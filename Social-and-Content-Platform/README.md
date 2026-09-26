# 🌐 Social and Content Platform — Low Level Design

Low Level Design problems about **people creating, sharing and learning from content**: posts, votes,
feeds, relationships, courses and progress. Frequently asked in product-based company interviews to
test **entity modelling**, **graph and feed algorithms**, **permissions / privacy rules** and
**reversible scoring**.
Every project follows the same flow: **Scoping → Building Blocks → Object Model → Implementation → Verify → Follow-ups**.

| # | Problem | Key Patterns | Highlights | Status |
|---|---|---|---|---|
| 1 | [Stack Overflow](StackOverflow/README.md) | Post hierarchy, Strategy, Facade, Event log | Reputation as a reversible sum of events, privileges by reputation, vote undo/switch, accepted answers, 3-vote closing, bounties, revisions, word + tag search | ✅ 16 tests |
| 2 | [Social Network](SocialNetwork/README.md) | Facade, Strategy, Observer, Graph BFS | Friend requests (crossed auto-accept), follows, blocking that cuts all ties, one privacy rule for every read, ranked and paged feed (chronological / decayed engagement), mutual friends, suggestions, degrees of separation | ✅ 14 tests |
