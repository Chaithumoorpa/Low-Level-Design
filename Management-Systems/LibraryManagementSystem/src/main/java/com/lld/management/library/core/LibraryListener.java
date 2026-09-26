package com.lld.management.library.core;

import com.lld.management.library.model.Hold;
import com.lld.management.library.model.Loan;

/** Observer for member notices (e-mail/SMS in a real system). */
public interface LibraryListener {

    /** A copy is on the hold shelf for this member. */
    default void onHoldReady(Hold hold) {
    }

    /** The member didn't collect in time; the copy moved on. */
    default void onHoldExpired(Hold hold) {
    }

    default void onDueSoon(Loan loan) {
    }

    default void onOverdue(Loan loan, long daysLate) {
    }
}
