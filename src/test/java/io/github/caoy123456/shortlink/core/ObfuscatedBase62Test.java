package io.github.caoy123456.shortlink.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contract tests for {@link ObfuscatedBase62}. The implementation is a
 * stub, so every test fails until you fill it in - that is the point:
 * green here is the definition of done.
 *
 * <p>Mirrors {@link Base62Test}'s style: known values, round trip, and
 * uniqueness. Plus one test M1 did not need: avalanche.</p>
 */
class ObfuscatedBase62Test {

    @Test
    void obfuscateStaysInsideDomain() {
        // The domain edges and a couple of ordinary ids: whatever the
        // Feistel network spits out, cycle walking must pull it back in.
        for (long id : new long[] {0, 1, 42, ObfuscatedBase62.DOMAIN - 1}) {
            assertThat(ObfuscatedBase62.obfuscate(id))
                    .isBetween(0L, ObfuscatedBase62.DOMAIN - 1);
        }
    }

    @Test
    void roundTripRestoresTheId() {
        long[] fixed = {0, 1, 42, 1_000_000_000L, ObfuscatedBase62.DOMAIN - 1};

        for (long id : fixed) {
            assertThat(ObfuscatedBase62.deobfuscate(ObfuscatedBase62.obfuscate(id)))
                    .as("id %d", id)
                    .isEqualTo(id);
        }

        // The edges are easy to special-case by accident; random values are
        // not. nextLong(bound) returns values in [0, bound), which is the
        // domain itself.
        RandomGenerator rng = RandomGenerator.getDefault();
        for (int i = 0; i < 10_000; i++) {
            long id = rng.nextLong(ObfuscatedBase62.DOMAIN);
            assertThat(ObfuscatedBase62.deobfuscate(ObfuscatedBase62.obfuscate(id)))
                    .isEqualTo(id);
        }
    }

    @Test
    void distinctIdsMapToDistinctCodes() {
        // TODO(you): like Base62Test's no-collision test - a few thousand
        //   sequential ids (say 10_000, starting at 1_000_000_000) must
        //   produce 10_000 distinct obfuscated values.
        //   Set<Long> seen = new HashSet<>(); ... assertThat(seen).hasSize(n);
        long first = 1_000_000_000L;
        Set<Long> seen = new HashSet<>();
        for (long id = first; id < first + 10_000; id++) {
            seen.add(ObfuscatedBase62.obfuscate(id));
        }
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void adjacentIdsDoNotLookAdjacent() {
        // The property M1 lacked. Compare obfuscate(id) and obfuscate(id+1)
        // bit by bit: on average about half the 36 bits should differ, and
        // never as few as, say, 8 (a suspiciously similar pair).
        //
        // TODO(you): implement hammingDistance(long a, long b) below, then:
        //   for 200 random ids, distance = hammingDistance(out1, out2);
        //   assert distance >= 8 for every pair;
        //   assert average distance is in [12, 24] (roughly half of 36).
        RandomGenerator rng = RandomGenerator.getDefault();
        int min = Integer.MAX_VALUE;
        long total = 0;
        int samples = 200;

        for (int i = 0; i < samples; i++) {
            long id = rng.nextLong(ObfuscatedBase62.DOMAIN - 1);
            int distance = hammingDistance(
                    ObfuscatedBase62.obfuscate(id),
                    ObfuscatedBase62.obfuscate(id + 1));
            min = Math.min(min, distance);
            total += distance;
        }
        double average = (double) total / samples;

        // Never as few as 8 differing bits (a suspiciously similar pair),
        // and on average about half of the 36 bits differ.
        assertThat(min).isGreaterThanOrEqualTo(8);
        assertThat(average).isBetween(12.0, 24.0);
    }

    @Test
    void rejectsValuesOutsideDomain() {
        // TODO(you): obfuscate(-1) and obfuscate(DOMAIN) should throw
        //   IllegalArgumentException - fail fast on contract violation.
        assertThrows(IllegalArgumentException.class, () -> ObfuscatedBase62.obfuscate(-1));
        assertThrows(IllegalArgumentException.class, () -> ObfuscatedBase62.obfuscate(ObfuscatedBase62.DOMAIN));
    }

    /** Number of differing bits between two values. Long.bitCount(a XOR b). */
    private static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
