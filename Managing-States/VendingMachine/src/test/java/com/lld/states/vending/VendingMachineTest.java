package com.lld.states.vending;

import com.lld.states.vending.machine.ProductDispenser;
import com.lld.states.vending.machine.VendingListener;
import com.lld.states.vending.machine.VendingMachine;
import com.lld.states.vending.machine.VendingStatus;
import com.lld.states.vending.model.Money;
import com.lld.states.vending.model.VendResult;
import com.lld.states.vending.model.VendingException;
import com.lld.states.vending.money.ChangeMaker;
import com.lld.states.vending.money.CoinBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class VendingMachineTest {

    private static VendingMachine.Builder standard() {
        return VendingMachine.builder()
                .acceptCoins(5, 10, 25, 100)
                .slot("A1", "Cola", 125, 5, 3)
                .slot("A2", "Water", 90, 5, 1)
                .slot("B1", "Chips", 150, 5, 2)
                .changeFloat(Map.of(25, 4, 10, 5, 5, 5));
    }

    private static void insert(VendingMachine vm, int... coins) {
        for (int c : coins) {
            vm.insertCoin(c);
        }
    }

    private static VendingException declined(Runnable action) {
        VendingException e = assertThrows(VendingException.class, action::run);
        assertFalse(e.isInvalidState(), "expected a decline, got: " + e.getMessage());
        return e;
    }

    private static void notAllowed(Runnable action) {
        VendingException e = assertThrows(VendingException.class, action::run);
        assertTrue(e.isInvalidState(), "expected wrong-state error, got: " + e.getMessage());
    }

    // ------------------------------------------------------------------ states

    @Test
    void purchaseGoesThroughTheExpectedStates() {
        VendingMachine vm = standard().build();
        List<String> transitions = new ArrayList<>();
        vm.addListener(new VendingListener() {
            @Override
            public void onStateChange(VendingStatus from, VendingStatus to) {
                transitions.add(from + "->" + to);
            }
        });

        insert(vm, 100, 25);
        vm.selectProduct("A1");

        assertEquals(List.of("IDLE->HAS_MONEY", "HAS_MONEY->DISPENSING", "DISPENSING->IDLE"), transitions);
    }

    @Test
    void eachStateRejectsForeignActions() {
        VendingMachine vm = standard().build();
        notAllowed(vm::cancel);                              // IDLE: nothing to cancel
        notAllowed(() -> vm.restock("A1", 1));              // door is closed
        notAllowed(vm::finishMaintenance);

        vm.insertCoin(25);                                   // HAS_MONEY
        notAllowed(vm::startMaintenance);                    // not while a customer has money in
        notAllowed(vm::collectCash);

        vm.cancel();
        vm.startMaintenance();                               // OUT_OF_SERVICE
        notAllowed(() -> vm.insertCoin(25));
        notAllowed(() -> vm.selectProduct("A1"));
    }

    @Test
    void dispensingStateBlocksEverythingWhileTheMotorRuns() {
        List<VendingException> duringDispense = new ArrayList<>();
        VendingMachine[] holder = new VendingMachine[1];
        ProductDispenser motor = code -> {
            try {
                holder[0].insertCoin(25);                    // someone presses buttons mid-dispense
            } catch (VendingException e) {
                duringDispense.add(e);
            }
            assertEquals(VendingStatus.DISPENSING, holder[0].status());
        };
        holder[0] = standard().dispenser(motor).build();

        insert(holder[0], 100, 25);
        holder[0].selectProduct("A1");

        assertEquals(1, duringDispense.size());
        assertTrue(duringDispense.get(0).isInvalidState());
        assertEquals(0, holder[0].balance());
    }

    // ------------------------------------------------------------------ buying

    @Test
    void selectingInIdleShowsThePrice() {
        VendingMachine vm = standard().build();

        VendingException e = declined(() -> vm.selectProduct("A1"));
        assertTrue(e.getMessage().contains("$1.25"));
    }

    @Test
    void insufficientBalanceKeepsTheMoneyAndAsksForMore() {
        VendingMachine vm = standard().build();
        insert(vm, 100);

        VendingException e = declined(() -> vm.selectProduct("A1"));

        assertTrue(e.getMessage().contains("$0.25 more"));
        assertEquals(100, vm.balance());
        assertEquals(VendingStatus.HAS_MONEY, vm.status());
        vm.insertCoin(25);
        assertEquals("Cola", vm.selectProduct("A1").product().name());
    }

    @Test
    void changeIsGivenWithTheFewestCoins() {
        VendingMachine vm = standard().build();
        insert(vm, 100, 100);

        VendResult result = vm.selectProduct("A2");               // 2.00 - 0.90 = 1.10

        assertEquals(110, result.changeAmount());
        assertEquals(Map.of(100, 1, 10, 1), result.change());     // the customer's own $1 can be change
        assertEquals(0, vm.balance());
    }

    @Test
    void cancelReturnsTheExactCoinsInserted() {
        VendingMachine vm = standard().build();
        insert(vm, 25, 25, 10, 5);

        Map<Integer, Integer> back = vm.cancel();

        assertEquals(Map.of(25, 2, 10, 1, 5, 1), back);
        assertEquals(VendingStatus.IDLE, vm.status());
        assertEquals(standard().build().coinsInMachine(), vm.coinsInMachine());
    }

    @Test
    void unacceptedCoinGoesToTheCoinReturn() {
        VendingMachine vm = standard().build();

        declined(() -> vm.insertCoin(1));

        assertEquals(VendingStatus.IDLE, vm.status());
        assertEquals(Map.of(1, 1), vm.takeCoinReturn());
        assertTrue(vm.takeCoinReturn().isEmpty());
    }

    @Test
    void soldOutProductIsRefusedButOthersWork() {
        VendingMachine vm = standard().build();
        insert(vm, 100);
        vm.selectProduct("A2");                                  // last water

        insert(vm, 100);
        declined(() -> vm.selectProduct("A2"));
        assertEquals(100, vm.balance());
        insert(vm, 25);
        assertEquals("Cola", vm.selectProduct("A1").product().name());
    }

    @Test
    void unknownSlotIsDeclined() {
        VendingMachine vm = standard().build();
        insert(vm, 100);

        declined(() -> vm.selectProduct("Z9"));
        assertEquals(100, vm.balance());
    }

    // ------------------------------------------------------------------ change safety

    @Test
    void exactChangeOnlyWhenTheFloatCannotPay() {
        VendingMachine vm = VendingMachine.builder()
                .acceptCoins(5, 10, 25, 100)
                .slot("A1", "Cola", 125, 5, 5)
                .build();                                        // no float at all
        insert(vm, 100, 100);

        VendingException e = declined(() -> vm.selectProduct("A1"));   // 75c change impossible

        assertTrue(e.getMessage().contains("exact change"));
        assertEquals(200, vm.balance());
        assertEquals(5, vm.slots().get(0).quantity(), "nothing was dispensed");
        assertEquals(Map.of(100, 2), vm.cancel());
    }

    @Test
    void customersOwnCoinsCanFormTheChange() {
        VendingMachine vm = VendingMachine.builder()
                .acceptCoins(5, 10, 25, 100)
                .slot("A1", "Gum", 50, 5, 5)
                .build();                                        // empty float
        insert(vm, 25, 25, 25);                                  // 75c for a 50c item: change 25c

        VendResult result = vm.selectProduct("A1");

        assertEquals(Map.of(25, 1), result.change());            // one of their own quarters comes back
    }

    @Test
    void changeMakerBeatsGreedyWithALimitedFloat() {
        CoinBox box = new CoinBox(25, 10, 5);
        box.add(Map.of(25, 1, 10, 3));

        // Greedy would take 25c and then need 5c (none): fail. The right answer is 3 x 10c.
        assertEquals(Optional.of(Map.of(10, 3)), ChangeMaker.makeChange(30, box));
        assertEquals(Optional.empty(), ChangeMaker.makeChange(15, box));
        assertEquals(Optional.of(Map.of()), ChangeMaker.makeChange(0, box));
    }

    @Test
    void changeMakerMatchesBruteForce() {
        Random random = new Random(21);
        int[] d = {100, 25, 10, 5};
        for (int trial = 0; trial < 300; trial++) {
            CoinBox box = new CoinBox(d);
            int[] c = new int[4];
            for (int i = 0; i < 4; i++) {
                c[i] = random.nextInt(4);
                box.add(Map.of(d[i], c[i]));
            }
            int amount = 5 * random.nextInt(60);

            int best = Integer.MAX_VALUE;
            for (int a = 0; a <= c[0]; a++) {
                for (int b = 0; b <= c[1]; b++) {
                    for (int e = 0; e <= c[2]; e++) {
                        for (int f = 0; f <= c[3]; f++) {
                            if (100 * a + 25 * b + 10 * e + 5 * f == amount) {
                                best = Math.min(best, a + b + e + f);
                            }
                        }
                    }
                }
            }
            Optional<Map<Integer, Integer>> plan = ChangeMaker.makeChange(amount, box);
            if (best == Integer.MAX_VALUE) {
                assertTrue(plan.isEmpty(), "trial " + trial);
            } else {
                assertEquals(amount, Money.total(plan.orElseThrow()), "trial " + trial);
                assertEquals(best, plan.get().values().stream().mapToInt(Integer::intValue).sum(), "trial " + trial);
            }
        }
    }

    // ------------------------------------------------------------------ failures and maintenance

    @Test
    void jamRefundsToCoinReturnAndTakesMachineOffline() {
        AtomicBoolean jam = new AtomicBoolean(true);
        VendingMachine vm = standard().dispenser(code -> {
            if (jam.get()) {
                throw new IllegalStateException("spiral stuck");
            }
        }).build();
        long coinsBefore = vm.coinsInMachine();
        insert(vm, 100, 25);

        VendingException e = declined(() -> vm.selectProduct("A1"));

        assertTrue(e.getMessage().contains("$1.25"));
        assertEquals(Map.of(25, 1, 100, 1), vm.takeCoinReturn());
        assertEquals(VendingStatus.OUT_OF_SERVICE, vm.status());
        assertEquals(coinsBefore, vm.coinsInMachine());
        assertEquals(3, vm.slots().get(0).quantity());
        assertEquals(0, vm.revenue());

        jam.set(false);
        vm.finishMaintenance();
        assertEquals(VendingStatus.IDLE, vm.status());
    }

    @Test
    void sellingTheLastItemTakesTheMachineOffline() {
        VendingMachine vm = VendingMachine.builder()
                .slot("A1", "Cola", 100, 5, 1)
                .build();
        List<String> soldOut = new ArrayList<>();
        vm.addListener(new VendingListener() {
            @Override
            public void onSoldOut(String slotCode) {
                soldOut.add(slotCode);
            }
        });
        insert(vm, 100);
        vm.selectProduct("A1");

        assertEquals(List.of("A1"), soldOut);
        assertEquals(VendingStatus.OUT_OF_SERVICE, vm.status());
        declined(vm::finishMaintenance);                         // nothing to sell yet
        vm.restock("A1", 3);
        vm.finishMaintenance();
        assertEquals(VendingStatus.IDLE, vm.status());
    }

    @Test
    void maintenanceRestocksReprisesAndCollectsCash() {
        VendingMachine vm = standard().build();
        insert(vm, 100, 25);
        vm.selectProduct("A1");

        vm.startMaintenance();
        vm.restock("A1", 100);                                   // only fills up to capacity
        vm.setPrice("A1", 150);
        declined(() -> vm.setPrice("A1", 0));
        Map<Integer, Integer> collected = vm.collectCash();
        vm.loadCoins(Map.of(25, 4));
        vm.finishMaintenance();

        assertEquals(5, vm.slots().get(0).quantity());
        assertEquals(150, vm.slots().get(0).product().price());
        assertEquals(100 + 50 + 25 + 125, Money.total(collected));    // float + the sale
        assertEquals(100, vm.coinsInMachine());
        assertEquals(125, vm.revenue());
        assertEquals(Map.of("A1", 1), vm.unitsSold());
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalStateException.class, () -> VendingMachine.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> VendingMachine.builder().slot("A1", "x", 1, 1, 0).slot("A1", "y", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> VendingMachine.builder().slot("1A", "x", 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> VendingMachine.builder().slot("A1", "x", 0, 1, 0));
        VendingMachine empty = VendingMachine.builder().slot("A1", "x", 100, 1, 0).build();
        assertEquals(VendingStatus.OUT_OF_SERVICE, empty.status());
    }
}
