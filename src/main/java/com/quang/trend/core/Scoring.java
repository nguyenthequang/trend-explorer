package com.quang.trend.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Theme extraction (build plan §6): tokenization plus smoothed log-odds
 * between the "now" window and a baseline window.
 *
 * lo(w) = ln((f_now + a) / (N_now + a*V)) - ln((f_base + a) / (N_base + a*V))
 * where V is the union vocabulary size and a is the smoothing constant.
 */
public final class Scoring {

    public record TermScore(String term, double score) {}

    private static final Pattern NON_TOKEN = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    private Scoring() {}

    /**
     * Lowercased unigram + bigram counts over the given texts.
     * Stopwords and tokens shorter than 3 characters are removed first;
     * bigrams are formed from adjacent surviving tokens.
     */
    public static Map<String, Integer> countTerms(List<String> texts, Set<String> stopwords) {
        Map<String, Integer> counts = new HashMap<>();
        for (String text : texts) {
            if (text == null) continue;
            String[] parts = NON_TOKEN.split(text.toLowerCase());
            List<String> tokens = new ArrayList<>();
            for (String p : parts) {
                if (p.length() >= 3 && !stopwords.contains(p)) {
                    tokens.add(p);
                }
            }
            for (int i = 0; i < tokens.size(); i++) {
                counts.merge(tokens.get(i), 1, Integer::sum);
                if (i + 1 < tokens.size()) {
                    counts.merge(tokens.get(i) + " " + tokens.get(i + 1), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** All terms of the union vocabulary, scored and sorted descending (emerging first). */
    public static List<TermScore> logOdds(Map<String, Integer> now,
                                          Map<String, Integer> base,
                                          double alpha) {
        Set<String> vocab = new HashSet<>(now.keySet());
        vocab.addAll(base.keySet());
        double v = vocab.size();
        double nNow = now.values().stream().mapToInt(Integer::intValue).sum();
        double nBase = base.values().stream().mapToInt(Integer::intValue).sum();

        List<TermScore> out = new ArrayList<>(vocab.size());
        for (String w : vocab) {
            double fNow = now.getOrDefault(w, 0);
            double fBase = base.getOrDefault(w, 0);
            double lo = Math.log((fNow + alpha) / (nNow + alpha * v))
                      - Math.log((fBase + alpha) / (nBase + alpha * v));
            out.add(new TermScore(w, lo));
        }
        out.sort(Comparator.comparingDouble(TermScore::score).reversed());
        return out;
    }

    /** Top-k emerging terms (highest positive log-odds). */
    public static List<TermScore> emerging(List<TermScore> ranked, int k) {
        return ranked.subList(0, Math.min(k, ranked.size()));
    }

    /** Top-k fading terms (most negative log-odds first). */
    public static List<TermScore> fading(List<TermScore> ranked, int k) {
        List<TermScore> copy = new ArrayList<>(ranked);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(k, copy.size()));
    }
}
