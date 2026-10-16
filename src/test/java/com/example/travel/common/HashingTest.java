package com.example.travel.common;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void hashIsOrderIndependent() {
        assertThat(Hashing.txnSetHash(List.of("txn_b", "txn_a", "txn_c")))
                .isEqualTo(Hashing.txnSetHash(List.of("txn_c", "txn_a", "txn_b")));
    }

    @Test
    void joinDelimiterMatters() {
        // ["a","b"] joins to "a\nb", ["ab"] to "ab" — concatenation without a
        // delimiter would collide
        assertThat(Hashing.txnSetHash(List.of("a", "b")))
                .isNotEqualTo(Hashing.txnSetHash(List.of("ab")));
    }

    @Test
    void producesSixtyFourLowercaseHexCharacters() {
        String hash = Hashing.txnSetHash(List.of("txn_1"));
        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void differentSetsProduceDifferentHashes() {
        assertThat(Hashing.txnSetHash(List.of("txn_1")))
                .isNotEqualTo(Hashing.txnSetHash(List.of("txn_1", "txn_2")));
    }
}
