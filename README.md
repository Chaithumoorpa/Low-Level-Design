# 🏗️ Low Level Design — Interview Practice

Plain Java 17 solutions to classic Low Level Design (LLD / object-oriented design) interview problems.
Each problem is a self-contained Maven project with runnable code, JUnit tests and a README that walks
through the design the way you would present it in an interview:

**Scoping → Building Blocks → Object Model (classes, patterns, UML) → Implementation → Build, Run & Verify → Follow-ups**

## 📂 Categories

| Category | Problems |
|---|---|
| [🎮 Games and Puzzles](Games-and-Puzzles/README.md) | Snake and Ladder, Minesweeper, Chess, Tic Tac Toe, Sudoku |
| [🧱 Data Structures and Search](Data-Structures-and-Search/README.md) | LRU Cache, LFU Cache, Search Autocomplete, Rate Limiter, Bloom Filter |
| [🔄 Managing States](Managing-States/README.md) | ATM, Vending Machine, Coffee Vending Machine, Elevator System, Traffic Control System |
| [🏢 Management Systems](Management-Systems/README.md) | Parking Lot, Task Management System, Inventory Management System, Library Management System, Restaurant Management System |
| [💬 Communications and Messaging](Communications-and-Messaging/README.md) | Notification System, Pub-Sub System, Chat Application |
| [💰 Finance and Payment](Finance-and-Payment/README.md) | Splitwise, Payment Gateway, Online Stock Exchange |
| [🌐 Social and Content Platform](Social-and-Content-Platform/README.md) | Stack Overflow, Social Network, Learning Platform |
| [🎟️ Booking and Reservation](Booking-and-Reservation/README.md) | Movie Booking System, Food Delivery Service, Ride-Sharing (Uber) |

## ▶️ Running any project

```bash
cd <Category>/<Problem>
mvn test                 # run the tests
mvn compile exec:java    # run the console demo
```

Requires JDK 17+ (Maven optional: every README also shows plain `javac` commands).

> Personal learning project. All code and write-ups are original work based on public rules and
> widely known computer-science material; no premium course content is reproduced.
