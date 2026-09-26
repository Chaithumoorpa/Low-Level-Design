package com.lld.management.library.policy;

import com.lld.management.library.model.Loan;

import java.time.LocalDate;

/**
 * A fixed amount per late day, after a short grace period, capped so a forgotten book never costs more
 * than a set maximum. Losing a copy costs its price plus a handling fee.
 */
public class DailyFinePolicy implements FinePolicy {

    private final long centsPerDay;
    private final int graceDays;
    private final long capCents;
    private final long handlingFeeCents;
    private final long blockingThresholdCents;

    public DailyFinePolicy(long centsPerDay, int graceDays, long capCents, long handlingFeeCents,
                           long blockingThresholdCents) {
        this.centsPerDay = centsPerDay;
        this.graceDays = graceDays;
        this.capCents = capCents;
        this.handlingFeeCents = handlingFeeCents;
        this.blockingThresholdCents = blockingThresholdCents;
    }

    /** 25c a day, 1 grace day, capped at $10; $2 handling for lost books; blocked from $5. */
    public static DailyFinePolicy standard() {
        return new DailyFinePolicy(25, 1, 1000, 200, 500);
    }

    @Override
    public long lateFine(Loan loan, LocalDate returnedOn) {
        long late = loan.daysLate(returnedOn);
        if (late <= graceDays) {
            return 0;
        }
        return Math.min(capCents, late * centsPerDay);        // grace only waives very short delays
    }

    @Override
    public long lostFee(Loan loan) {
        return loan.copy().book().priceCents() + handlingFeeCents;
    }

    @Override
    public long blockingThresholdCents() {
        return blockingThresholdCents;
    }
}
