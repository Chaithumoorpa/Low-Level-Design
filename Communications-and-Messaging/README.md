# 💬 Communications and Messaging — Low Level Design

Low Level Design problems about **moving messages between parties**: fan-out to many channels or
subscribers, ordering, delivery guarantees (at-least-once, deduplication), retries, back-pressure and
presence. Frequently asked in product-based company interviews to test **asynchronous design**,
**Observer / Publish-Subscribe** thinking and **concurrency**.
Every project follows the same flow: **Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [Notification System](NotificationSystem/README.md) | Facade, Adapter, Builder, Observer, Strategy | One delivery per channel, templates rendered up front, preferences and quiet hours, priority dispatch with claim-under-lock, exponential backoff, permanent-error fallback, SMS rate limit, idempotency key | ✅ 27 tests |
