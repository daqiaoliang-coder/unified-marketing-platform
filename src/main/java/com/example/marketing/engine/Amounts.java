package com.example.marketing.engine;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Integer-cent allocation by largest remainder, with lexicographic key tie-breaking. */
public final class Amounts {
    private Amounts() {}

    public static Map<String, Long> allocate(long total, Map<String, Long> weights) {
        if (total < 0) {
            throw new IllegalArgumentException("Allocation total must be nonnegative");
        }
        Objects.requireNonNull(weights, "weights");
        var result = new TreeMap<String, Long>();
        BigInteger weightSum = BigInteger.ZERO;
        for (var entry : weights.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "weight key");
            long weight = Objects.requireNonNull(entry.getValue(), "weight value");
            if (weight < 0) {
                throw new IllegalArgumentException("Allocation weights must be nonnegative");
            }
            result.put(entry.getKey(), 0L);
            weightSum = weightSum.add(BigInteger.valueOf(weight));
        }
        if (total == 0) {
            return Collections.unmodifiableMap(result);
        }
        if (weightSum.signum() == 0) {
            throw new IllegalArgumentException("A positive total requires positive weights");
        }

        var remainders = new ArrayList<Remainder>();
        BigInteger amount = BigInteger.valueOf(total);
        long allocated = 0;
        for (var entry : weights.entrySet()) {
            BigInteger[] parts = amount.multiply(BigInteger.valueOf(entry.getValue()))
                    .divideAndRemainder(weightSum);
            long share = parts[0].longValueExact();
            result.put(entry.getKey(), share);
            allocated = Math.addExact(allocated, share);
            remainders.add(new Remainder(entry.getKey(), parts[1]));
        }
        remainders.sort(Comparator.comparing(Remainder::value).reversed()
                .thenComparing(Remainder::key));
        // The sum of fractional parts is an integer strictly below the key count.
        int remaining = Math.toIntExact(total - allocated);
        for (int i = 0; i < remaining; i++) {
            result.compute(remainders.get(i).key(), (key, value) -> value + 1);
        }
        return Collections.unmodifiableMap(result);
    }

    private record Remainder(String key, BigInteger value) {}
}
