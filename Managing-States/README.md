# 🔄 Managing States — Low Level Design

Low Level Design problems where the heart of the solution is a **state machine**: the same action means
something different (or is forbidden) depending on the current state. Frequently asked in product-based
company interviews to test the **State pattern**, transition rules and failure handling.
Every project follows the same flow: **Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [ATM](ATM/README.md) | State, Chain of Responsibility, Strategy, Facade, Observer | 4 states with safe defaults, PIN blocking + card retention, greedy vs optimal note dispensing, refund on jam | ✅ 27 tests |
| 2 | [Vending Machine](VendingMachine/README.md) | State, Builder, Facade, Observer | Escrowed coins, change planned before dispensing (exact-change-only), coin return, jam refund | ✅ 18 tests |
| 3 | [Coffee Vending Machine](CoffeeVendingMachine/README.md) | State, Decorator, Template Method, Strategy, Observer | Stackable extras, all-or-nothing ingredients, brewing template, cash/card, cleaning cycle | ✅ 21 tests |
| 4 | [Elevator System](ElevatorSystem/README.md) | State, Strategy, Facade, Observer | LOOK scheduling, nearest-car vs round-robin dispatch, capacity, maintenance, fire recall | ✅ 16 tests |
| 5 | [Traffic Control System](TrafficControlSystem/README.md) | State, Strategy, Observer | Safe green-yellow-all-red cycle, conflict monitor fail-safe, actuated vs fixed timing, pedestrians, emergency preemption, night flash | ✅ 16 tests |
