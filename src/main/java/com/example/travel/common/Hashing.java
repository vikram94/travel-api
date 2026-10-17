package com.example.travel.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;

/**
 * Transaction-set fingerprint: SHA-256 over the sorted ids joined with '\n'.
 * Sorting inside the hash makes it order-independent; billing an identical set
 * in a different order yields the same fingerprint. Ids never contain '\n'
 * (validated: no control characters), so the encoding is unambiguous.
 */
public final class Hashing {

    private Hashing() {
    }

    public static String txnSetHash(Collection<String> transactionIds) {
        String joined = String.join("\n", transactionIds.stream().sorted().toList());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
