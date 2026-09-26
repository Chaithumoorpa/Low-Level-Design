# 💰 Finance and Payment — Low Level Design

Low Level Design problems where **money must be exactly right**: integer minor units, rounding rules,
idempotent operations, audit trails (ledgers), strict state machines and concurrency that never
double-spends. Frequently asked in product-based company (and fintech) interviews.
Every project follows the same flow: **Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [Splitwise](Splitwise/README.md) | Strategy, Facade, Greedy | Equal / exact / percent / share splits exact to the cent (largest remainder), balances derived from events, pairwise netting with cycle cancellation, greedy debt simplification (≤ n−1 payments) | ✅ 22 tests |
