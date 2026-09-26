package com.lld.management.library.model;

/**
 * Borrowing privileges per membership type. Adding a type (e.g. RESEARCHER) is one line here and
 * nothing else changes.
 */
public enum MemberType {
    //      max loans, loan days, max renewals
    GUEST(1, 7, 0),
    STUDENT(3, 14, 1),
    FACULTY(10, 30, 2);

    private final int maxLoans;
    private final int loanDays;
    private final int maxRenewals;

    MemberType(int maxLoans, int loanDays, int maxRenewals) {
        this.maxLoans = maxLoans;
        this.loanDays = loanDays;
        this.maxRenewals = maxRenewals;
    }

    public int maxLoans() {
        return maxLoans;
    }

    public int loanDays() {
        return loanDays;
    }

    public int maxRenewals() {
        return maxRenewals;
    }
}
