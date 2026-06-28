package com.platizio.wealthtech.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Money-weighted return (XIRR) over irregularly dated cashflows, used by the
 * Phase-3 investor dashboard (FR-DASH XIRR). Purchases are negative cashflows on
 * their dates, redemptions positive on theirs, and the live portfolio value is a
 * positive cashflow dated "today".
 *
 * <p>XIRR is the rate {@code r} that solves
 * {@code NPV(r) = Σ cf_i / (1 + r)^(days_i / 365) = 0}. We solve it with
 * Newton-Raphson seeded at {@code r = 0.1}, falling back to bracketed bisection
 * when Newton fails to converge (flat/ill-conditioned derivative or it walks out
 * of the valid domain {@code r > -1}).
 *
 * <p>The result is {@code null} (never {@code NaN}/{@code Infinity}) when XIRR is
 * mathematically undefined: fewer than two cashflows, all cashflows the same sign
 * (no internal rate exists), a zero net horizon, or non-convergence. Callers must
 * treat {@code null} as "unavailable", never as zero.
 */
public final class XirrCalculator {

    /** Newton-Raphson convergence tolerance on the NPV (in cashflow currency units). */
    private static final double NPV_TOLERANCE = 1e-7;
    private static final int MAX_NEWTON_ITERATIONS = 100;
    private static final int MAX_BISECTION_ITERATIONS = 200;
    private static final double DAYS_PER_YEAR = 365.0;
    /** Rates below this are outside the valid domain (1 + r must stay positive). */
    private static final double MIN_RATE = -0.999999999;

    private XirrCalculator() {
    }

    /** A single dated cashflow. Negative = money out (purchase), positive = money in (redemption/value). */
    public record Cashflow(LocalDate date, double amount) {
    }

    /**
     * @return the annualized money-weighted rate (e.g. {@code 0.1} = 10%), or
     *         {@code null} when XIRR is undefined for these cashflows.
     */
    public static Double compute(List<Cashflow> rawCashflows) {
        if (rawCashflows == null || rawCashflows.size() < 2) {
            return null;
        }

        List<Cashflow> cashflows = new ArrayList<>(rawCashflows);
        cashflows.sort(Comparator.comparing(Cashflow::date));

        LocalDate origin = cashflows.get(0).date();
        boolean hasPositive = false;
        boolean hasNegative = false;
        boolean hasNonZeroHorizon = false;
        for (Cashflow cf : cashflows) {
            if (cf.amount() > 0) {
                hasPositive = true;
            } else if (cf.amount() < 0) {
                hasNegative = true;
            }
            if (!cf.date().isEqual(origin)) {
                hasNonZeroHorizon = true;
            }
        }
        // An internal rate of return only exists when cashflows change sign and
        // span more than a single instant; otherwise XIRR is undefined.
        if (!hasPositive || !hasNegative || !hasNonZeroHorizon) {
            return null;
        }

        Double newton = solveNewton(cashflows, origin);
        if (newton != null) {
            return newton;
        }
        return solveBisection(cashflows, origin);
    }

    private static Double solveNewton(List<Cashflow> cashflows, LocalDate origin) {
        double rate = 0.1;
        for (int i = 0; i < MAX_NEWTON_ITERATIONS; i++) {
            double npv = npv(cashflows, origin, rate);
            if (!isFinite(npv)) {
                return null;
            }
            if (Math.abs(npv) < NPV_TOLERANCE) {
                return isFinite(rate) ? rate : null;
            }
            double derivative = npvDerivative(cashflows, origin, rate);
            if (!isFinite(derivative) || Math.abs(derivative) < 1e-12) {
                return null; // flat/ill-conditioned slope → hand off to bisection
            }
            double next = rate - npv / derivative;
            if (!isFinite(next) || next <= MIN_RATE) {
                return null; // walked out of the valid domain → hand off to bisection
            }
            if (Math.abs(next - rate) < 1e-12) {
                rate = next;
                break;
            }
            rate = next;
        }
        double finalNpv = npv(cashflows, origin, rate);
        if (isFinite(rate) && isFinite(finalNpv) && Math.abs(finalNpv) < 1e-4) {
            return rate;
        }
        return null;
    }

    /**
     * Bracketed bisection fallback. Expands the upper bound geometrically to find a
     * sign change in NPV, then bisects to the root. Robust where Newton diverges.
     */
    private static Double solveBisection(List<Cashflow> cashflows, LocalDate origin) {
        double low = MIN_RATE;
        double npvLow = npv(cashflows, origin, low);

        // Find a high bound whose NPV has the opposite sign of npvLow.
        double high = 1.0;
        double npvHigh = npv(cashflows, origin, high);
        int expansions = 0;
        while (isFinite(npvLow) && isFinite(npvHigh)
                && Math.signum(npvLow) == Math.signum(npvHigh)
                && expansions < 100) {
            high *= 2.0;
            npvHigh = npv(cashflows, origin, high);
            expansions++;
        }
        if (!isFinite(npvLow) || !isFinite(npvHigh)
                || Math.signum(npvLow) == Math.signum(npvHigh)) {
            return null; // no sign change bracketed → XIRR not resolvable
        }

        for (int i = 0; i < MAX_BISECTION_ITERATIONS; i++) {
            double mid = (low + high) / 2.0;
            double npvMid = npv(cashflows, origin, mid);
            if (!isFinite(npvMid)) {
                return null;
            }
            if (Math.abs(npvMid) < NPV_TOLERANCE || (high - low) / 2.0 < 1e-12) {
                return mid;
            }
            if (Math.signum(npvMid) == Math.signum(npvLow)) {
                low = mid;
                npvLow = npvMid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2.0;
    }

    private static double npv(List<Cashflow> cashflows, LocalDate origin, double rate) {
        double base = 1.0 + rate;
        if (base <= 0) {
            return Double.NaN;
        }
        double total = 0.0;
        for (Cashflow cf : cashflows) {
            double years = ChronoUnit.DAYS.between(origin, cf.date()) / DAYS_PER_YEAR;
            total += cf.amount() / Math.pow(base, years);
        }
        return total;
    }

    private static double npvDerivative(List<Cashflow> cashflows, LocalDate origin, double rate) {
        double base = 1.0 + rate;
        if (base <= 0) {
            return Double.NaN;
        }
        double total = 0.0;
        for (Cashflow cf : cashflows) {
            double years = ChronoUnit.DAYS.between(origin, cf.date()) / DAYS_PER_YEAR;
            // d/dr [ cf * (1+r)^(-years) ] = -years * cf * (1+r)^(-years-1)
            total += -years * cf.amount() / Math.pow(base, years + 1.0);
        }
        return total;
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /** Convenience overload accepting {@link BigDecimal} amounts. */
    public static Double computeDecimal(List<DecimalCashflow> rawCashflows) {
        if (rawCashflows == null) {
            return null;
        }
        List<Cashflow> mapped = new ArrayList<>(rawCashflows.size());
        for (DecimalCashflow cf : rawCashflows) {
            if (cf.date() == null || cf.amount() == null) {
                continue;
            }
            mapped.add(new Cashflow(cf.date(), cf.amount().doubleValue()));
        }
        return compute(mapped);
    }

    /** {@link BigDecimal}-valued dated cashflow for the decimal convenience overload. */
    public record DecimalCashflow(LocalDate date, BigDecimal amount) {
    }
}
