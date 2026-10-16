package com.example.travel.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a list into consecutive chunks of at most {@code size} elements,
 * used for chunked IN (...) loads and batch inserts (never one giant IN).
 */
public final class Chunks {

    private Chunks() {
    }

    public static <T> List<List<T>> chunk(List<T> items, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("chunk size must be >= 1");
        }
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(List.copyOf(items.subList(i, Math.min(items.size(), i + size))));
        }
        return chunks;
    }
}
