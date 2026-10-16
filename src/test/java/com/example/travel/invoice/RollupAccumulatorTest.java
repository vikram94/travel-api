package com.example.travel.invoice;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RollupAccumulatorTest {

    @Test
    void taxesGroupByNameAndRateWithScaleNormalizedRates() {
        RollupAccumulator rollups = new RollupAccumulator();
        rollups.addTax("VAT", new BigDecimal("0.07"), 2170);
        rollups.addTax("VAT", new BigDecimal("0.0700"), 500); // same rate, different scale
        rollups.addTax("VAT", new BigDecimal("0.20"), 1000); // different rate -> own line
        rollups.addTax("Airport tax", null, 1500); // null rate allowed

        assertThat(rollups.taxLines()).containsExactly(
                new RollupAccumulator.TaxRollupLine("Airport tax", null, 1500),
                new RollupAccumulator.TaxRollupLine("VAT", new BigDecimal("0.0700"), 2670),
                new RollupAccumulator.TaxRollupLine("VAT", new BigDecimal("0.2000"), 1000));
    }

    @Test
    void feesGroupByNameOnly() {
        RollupAccumulator rollups = new RollupAccumulator();
        rollups.addFee("YQ", 2500);
        rollups.addFee("YQ", 300);
        rollups.addFee("Booking fee", 400);

        assertThat(rollups.feeLines()).containsExactly(
                new RollupAccumulator.FeeRollupLine("Booking fee", 400),
                new RollupAccumulator.FeeRollupLine("YQ", 2800));
    }

    @Test
    void sumsAreExactAndOverflowLoudly() {
        RollupAccumulator rollups = new RollupAccumulator();
        rollups.addFee("YQ", Long.MAX_VALUE);
        assertThatThrownBy(() -> rollups.addFee("YQ", 1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void nullAndZeroRatesAreDistinctLines() {
        RollupAccumulator rollups = new RollupAccumulator();
        rollups.addTax("VAT", null, 100);
        rollups.addTax("VAT", BigDecimal.ZERO, 200);

        assertThat(rollups.taxLines()).hasSize(2);
    }
}
