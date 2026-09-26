# 🏢 Management Systems — Low Level Design

Low Level Design problems that model a **real business operation**: resources that are allocated and
released (spots, rooms, books, seats), money that is charged, and many users acting at the same time.
Frequently asked in product-based company interviews to test **entity modelling**, **Strategy-based
policies** (allocation, pricing, payment) and **concurrency** around shared resources.
Every project follows the same flow: **Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [Parking Lot](ParkingLot/README.md) | Facade, Strategy, Observer | Best-fit vs nearest allocation, EV-only bays, hourly pricing with grace + daily cap, lost ticket, declined payment keeps the car inside, lock-safe multi-gate entry | ✅ 34 tests |
| 2 | [Task Management System](TaskManagementSystem/README.md) | Facade, Builder, Specification, Observer, Strategy | Table-driven workflow, reporter/assignee/manager permissions, subtasks with parent-before-child locking, optimistic locking on edits, composable search, once-a-day reminders, @mentions | ✅ 27 tests |
