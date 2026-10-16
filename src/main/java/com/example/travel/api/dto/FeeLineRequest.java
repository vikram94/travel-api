package com.example.travel.api.dto;

import java.math.BigDecimal;

/** A carrier/merchant fee line on this transaction (never a tax). */
public record FeeLineRequest(String name, BigDecimal amount) {
}
