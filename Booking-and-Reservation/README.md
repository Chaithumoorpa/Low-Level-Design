# 🎟️ Booking and Reservation — Low Level Design

Low Level Design problems where **limited, time-bound resources are matched to people**: seats for a
show, delivery partners for orders, drivers for riders. Frequently asked in product-based company
interviews to test **reservation flows (hold → pay → confirm)**, **matching and dispatch strategies**,
**state machines** and **concurrency on scarce resources**.
Every project follows the same flow: **Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [Movie Booking System](MovieBooking/README.md) | Facade, Strategy, Ports & Adapters | Two-phase booking (hold → pay → confirm) under a per-show lock, payment outside the lock with a PAYING state, idempotent confirm, hold expiry, lone-seat rule, peak pricing, time-based refunds, overlap-free scheduling | ✅ 22 tests |
