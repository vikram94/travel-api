package com.example.travel.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates invoice-level rollups while a snapshot is built.
 * Taxes group by (name, rate) — rate normalized to scale 4 and compared via
 * compareTo so 0.07 and 0.0700 are one line; fees group by name only and come
 * from fee_lines only (navan_fee totals are transaction totals, not fee lines).
 * All sums are exact ({@link Math#addExact}); overflows surface as 400.
 */
public class RollupAccumulator {

    /** Grouping key: name first, then rate via compareTo (null rate allowed). */
    record TaxKey(String name, BigDecimal rate) implements Comparable<TaxKey> {

        @Override
        public int compareTo(TaxKey other) {
            int byName = name.compareTo(other.name);
            if (byName != 0) {
                return byName;
            }
            if (rate == null) {
                return other.rate == null ? 0 : -1;
            }
            if (other.rate == null) {
                return 1;
            }
            return rate.compareTo(other.rate);
        }
    }

    public record TaxRollupLine(String name, BigDecimal rate, long amountMinor) {
    }

    public record FeeRollupLine(String name, long amountMinor) {
    }

    private final Map<TaxKey, Long> taxes = new TreeMap<>();
    private final Map<String, Long> fees = new TreeMap<>();

    public void addTax(String name, BigDecimal rate, long amountMinor) {
        BigDecimal normalized = rate == null ? null : rate.setScale(4, RoundingMode.HALF_UP);
        taxes.merge(new TaxKey(name, normalized), amountMinor, Math::addExact);
    }

    public void addFee(String name, long amountMinor) {
        fees.merge(name, amountMinor, Math::addExact);
    }

    public List<TaxRollupLine> taxLines() {
        List<TaxRollupLine> lines = new ArrayList<>();
        taxes.forEach((key, amount) -> lines.add(new TaxRollupLine(key.name(), key.rate(), amount)));
        return List.copyOf(lines);
    }

    public List<FeeRollupLine> feeLines() {
        List<FeeRollupLine> lines = new ArrayList<>();
        fees.forEach((name, amount) -> lines.add(new FeeRollupLine(name, amount)));
        return List.copyOf(lines);
    }
}
