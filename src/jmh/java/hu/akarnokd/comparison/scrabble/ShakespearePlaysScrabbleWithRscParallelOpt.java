/*
 * Copyright (C) 2019 Jos� Paumard
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package hu.akarnokd.comparison.scrabble;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.function.Function;

import org.openjdk.jmh.annotations.*;

import rsc.publisher.Px;


/**
 * Shakespeare plays Scrabble with Reactive-Streams-Commons parallel.
 * @author José
 * @author akarnokd
 */
public class ShakespearePlaysScrabbleWithRscParallelOpt extends ShakespearePlaysScrabble {

    static Px<Integer> chars(String word) {
//        return Px.range(0, word.length()).map(i -> (int)word.charAt(i));
        return Px.characters(word);
    }

//    @Param({/*"1", "2", "3", "4", "5", "6", "7",*/ "8"})
    public int cores = 8;

//    @Param({ /*"1", "2", "4", "8", "16", "32", "64",*/ "128"/*, "256", "512", "1024"*/})
    public int prefetch = 128;

//    @Param({"false"})
    public boolean fj;

    rsc.scheduler.Scheduler scheduler;

    @Setup
    public void setupThis() {
        if (fj) {
            scheduler = new rsc.scheduler.ExecutorServiceScheduler(ForkJoinPool.commonPool(), false);
        } else {
            scheduler = new rsc.scheduler.ParallelScheduler(cores);
        }
    }

    @TearDown
    public void teardown() {
        scheduler.shutdown();
    }

    @SuppressWarnings("unused")
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Warmup(
        iterations = 5, time = 1
    )
    @Measurement(
        iterations = 5, time = 1
    )
    @Fork(1)
    public List<Entry<Integer, List<String>>> measureThroughput() throws InterruptedException {

        //  to compute the score of a given word
        Function<Integer, Integer> scoreOfALetter = letter -> letterScores[letter - 'a'];

        // score of the same letters in a word
        Function<Entry<Integer, MutableLong>, Integer> letterScore =
                entry ->
                        letterScores[entry.getKey() - 'a'] *
                        Integer.min(
                                (int)entry.getValue().get(),
                                scrabbleAvailableLetters[entry.getKey() - 'a']
                            )
                    ;


        Function<String, Px<Integer>> toIntegerPx =
                string -> chars(string);

        // Histogram of the letters in a given word
        Function<String, Px<HashMap<Integer, MutableLong>>> histoOfLetters =
                word -> toIntegerPx.apply(word)
                            .collect(
                                () -> new HashMap<>(),
                                (HashMap<Integer, MutableLong> map, Integer value) ->
                                    {
                                        MutableLong newValue = map.get(value) ;
                                        if (newValue == null) {
                                            newValue = new MutableLong();
                                            map.put(value, newValue);
                                        }
                                        newValue.incAndSet();
                                    }

                            ) ;

        // number of blanks for a given letter
        Function<Entry<Integer, MutableLong>, Long> blank =
                entry ->
                        Long.max(
                            0L,
                            entry.getValue().get() -
                            scrabbleAvailableLetters[entry.getKey() - 'a']
                        )
                    ;

        // number of blanks for a given word
        Function<String, Px<Long>> nBlanks =
                word -> histoOfLetters.apply(word)
                            .flatMapIterable(map -> map.entrySet())
                            .map(blank)
                            .sumLong();


        // can a word be written with 2 blanks?
        Function<String, Px<Boolean>> checkBlanks =
                word -> nBlanks.apply(word)
                            .map(l -> l <= 2L) ;

        // score taking blanks into account letterScore1
        Function<String, Px<Integer>> score2 =
                word -> histoOfLetters.apply(word)
                            .flatMapIterable(map -> map.entrySet())
                            .map(letterScore)
                            .sumInt() ;

        // Placing the word on the board
        // Building the streams of first and last letters
        Function<String, Px<Integer>> first3 =
                word -> chars(word).take(3) ;
        Function<String, Px<Integer>> last3 =
                word -> chars(word).skip(3) ;


        // Stream to be maxed
        Function<String, Px<Integer>> toBeMaxed =
            word -> Px.concatArray(first3.apply(word), last3.apply(word))
            ;

        // Bonus for double letter
        Function<String, Px<Integer>> bonusForDoubleLetter =
            word -> toBeMaxed.apply(word)
                        .map(scoreOfALetter)
                        .maxInt() ;

        // score of the word put on the board
        Function<String, Px<Integer>> score3 =
            word ->
                Px.concatArray(
                        score2.apply(word),
                        bonusForDoubleLetter.apply(word)
                )
                .sumInt().map(v -> 2 * v + (word.length() == 7 ? 50 : 0)) ;

        Function<Function<String, Px<Integer>>, Px<TreeMap<Integer, List<String>>>> buildHistoOnScore =
                score -> Px.fromIterable(shakespeareWords)
                                .parallel()
                                .runOn(scheduler, prefetch)
                                .filter(scrabbleWords::contains)
                                .filter(word -> checkBlanks.apply(word).blockingFirst())
                                .collect(
                                    () -> new TreeMap<Integer, List<String>>(Comparator.reverseOrder()),
                                    (TreeMap<Integer, List<String>> map, String word) -> {
                                        Integer key = score.apply(word).blockingFirst();
                                        List<String> list = map.get(key) ;
                                        if (list == null) {
                                            list = new ArrayList<>() ;
                                            map.put(key, list) ;
                                        }
                                        list.add(word) ;
                                    }
                                )
                                .reduce((m1, m2) -> {
                                    for (Map.Entry<Integer, List<String>> e : m2.entrySet()) {
                                        List<String> list = m1.get(e.getKey());
                                        if (list == null) {
                                            m1.put(e.getKey(), e.getValue());
                                        } else {
                                            list.addAll(e.getValue());
                                        }
                                    }
                                    return m1;
                                });

        // best key / value pairs
        List<Entry<Integer, List<String>>> finalList2 =
                buildHistoOnScore.apply(score3)
                    .flatMapIterable(map -> map.entrySet())
                    .take(3)
                    .collect(
                        () -> new ArrayList<Entry<Integer, List<String>>>(),
                        (list, entry) -> {
                            list.add(entry) ;
                        }
                    )
                    .blockingFirst() ;


//        System.out.println(finalList2);

        return finalList2 ;
    }

    public static void main(String[] args) throws Exception {
        ShakespearePlaysScrabbleWithRscParallelOpt s = new ShakespearePlaysScrabbleWithRscParallelOpt();
        s.init();
        s.setupThis();
        try {
            System.out.println(s.measureThroughput());
        } finally {
            s.teardown();
        }
    }
}