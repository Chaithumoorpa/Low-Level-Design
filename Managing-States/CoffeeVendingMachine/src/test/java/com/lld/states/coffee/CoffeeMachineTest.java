package com.lld.states.coffee;

import com.lld.states.coffee.beverage.AddOn;
import com.lld.states.coffee.beverage.Beverage;
import com.lld.states.coffee.beverage.Drink;
import com.lld.states.coffee.brewing.BrewerHardware;
import com.lld.states.coffee.ingredient.Ingredient;
import com.lld.states.coffee.ingredient.IngredientInventory;
import com.lld.states.coffee.machine.CoffeeMachine;
import com.lld.states.coffee.machine.CoffeeMachineException;
import com.lld.states.coffee.machine.CoffeeMachineListener;
import com.lld.states.coffee.machine.Cup;
import com.lld.states.coffee.machine.MachineStatus;
import com.lld.states.coffee.payment.CardPayment;
import com.lld.states.coffee.payment.CashPayment;
import com.lld.states.coffee.payment.InMemoryPaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CoffeeMachineTest {

    private IngredientInventory inventory;
    private AtomicBoolean grinderBlocked;
    private CoffeeMachine machine;
    private InMemoryPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        inventory = new IngredientInventory(Map.of(
                Ingredient.WATER, 5000, Ingredient.MILK, 2000, Ingredient.COFFEE_BEANS, 1000,
                Ingredient.CHOCOLATE, 500, Ingredient.SUGAR, 200));
        inventory.fillAll();
        grinderBlocked = new AtomicBoolean(false);
        BrewerHardware hardware = step -> {
            if (grinderBlocked.get() && step.startsWith("Grinding")) {
                throw new IllegalStateException("grinder blocked");
            }
        };
        machine = new CoffeeMachine(inventory, hardware, 5);
        gateway = new InMemoryPaymentGateway();
        gateway.addCard("alice", 1000);
    }

    private static CoffeeMachineException declined(Runnable action) {
        CoffeeMachineException e = assertThrows(CoffeeMachineException.class, action::run);
        assertFalse(e.isInvalidState(), "expected a decline, got: " + e.getMessage());
        return e;
    }

    private static void notAllowed(Runnable action) {
        CoffeeMachineException e = assertThrows(CoffeeMachineException.class, action::run);
        assertTrue(e.isInvalidState(), "expected wrong-state error, got: " + e.getMessage());
    }

    private Cup buy(Drink drink) {
        machine.selectDrink(drink);
        return machine.pay(new CashPayment(1000));
    }

    // ------------------------------------------------------------------ decorator

    @Test
    void addOnsStackPriceRecipeAndDescription() {
        Beverage drink = new AddOn(new AddOn(new AddOn(Drink.LATTE, AddOn.Kind.EXTRA_SHOT),
                AddOn.Kind.SUGAR), AddOn.Kind.SUGAR);

        assertEquals("Latte + extra shot + sugar + sugar", drink.description());
        assertEquals(250 + 60, drink.price());
        assertEquals(36, drink.recipe().get(Ingredient.COFFEE_BEANS));
        assertEquals(10, drink.recipe().get(Ingredient.SUGAR));
        assertEquals(180, drink.recipe().get(Ingredient.MILK));
        assertEquals(2, AddOn.count(drink, AddOn.Kind.SUGAR));
        assertEquals(Map.of(Ingredient.WATER, 30, Ingredient.COFFEE_BEANS, 18), Drink.ESPRESSO.recipe());
    }

    @Test
    void extraShotTurnsHotChocolateIntoACoffeeDrink() {
        assertFalse(Drink.HOT_CHOCOLATE.containsCoffee());
        assertTrue(new AddOn(Drink.HOT_CHOCOLATE, AddOn.Kind.EXTRA_SHOT).containsCoffee());
    }

    // ------------------------------------------------------------------ state machine

    @Test
    void orderGoesThroughTheExpectedStates() {
        List<String> transitions = new ArrayList<>();
        machine.addListener(new CoffeeMachineListener() {
            @Override
            public void onStateChange(MachineStatus from, MachineStatus to) {
                transitions.add(from + "->" + to);
            }
        });

        buy(Drink.ESPRESSO);

        assertEquals(List.of("IDLE->SELECTING", "SELECTING->BREWING", "BREWING->IDLE"), transitions);
    }

    @Test
    void eachStateRejectsForeignActions() {
        notAllowed(() -> machine.addExtra(AddOn.Kind.SUGAR));            // IDLE: no drink yet
        notAllowed(() -> machine.pay(new CashPayment(100)));
        notAllowed(machine::cancel);
        notAllowed(() -> machine.refill(Ingredient.MILK, 10));

        machine.selectDrink(Drink.LATTE);                                // SELECTING
        notAllowed(() -> machine.selectDrink(Drink.ESPRESSO));
        notAllowed(machine::startMaintenance);                           // not with a customer mid-order
        notAllowed(machine::runCleaning);

        machine.cancel();
        machine.startMaintenance();                                      // OUT_OF_SERVICE
        notAllowed(() -> machine.selectDrink(Drink.ESPRESSO));
    }

    @Test
    void brewingStateIgnoresButtons() {
        List<CoffeeMachineException> pressed = new ArrayList<>();
        CoffeeMachine[] holder = new CoffeeMachine[1];
        holder[0] = new CoffeeMachine(inventory, step -> {
            try {
                holder[0].selectDrink(Drink.ESPRESSO);
            } catch (CoffeeMachineException e) {
                pressed.add(e);
            }
        }, 5);

        holder[0].selectDrink(Drink.AMERICANO);
        holder[0].pay(new CashPayment(500));

        assertFalse(pressed.isEmpty());
        assertTrue(pressed.stream().allMatch(CoffeeMachineException::isInvalidState));
    }

    @Test
    void cancelDropsTheOrderWithoutChargingOrUsingIngredients() {
        Map<Ingredient, Integer> before = inventory.levels();
        machine.selectDrink(Drink.LATTE);
        machine.addExtra(AddOn.Kind.EXTRA_SHOT);
        machine.cancel();

        assertEquals(MachineStatus.IDLE, machine.status());
        assertNull(machine.currentOrder());
        assertEquals(before, inventory.levels());
    }

    // ------------------------------------------------------------------ ordering and paying

    @Test
    void cashPaymentGivesChangeAndConsumesTheWholeRecipe() {
        machine.selectDrink(Drink.LATTE);
        machine.addExtra(AddOn.Kind.EXTRA_SHOT);

        Cup cup = machine.pay(new CashPayment(400));

        assertEquals(310, cup.price());
        assertEquals(90, cup.payment().change());
        assertEquals(5000 - 60, inventory.level(Ingredient.WATER));
        assertEquals(1000 - 36, inventory.level(Ingredient.COFFEE_BEANS));
        assertEquals(2000 - 180, inventory.level(Ingredient.MILK));
        assertEquals(310, machine.revenue());
    }

    @Test
    void insufficientCashIsDeclinedAndTheOrderIsKept() {
        machine.selectDrink(Drink.LATTE);

        CoffeeMachineException e = declined(() -> machine.pay(new CashPayment(200)));

        assertTrue(e.getMessage().contains("50c more"));
        assertEquals(MachineStatus.SELECTING, machine.status());
        assertEquals(Drink.LATTE, machine.currentOrder());
        assertEquals(MachineStatus.IDLE, statusAfter(() -> machine.pay(new CashPayment(250))));
    }

    @Test
    void cardPaymentIsChargedThroughTheGateway() {
        machine.selectDrink(Drink.CAPPUCCINO);
        Cup cup = machine.pay(new CardPayment("alice", gateway));

        assertEquals(1000 - 240, gateway.balance("alice"));
        assertEquals("TX1", cup.payment().reference());
    }

    @Test
    void declinedCardKeepsTheOrderAndUsesNoIngredients() {
        gateway.addCard("poor", 50);
        Map<Ingredient, Integer> before = inventory.levels();
        machine.selectDrink(Drink.ESPRESSO);

        declined(() -> machine.pay(new CardPayment("poor", gateway)));

        assertEquals(MachineStatus.SELECTING, machine.status());
        assertEquals(before, inventory.levels());
    }

    @Test
    void extrasAreLimited() {
        machine.selectDrink(Drink.AMERICANO);
        for (int i = 0; i < CoffeeMachine.MAX_EXTRAS_PER_KIND; i++) {
            machine.addExtra(AddOn.Kind.SUGAR);
        }

        declined(() -> machine.addExtra(AddOn.Kind.SUGAR));
        machine.addExtra(AddOn.Kind.EXTRA_MILK);                          // other kinds still fine
    }

    // ------------------------------------------------------------------ ingredients

    @Test
    void unavailableDrinksAreDeclinedAndShownOnTheMenu() {
        IngredientInventory noMilk = new IngredientInventory(Map.of(
                Ingredient.WATER, 1000, Ingredient.COFFEE_BEANS, 100, Ingredient.MILK, 100));
        noMilk.refill(Ingredient.WATER, 1000);
        noMilk.refill(Ingredient.COFFEE_BEANS, 100);
        CoffeeMachine m = new CoffeeMachine(noMilk, BrewerHardware.ALWAYS_WORKS, 5);

        assertFalse(m.menu().get(Drink.LATTE));
        assertTrue(m.menu().get(Drink.ESPRESSO));
        CoffeeMachineException e = declined(() -> m.selectDrink(Drink.LATTE));
        assertTrue(e.getMessage().contains("MILK"));
        assertEquals(MachineStatus.IDLE, m.status());
    }

    @Test
    void extraThatWouldRunOutIsRefused() {
        inventory.consume(Map.of(Ingredient.MILK, 2000 - 200));            // leave 200 ml
        machine.selectDrink(Drink.LATTE);                                  // needs 180

        declined(() -> machine.addExtra(AddOn.Kind.EXTRA_MILK));           // 240 > 200
        assertEquals(Drink.LATTE, machine.currentOrder());
    }

    @Test
    void inventoryConsumeIsAllOrNothing() {
        IngredientInventory inv = new IngredientInventory(Map.of(Ingredient.WATER, 100, Ingredient.MILK, 100));
        inv.refill(Ingredient.WATER, 100);
        inv.refill(Ingredient.MILK, 10);

        assertThrows(IllegalStateException.class,
                () -> inv.consume(Map.of(Ingredient.WATER, 50, Ingredient.MILK, 50)));
        assertEquals(100, inv.level(Ingredient.WATER), "water must not be taken when milk is short");
        assertEquals(Map.of(Ingredient.MILK, 40), inv.missing(Map.of(Ingredient.WATER, 50, Ingredient.MILK, 50)));
    }

    @Test
    void lowLevelAlertFiresOnceUntilRefilled() {
        List<Ingredient> alerts = new ArrayList<>();
        machine.addListener(new CoffeeMachineListener() {
            @Override
            public void onLowIngredient(Ingredient ingredient, int levelLeft) {
                alerts.add(ingredient);
            }
        });
        inventory.consume(Map.of(Ingredient.MILK, 1500));                  // 500 left, threshold 400

        buy(Drink.LATTE);                                                  // 320 left → alert
        buy(Drink.LATTE);                                                  // 140 left → no second alert
        assertEquals(List.of(Ingredient.MILK), alerts);

        inventory.refill(Ingredient.MILK, 2000);
        inventory.consume(Map.of(Ingredient.MILK, 1700));                  // below threshold again
        assertEquals(List.of(Ingredient.MILK, Ingredient.MILK), alerts);
    }

    @Test
    void runningOutOfEverythingTakesTheMachineOffline() {
        IngredientInventory tiny = new IngredientInventory(Map.of(Ingredient.WATER, 30, Ingredient.COFFEE_BEANS, 18));
        tiny.fillAll();
        CoffeeMachine m = new CoffeeMachine(tiny, BrewerHardware.ALWAYS_WORKS, 5);
        m.selectDrink(Drink.ESPRESSO);
        m.pay(new CashPayment(150));

        assertEquals(MachineStatus.OUT_OF_SERVICE, m.status());
        declined(m::finishMaintenance);                                    // nothing can be made yet
        m.refill(Ingredient.WATER, 30);
        m.refill(Ingredient.COFFEE_BEANS, 18);
        m.finishMaintenance();
        assertEquals(MachineStatus.IDLE, m.status());
    }

    // ------------------------------------------------------------------ brewing template, faults, cleaning

    @Test
    void brewingStepsFollowTheTemplate() {
        List<String> steps = new ArrayList<>();
        machine.addListener(new CoffeeMachineListener() {
            @Override
            public void onBrewStep(String step) {
                steps.add(step);
            }
        });

        machine.selectDrink(Drink.CAPPUCCINO);
        machine.addExtra(AddOn.Kind.SUGAR);
        machine.pay(new CashPayment(300));
        List<String> coffee = new ArrayList<>(steps);
        steps.clear();
        buy(Drink.HOT_CHOCOLATE);

        assertEquals(List.of("Heating water", "Grinding 18 g beans", "Extracting 1 shot(s)",
                "Steaming 120 ml milk", "Adding 5 g sugar", "Pouring Cappuccino + sugar"), coffee);
        assertEquals(List.of("Heating water", "Mixing chocolate powder", "Steaming 150 ml milk",
                "Pouring Hot Chocolate"), steps);
    }

    @Test
    void hardwareFaultRefundsTheCardAndTakesTheMachineOffline() {
        grinderBlocked.set(true);
        machine.selectDrink(Drink.ESPRESSO);

        CoffeeMachineException e = declined(() -> machine.pay(new CardPayment("alice", gateway)));

        assertTrue(e.getMessage().contains("refunded"));
        assertEquals(1000, gateway.balance("alice"));
        assertEquals(MachineStatus.OUT_OF_SERVICE, machine.status());
        assertEquals(0, machine.cupsServed());

        grinderBlocked.set(false);
        machine.finishMaintenance();
        assertEquals(MachineStatus.IDLE, machine.status());
    }

    @Test
    void cashIsReturnedOnFault() {
        grinderBlocked.set(true);
        CashPayment coins = new CashPayment(500);
        machine.selectDrink(Drink.AMERICANO);

        declined(() -> machine.pay(coins));

        assertTrue(coins.wasRefunded());
    }

    @Test
    void cleaningIsRequiredEveryNCups() {
        for (int i = 0; i < 4; i++) {
            buy(Drink.ESPRESSO);
        }
        assertEquals(1, machine.cupsUntilCleaning());
        buy(Drink.ESPRESSO);

        assertEquals(MachineStatus.NEEDS_CLEANING, machine.status());
        notAllowed(() -> machine.selectDrink(Drink.ESPRESSO));
        int water = inventory.level(Ingredient.WATER);
        machine.runCleaning();
        assertEquals(MachineStatus.IDLE, machine.status());
        assertEquals(water - 200, inventory.level(Ingredient.WATER));
        assertEquals(5, machine.cupsUntilCleaning());
    }

    @Test
    void invalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new CoffeeMachine(inventory, BrewerHardware.ALWAYS_WORKS, 0));
        assertThrows(IllegalArgumentException.class, () -> new CashPayment(0));
        IngredientInventory empty = new IngredientInventory(Map.of(Ingredient.WATER, 100));
        assertEquals(MachineStatus.OUT_OF_SERVICE,
                new CoffeeMachine(empty, BrewerHardware.ALWAYS_WORKS, 5).status());
    }

    private MachineStatus statusAfter(Runnable action) {
        action.run();
        return machine.status();
    }
}
