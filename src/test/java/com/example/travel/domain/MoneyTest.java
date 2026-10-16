package com.example.travel.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesTwoDecimalAmountsIntoMinorUnits() {
        assertThat(Money.fromDecimal(new BigDecimal("220.00"), "total").minor()).isEqualTo(22000);
        assertThat(Money.fromDecimal(new BigDecimal("0.1"), "total").minor()).isEqualTo(10);
        assertThat(Money.fromDecimal(new BigDecimal("685.7"), "total").minor()).isEqualTo(68570);
        assertThat(Money.fromDecimal(BigDecimal.ZERO, "total").minor()).isZero();
    }

    @Test
    void rendersMinorUnitsAsTwoDecimalAmounts() {
        assertThat(Money.ofMinor(22000).toDecimal()).isEqualByComparingTo("220.00");
        assertThat(Money.ofMinor(5).toDecimal()).isEqualByComparingTo("0.05");
    }

    @Test
    void rejectsMoreThanTwoDecimals() {
        assertThatThrownBy(() -> Money.fromDecimal(new BigDecimal("1.505"), "total"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("2 decimal places");
    }

    @Test
    void rejectsTrailingZerosBeyondTwoPlacesButNotEquivalentOnes() {
        // 1.500 == 1.50: trailing zeros are fine, real extra precision is not
        assertThat(Money.fromDecimal(new BigDecimal("1.500"), "total").minor()).isEqualTo(150);
        assertThatThrownBy(() -> Money.fromDecimal(new BigDecimal("1.005"), "total"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.fromDecimal(new BigDecimal("-0.01"), "total"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    void rejectsMissingAmounts() {
        assertThatThrownBy(() -> Money.fromDecimal(null, "total"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsOutOfRangeAmounts() {
        assertThatThrownBy(() -> Money.fromDecimal(new BigDecimal("1e20"), "total"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void additionIsExactAndOverflowsLoudly() {
        assertThat(Money.ofMinor(100).plus(Money.ofMinor(50)).minor()).isEqualTo(150);
        assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE).plus(Money.ofMinor(1)))
                .isInstanceOf(ArithmeticException.class);
    }
}
