package com.lld.management.parkinglot.pricing;

import com.lld.management.parkinglot.model.VehicleType;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Common garage tariff:
 * <ul>
 *   <li>free for a short <b>grace period</b> (drop-offs, turning back at a full lot),</li>
 *   <li>otherwise per started hour, at a rate per vehicle type,</li>
 *   <li>capped per 24 hours (<b>daily maximum</b>),</li>
 *   <li>lost ticket = one daily maximum.</li>
 * </ul>
 */
public class HourlyPricing implements PricingStrategy {

    private final Map<VehicleType, Long> hourlyRate;
    private final Map<VehicleType, Long> dailyCap;
    private final Duration grace;

    public HourlyPricing(Map<VehicleType, Long> hourlyRate, Map<VehicleType, Long> dailyCap, Duration grace) {
        for (VehicleType t : VehicleType.values()) {
            if (!hourlyRate.containsKey(t) || !dailyCap.containsKey(t)) {
                throw new IllegalArgumentException("Rates are needed for every vehicle type; missing " + t);
            }
        }
        this.hourlyRate = new EnumMap<>(hourlyRate);
        this.dailyCap = new EnumMap<>(dailyCap);
        this.grace = grace;
    }

    /** Sensible defaults: $2/h car, $1/h motorcycle, $4/h van/truck; caps at 10x the hourly rate; 15 min grace. */
    public static HourlyPricing standard() {
        Map<VehicleType, Long> rate = new EnumMap<>(VehicleType.class);
        rate.put(VehicleType.MOTORCYCLE, 100L);
        rate.put(VehicleType.CAR, 200L);
        rate.put(VehicleType.ELECTRIC_CAR, 200L);
        rate.put(VehicleType.VAN, 400L);
        rate.put(VehicleType.TRUCK, 400L);
        Map<VehicleType, Long> cap = new EnumMap<>(VehicleType.class);
        rate.forEach((t, r) -> cap.put(t, r * 10));
        return new HourlyPricing(rate, cap, Duration.ofMinutes(15));
    }

    @Override
    public long fee(VehicleType type, Duration stay) {
        if (stay.isNegative()) {
            throw new IllegalArgumentException("Negative stay");
        }
        if (stay.compareTo(grace) <= 0) {
            return 0;
        }
        long fullDays = stay.toDays();
        Duration rest = stay.minusDays(fullDays);
        long seconds = rest.getSeconds() + (rest.getNano() > 0 ? 1 : 0);
        long startedHours = (seconds + 3599) / 3600;                  // every started hour counts
        long restFee = Math.min(dailyCap.get(type), startedHours * hourlyRate.get(type));
        return fullDays * dailyCap.get(type) + restFee;
    }

    @Override
    public long lostTicketFee(VehicleType type) {
        return dailyCap.get(type);
    }
}
