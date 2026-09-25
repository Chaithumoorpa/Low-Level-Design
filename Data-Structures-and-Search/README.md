# 🧱 Data Structures and Search — Low Level Design

Low Level Design problems centred on building a data structure or a search component, frequently asked in
product-based company interviews. Every project follows the same flow:
**Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [LRU Cache](LRUCache/README.md) | Strategy, Decorator, Observer | O(1) HashMap + doubly linked list, O(1) LFU, TTL, thread safety | ✅ 24 tests |
| 2 | [LFU Cache](LFUCache/README.md) | Composite structure, Builder, Observer | Strict O(1) frequency-list design vs O(log n) TreeSet, frequency aging, brute-force oracle tests | ✅ 19 tests |
