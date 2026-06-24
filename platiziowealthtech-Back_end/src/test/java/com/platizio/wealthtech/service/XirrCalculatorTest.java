package com.platizio.wealthtech.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.platizio.wealthtech.service.XirrCalculator.Cashflow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed XIRR scenarios + degenerate-input guards for {@link XirrCalculator}.
 *
 * <p>XIRR solves {@code Σ cf_i / (1+r)^(days_i/365) = 0}. Each "expected" rate below
 * is derived analytically, then asserted with a tolerance so Newton-Raphson /
 * bisection convergence is checked against the true root rather than itself.
 */
class XirrCalculatorTest {

    /**
     * Scenario 1 — a single one-year investment that doubled ≈ +100%.
     * Invest 1,000 on 2024-01-01, worth 2,000 exactly 365 days later (2024-12-31).
     * NPV: -1000 + 2000/(1+r)^1 = 0  ⇒  (1+r) = 2  ⇒  r = 1.0 (100%).
     */
    @Test
    void singleYearDoubling_isApproximately100Percent() {
        Double rate = XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2024, 1, 1), -1000.0),
                new Cashflow(LocalDate.of(2024, 12, 31), 2000.0)));

        assertThat(rate).isNotNull();
        assertThat(rate).isCloseTo(1.0, within(1e-3));
    }

    /**
     * Scenario 2 — a multi-cashflow case with a known closed-form root.
     * Two outflows of 1,000 (day 0 and day 365) and one inflow of 2,210 at day 730.
     * Let x = (1+r). NPV scaled to yearly powers (0, 1, 2):
     *   -1000 - 1000/x + 2210/x^2 = 0
     *   ⇒ 1000 x^2 + 1000 x - 2210 = 0
     *   ⇒ x = (-1000 + sqrt(1000^2 + 4*1000*2210)) / (2*1000)
     *   ⇒ x = (-1 + sqrt(1 + 8.84)) / 2 = (-1 + sqrt(9.84))/2 ≈ 1.0681
     *   ⇒ r ≈ 0.0681 (6.81%).
     */
    @Test
    void multiCashflow_matchesClosedFormRoot() {
        double x = (-1.0 + Math.sqrt(9.84)) / 2.0;
        double expected = x - 1.0; // ≈ 0.06810

        Double rate = XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2022, 1, 1), -1000.0),
                new Cashflow(LocalDate.of(2023, 1, 1), -1000.0),  // +365 days
                new Cashflow(LocalDate.of(2024, 1, 1), 2210.0)));  // +730 days

        assertThat(rate).isNotNull();
        assertThat(rate).isCloseTo(expected, within(1e-3));
        // Independently sanity-check the NPV at the solved rate is ~0.
        double base = 1.0 + rate;
        double npv = -1000.0 - 1000.0 / base + 2210.0 / (base * base);
        assertThat(npv).isCloseTo(0.0, within(1e-2));
    }

    /**
     * Scenario 3 — a loss case (≈ -50%). Invest 2,000, get back 1,000 a year later.
     * -2000 + 1000/(1+r) = 0 ⇒ (1+r) = 0.5 ⇒ r = -0.5.
     */
    @Test
    void halvedOverOneYear_isApproximatelyMinus50Percent() {
        Double rate = XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2024, 1, 1), -2000.0),
                new Cashflow(LocalDate.of(2024, 12, 31), 1000.0)));

        assertThat(rate).isNotNull();
        assertThat(rate).isCloseTo(-0.5, within(1e-3));
    }

    @Test
    void returnsNullForFewerThanTwoCashflows() {
        assertThat(XirrCalculator.compute(List.of())).isNull();
        assertThat(XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2024, 1, 1), -1000.0)))).isNull();
        assertThat(XirrCalculator.compute(null)).isNull();
    }

    @Test
    void returnsNullWhenAllCashflowsSameSign() {
        assertThat(XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2024, 1, 1), -1000.0),
                new Cashflow(LocalDate.of(2024, 12, 31), -2000.0)))).isNull();
        assertThat(XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2024, 1, 1), 1000.0),
                new Cashflow(LocalDate.of(2024, 12, 31), 2000.0)))).isNull();
    }

    @Test
    void returnsNullWhenAllCashflowsOnSameDay() {
        assertThat(XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2024, 1, 1), -1000.0),
                new Cashflow(LocalDate.of(2024, 1, 1), 1500.0)))).isNull();
    }

    @Test
    void neverReturnsNanOrInfinity() {
        // An extreme near-total-loss still resolves to a finite rate, never NaN/Inf.
        Double rate = XirrCalculator.compute(List.of(
                new Cashflow(LocalDate.of(2024, 1, 1), -1_000_000.0),
                new Cashflow(LocalDate.of(2024, 12, 31), 1.0)));
        assertThat(rate).isNotNull();
        assertThat(Double.isNaN(rate)).isFalse();
        assertThat(Double.isInfinite(rate)).isFalse();
    }
}
