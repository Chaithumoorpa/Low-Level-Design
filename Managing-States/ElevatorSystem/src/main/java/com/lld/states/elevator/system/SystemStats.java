package com.lld.states.elevator.system;

/** Service quality over a run: how long people waited and rode, and how far the cars moved. */
public record SystemStats(int delivered, double averageWait, long maxWait, double averageRide, long totalFloorsTravelled) {

    @Override
    public String toString() {
        return String.format("delivered %d, avg wait %.1f ticks (max %d), avg ride %.1f ticks, car travel %d floors",
                delivered, averageWait, maxWait, averageRide, totalFloorsTravelled);
    }
}
