package com.lld.booking.food.model;

/** Running average of 1–5 star ratings. */
public final class RatingTally {

    private long sum;
    private int count;

    public void add(int stars) {
        if (stars < 1 || stars > 5) {
            throw new FoodException("Stars must be 1 to 5");
        }
        sum += stars;
        count++;
    }

    /** Average, or 5.0 for someone not rated yet (benefit of the doubt). */
    public double average() {
        return count == 0 ? 5.0 : (double) sum / count;
    }

    public int count() {
        return count;
    }
}
