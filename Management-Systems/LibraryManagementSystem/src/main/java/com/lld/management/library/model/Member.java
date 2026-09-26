package com.lld.management.library.model;

/** A library card holder. Fines accumulate here until paid. */
public final class Member {

    private final String id;
    private final String name;
    private final MemberType type;
    private long finesDueCents;
    private boolean suspended;

    public Member(String id, String name, MemberType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public MemberType type() {
        return type;
    }

    public long finesDueCents() {
        return finesDueCents;
    }

    public boolean suspended() {
        return suspended;
    }

    public void charge(long cents) {
        finesDueCents += cents;
    }

    public void pay(long cents) {
        finesDueCents -= cents;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    @Override
    public String toString() {
        return name + " (" + type + ")";
    }
}
