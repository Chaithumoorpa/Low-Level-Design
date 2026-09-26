package com.lld.states.coffee.machine;

import com.lld.states.coffee.beverage.AddOn;
import com.lld.states.coffee.beverage.Drink;
import com.lld.states.coffee.ingredient.Ingredient;
import com.lld.states.coffee.payment.PaymentMethod;

/**
 * The five states. Each is small, so they live together here; each overrides only the actions its
 * screen offers. They hold no data (the order, inventory and counters live in {@link CoffeeMachine}).
 */
final class States {

    private States() {
    }

    /** "Choose a drink". */
    static final MachineState IDLE = new MachineState() {
        public MachineStatus status() {
            return MachineStatus.IDLE;
        }

        public void selectDrink(CoffeeMachine m, Drink drink) {
            m.startOrder(drink);
            m.transitionTo(SELECTING);
        }

        public void startMaintenance(CoffeeMachine m) {
            m.transitionTo(OUT_OF_SERVICE);
        }
    };

    /** A drink is chosen: add extras, pay, or cancel. */
    static final MachineState SELECTING = new MachineState() {
        public MachineStatus status() {
            return MachineStatus.SELECTING;
        }

        public void addExtra(CoffeeMachine m, AddOn.Kind kind) {
            m.addToOrder(kind);
        }

        public void cancel(CoffeeMachine m) {
            m.clearOrder();
            m.transitionTo(IDLE);
        }

        public Cup pay(CoffeeMachine m, PaymentMethod payment) {
            return m.payAndBrew(payment);
        }
    };

    /** The machine is working; every button is ignored. */
    static final MachineState BREWING = () -> MachineStatus.BREWING;

    /** Hygiene: after N cups the machine refuses drinks until a cleaning cycle runs. */
    static final MachineState NEEDS_CLEANING = new MachineState() {
        public MachineStatus status() {
            return MachineStatus.NEEDS_CLEANING;
        }

        public void runCleaning(CoffeeMachine m) {
            m.clean();
            m.transitionTo(m.canMakeAnything() ? IDLE : OUT_OF_SERVICE);
        }

        public void startMaintenance(CoffeeMachine m) {
            m.transitionTo(OUT_OF_SERVICE);
        }
    };

    /** Staff only: refill, clean, bring back. */
    static final MachineState OUT_OF_SERVICE = new MachineState() {
        public MachineStatus status() {
            return MachineStatus.OUT_OF_SERVICE;
        }

        public void startMaintenance(CoffeeMachine m) {
            // already open
        }

        public void refill(CoffeeMachine m, Ingredient ingredient, int amount) {
            m.inventory().refill(ingredient, amount);
        }

        public void runCleaning(CoffeeMachine m) {
            m.clean();
        }

        public void finishMaintenance(CoffeeMachine m) {
            if (!m.canMakeAnything()) {
                throw CoffeeMachineException.declined("Refill ingredients: no drink on the menu can be made");
            }
            m.clean();                                   // every maintenance visit ends with a clean machine
            m.clearFault();
            m.transitionTo(IDLE);
        }
    };
}
