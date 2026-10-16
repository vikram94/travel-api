package com.example.travel.domain;

/** Parsed fee line on one transaction (never part of a tax rollup). */
public record FeeLine(String name, long amountMinor) {
}
