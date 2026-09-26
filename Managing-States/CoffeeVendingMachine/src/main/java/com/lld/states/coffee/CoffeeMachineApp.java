package com.lld.states.coffee;

import com.lld.states.coffee.beverage.AddOn;
import com.lld.states.coffee.beverage.Drink;
import com.lld.states.coffee.brewing.BrewerHardware;
import com.lld.states.coffee.ingredient.Ingredient;
import com.lld.states.coffee.ingredient.IngredientInventory;
import com.lld.states.coffee.machine.CoffeeMachine;
import com.lld.states.coffee.machine.CoffeeMachineException;
import com.lld.states.coffee.machine.CoffeeMachineListener;
import com.lld.states.coffee.machine.MachineStatus;
import com.lld.states.coffee.payment.CardPayment;
import com.lld.states.coffee.payment.CashPayment;
import com.lld.states.coffee.payment.InMemoryPaymentGateway;

import java.util.Map;

/** Scripted walk-through: customising, paying by cash and card, cleaning cycle, low milk. */
public class CoffeeMachineApp {

    public static void main(String[] args) {
        IngredientInventory inventory = new IngredientInventory(Map.of(
                Ingredient.WATER, 2000, Ingredient.MILK, 500, Ingredient.COFFEE_BEANS, 300,
                Ingredient.CHOCOLATE, 200, Ingredient.SUGAR, 100));
        inventory.fillAll();
        CoffeeMachine machine = new CoffeeMachine(inventory, BrewerHardware.ALWAYS_WORKS, 3);
        InMemoryPaymentGateway gateway = new InMemoryPaymentGateway();
        gateway.addCard("card-alice", 1000);
        gateway.addCard("card-empty", 100);

        machine.addListener(new CoffeeMachineListener() {
            @Override
            public void onStateChange(MachineStatus from, MachineStatus to) {
                System.out.println("      [display] " + from + " -> " + to);
            }

            @Override
            public void onBrewStep(String step) {
                System.out.println("      ... " + step);
            }

            @Override
            public void onLowIngredient(Ingredient ingredient, int left) {
                System.out.println("      [alert] " + ingredient + " low: " + left + " " + ingredient.unit() + " left");
            }
        });

        System.out.println("Menu: " + machine.menu());

        step("Latte + extra shot + 2 sugars, paid with coins (400c)", () -> {
            machine.selectDrink(Drink.LATTE);
            machine.addExtra(AddOn.Kind.EXTRA_SHOT);
            machine.addExtra(AddOn.Kind.SUGAR);
            machine.addExtra(AddOn.Kind.SUGAR);
            System.out.println("      order: " + machine.currentOrder().description() + " = " + machine.currentOrder().price() + "c");
            System.out.println("      served: " + machine.pay(new CashPayment(400)));
        });
        step("Hot chocolate, card with too little money", () -> {
            machine.selectDrink(Drink.HOT_CHOCOLATE);
            machine.pay(new CardPayment("card-empty", gateway));
        });
        step("Same order, a different card", () ->
                System.out.println("      served: " + machine.pay(new CardPayment("card-alice", gateway))));
        step("Cappuccino + extra milk", () -> {
            machine.selectDrink(Drink.CAPPUCCINO);
            machine.addExtra(AddOn.Kind.EXTRA_MILK);
        });
        step("Customer keeps the plain cappuccino and pays", () ->
                System.out.println("      served: " + machine.pay(new CashPayment(300))));
        step("Try to order: cleaning is due after 3 cups", () -> machine.selectDrink(Drink.ESPRESSO));
        step("Run the cleaning cycle", machine::runCleaning);
        step("Try a latte now: is there enough milk?", () -> machine.selectDrink(Drink.LATTE));
        System.out.println("      menu now: " + machine.menu());
        step("Staff refill milk", () -> {
            machine.startMaintenance();
            machine.refill(Ingredient.MILK, 500);
            machine.finishMaintenance();
        });

        System.out.println();
        System.out.println("Served " + machine.cupsServed() + " cups, revenue " + machine.revenue() + "c");
        System.out.println("Levels: " + machine.inventory().levels());
    }

    private static void step(String title, Runnable action) {
        System.out.println();
        System.out.println("> " + title);
        try {
            action.run();
        } catch (CoffeeMachineException e) {
            System.out.println("      " + (e.isInvalidState() ? "[not allowed] " : "[declined] ") + e.getMessage());
        }
    }
}
