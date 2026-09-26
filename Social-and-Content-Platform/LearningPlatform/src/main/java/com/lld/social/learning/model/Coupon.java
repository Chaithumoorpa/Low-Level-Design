package com.lld.social.learning.model;

import java.time.Instant;

/** An instructor's discount code for one course: percent off, expiry, limited uses. */
public final class Coupon {

    private final String code;
    private final String courseId;
    private final int percentOff;
    private final Instant expiresAt;
    private final int maxUses;
    private int used;

    public Coupon(String code, String courseId, int percentOff, Instant expiresAt, int maxUses) {
        if (percentOff < 1 || percentOff > 100 || maxUses < 1) {
            throw new LearningException("Invalid coupon " + code);
        }
        this.code = code;
        this.courseId = courseId;
        this.percentOff = percentOff;
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
    }

    public String code() {
        return code;
    }

    public String courseId() {
        return courseId;
    }

    public int percentOff() {
        return percentOff;
    }

    public int usesLeft() {
        return maxUses - used;
    }

    /** @return null if usable, else why not */
    public String problem(String forCourse, Instant now) {
        if (!courseId.equals(forCourse)) {
            return "coupon " + code + " is for another course";
        }
        if (!now.isBefore(expiresAt)) {
            return "coupon " + code + " expired";
        }
        if (used >= maxUses) {
            return "coupon " + code + " has been used up";
        }
        return null;
    }

    public long apply(long priceCents) {
        return priceCents - Math.floorDiv(priceCents * percentOff + 50, 100);
    }

    public void use() {
        used++;
    }

    public void unuse() {
        used--;
    }
}
