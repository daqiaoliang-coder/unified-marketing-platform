package com.example.marketing;

import com.example.marketing.engine.Amounts;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AmountsTest {
    @Test
    void allocatesByLargestRemainderRatherThanAlwaysGivingTheLastKeyTheDifference() {
        assertThat(Amounts.allocate(7, Map.of("a", 1L, "b", 2L, "c", 3L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 1L, "b", 2L, "c", 4L));
    }

    @Test
    void tiedRemaindersUseSortedKeysRegardlessOfInputOrder() {
        var weights = new LinkedHashMap<String, Long>();
        weights.put("c", 1L);
        weights.put("b", 1L);
        weights.put("a", 1L);
        var allocation = Amounts.allocate(2, weights);
        assertThat(allocation).containsExactlyInAnyOrderEntriesOf(Map.of("a", 1L, "b", 1L, "c", 0L));
        assertThat(allocation.keySet()).containsExactly("a", "b", "c");
        assertThat(new ArrayList<>(weights.keySet())).containsExactly("c", "b", "a");
    }

    @Test
    void zeroWeightNeverReceivesTheRemainder() {
        assertThat(Amounts.allocate(1, Map.of("a", 0L, "b", 1L, "c", 1L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 0L, "b", 1L, "c", 0L));
    }

    @Test
    void supportsTotalsLargerThanWeightSumForFunding() {
        assertThat(Amounts.allocate(100_001, Map.of("merchant", 6000L, "platform", 4000L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("merchant", 60_001L, "platform", 40_000L));
    }

    @Test
    void bothProductsAndWeightSumCanExceedLongRange() {
        assertThat(Amounts.allocate(Long.MAX_VALUE, Map.of("b", Long.MAX_VALUE, "a", Long.MAX_VALUE)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 4_611_686_018_427_387_904L,
                        "b", 4_611_686_018_427_387_903L));
        assertThat(Amounts.allocate(Long.MAX_VALUE, Map.of("only", Long.MAX_VALUE)))
                .containsEntry("only", Long.MAX_VALUE);
    }

    @Test
    void zeroTotalAllowsEmptyAndZeroWeights() {
        assertThat(Amounts.allocate(0, Map.of())).isEmpty();
        assertThat(Amounts.allocate(0, Map.of("a", 0L, "b", 9L)))
                .containsExactlyInAnyOrderEntriesOf(Map.of("a", 0L, "b", 0L));
    }

    @Test
    void rejectsUndefinedOrNegativeAllocations() {
        assertThatIllegalArgumentException().isThrownBy(() -> Amounts.allocate(-1, Map.of("a", 1L)));
        assertThatIllegalArgumentException().isThrownBy(() -> Amounts.allocate(0, Map.of("a", -1L)));
        assertThatIllegalArgumentException().isThrownBy(() -> Amounts.allocate(1, Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> Amounts.allocate(1, Map.of("a", 0L)));
    }

    @Test
    void resultIsImmutableAndIndependentOfInput() {
        var weights = new LinkedHashMap<>(Map.of("a", 3L, "b", 2L));
        var allocation = Amounts.allocate(4, weights);
        weights.put("a", 100L);
        assertThat(allocation).containsEntry("a", 2L).containsEntry("b", 2L);
        assertThatThrownBy(() -> allocation.put("a", 0L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void randomizedAllocationsConserveEveryCentAndRespectQuotaBounds() {
        var random = new Random(41230);
        for (int trial = 0; trial < 500; trial++) {
            long total = random.nextLong() & Long.MAX_VALUE;
            var weights = new LinkedHashMap<String, Long>();
            for (int index = 0; index < 1 + random.nextInt(12); index++) {
                weights.put("key-" + index, (random.nextLong() & Long.MAX_VALUE) | 1L);
            }
            var result = Amounts.allocate(total, weights);
            BigInteger sum = result.values().stream().map(BigInteger::valueOf)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            assertThat(sum).isEqualTo(BigInteger.valueOf(total));
            BigInteger weightSum = weights.values().stream().map(BigInteger::valueOf)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            for (var entry : weights.entrySet()) {
                long floor = BigInteger.valueOf(total).multiply(BigInteger.valueOf(entry.getValue()))
                        .divide(weightSum).longValueExact();
                long actual = result.get(entry.getKey());
                assertThat(actual).isGreaterThanOrEqualTo(floor);
                assertThat(actual - floor).isBetween(0L, 1L);
            }
        }
    }
}
