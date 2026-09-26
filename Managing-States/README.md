# 🔄 Managing States — Low Level Design

Low Level Design problems where the heart of the solution is a **state machine**: the same action means
something different (or is forbidden) depending on the current state. Frequently asked in product-based
company interviews to test the **State pattern**, transition rules and failure handling.
Every project follows the same flow: **Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [ATM](ATM/README.md) | State, Chain of Responsibility, Strategy, Facade, Observer | 4 states with safe defaults, PIN blocking + card retention, greedy vs optimal note dispensing, refund on jam | ✅ 27 tests |
