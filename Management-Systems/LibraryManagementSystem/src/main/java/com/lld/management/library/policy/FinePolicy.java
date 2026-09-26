package com.lld.management.library.policy;

import com.lld.management.library.model.Loan;

import java.time.LocalDate;

/** How much a member owes for a late return, and what blocks further borrowing. */
public interface FinePolicy {

    /** Fine for returning {@code loan} on {@code returnedOn} (0 if on time). */
    long lateFine(Loan loan, LocalDate returnedOn);

    /** Charged when a borrowed copy is lost: replacement cost plus handling. */
    long lostFee(Loan loan);

    /** Members owing at least this much can't borrow, renew or place holds. */
    long blockingThresholdCents();
}
