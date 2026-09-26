package com.lld.states.atm;

import com.lld.states.atm.bank.InMemoryBank;
import com.lld.states.atm.cash.CashDispenser;
import com.lld.states.atm.cash.CashHardware;
import com.lld.states.atm.cash.CashInventory;
import com.lld.states.atm.cash.OptimalDispenseStrategy;
import com.lld.states.atm.machine.Atm;
import com.lld.states.atm.machine.AtmEventListener;
import com.lld.states.atm.machine.AtmStatus;
import com.lld.states.atm.model.AtmException;
import com.lld.states.atm.model.Card;
import com.lld.states.atm.model.TransactionRecord;
import com.lld.states.atm.model.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AtmTest {

    /** A clock the test can move to another day. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Card ALICE = new Card("4111111111111111");
    private static final Card BOB = new Card("5500000000000004");

    private TestClock clock;
    private InMemoryBank bank;
    private CashInventory cash;
    private AtomicBoolean jam;
    private Atm atm;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        bank = new InMemoryBank(clock);
        bank.openAccount(ALICE, "1234", 50_000, 25_000);
        bank.openAccount(BOB, "4321", 3_000, 10_000);
        cash = new CashInventory(2000, 500, 200, 100);
        cash.add(Map.of(2000, 10, 500, 10, 200, 10, 100, 10));          // 28,000
        jam = new AtomicBoolean(false);
        CashHardware hardware = notes -> {
            if (jam.get()) {
                throw new IllegalStateException("note jam");
            }
        };
        atm = new Atm("T-1", bank, new CashDispenser(cash, new OptimalDispenseStrategy(), hardware), 20_000, clock);
    }

    private void loginAlice() {
        atm.insertCard(ALICE);
        atm.enterPin("1234");
    }

    private static AtmException assertDeclined(Runnable action) {
        AtmException e = assertThrows(AtmException.class, action::run);
        assertFalse(e.isInvalidState(), "expected a declined transaction, got: " + e.getMessage());
        return e;
    }

    private static void assertNotAllowed(Runnable action) {
        AtmException e = assertThrows(AtmException.class, action::run);
        assertTrue(e.isInvalidState(), "expected a wrong-state error, got: " + e.getMessage());
    }

    // ------------------------------------------------------------------ state machine

    @Test
    void happyPathVisitsTheExpectedStates() {
        List<String> transitions = new ArrayList<>();
        atm.addListener(new AtmEventListener() {
            @Override
            public void onStateChange(AtmStatus from, AtmStatus to) {
                transitions.add(from + "->" + to);
            }
        });

        assertEquals(AtmStatus.IDLE, atm.status());
        loginAlice();
        atm.checkBalance();
        atm.ejectCard();

        assertEquals(List.of("IDLE->CARD_INSERTED", "CARD_INSERTED->AUTHENTICATED", "AUTHENTICATED->IDLE"),
                transitions);
    }

    @Test
    void eachStateRejectsActionsThatDoNotBelongToIt() {
        // IDLE
        assertNotAllowed(() -> atm.enterPin("1234"));
        assertNotAllowed(() -> atm.withdraw(100));
        assertNotAllowed(() -> atm.checkBalance());
        assertNotAllowed(() -> atm.ejectCard());
        assertNotAllowed(() -> atm.refill(Map.of(100, 1)));

        // CARD_INSERTED
        atm.insertCard(ALICE);
        assertNotAllowed(() -> atm.insertCard(BOB));
        assertNotAllowed(() -> atm.withdraw(100));
        assertNotAllowed(() -> atm.deposit(Map.of(100, 1)));
        assertNotAllowed(() -> atm.startMaintenance());

        // AUTHENTICATED
        atm.enterPin("1234");
        assertNotAllowed(() -> atm.insertCard(BOB));
        assertNotAllowed(() -> atm.enterPin("1234"));
        assertNotAllowed(() -> atm.startMaintenance());   // never offline in the middle of a session

        // OUT_OF_SERVICE
        atm.ejectCard();
        atm.startMaintenance();
        assertNotAllowed(() -> atm.insertCard(ALICE));
        assertNotAllowed(() -> atm.checkBalance());
    }

    @Test
    void customerCanCancelBeforeEnteringThePin() {
        atm.insertCard(ALICE);
        atm.ejectCard();

        assertEquals(AtmStatus.IDLE, atm.status());
    }

    @Test
    void sessionAllowsSeveralTransactions() {
        loginAlice();
        atm.withdraw(1000);
        atm.deposit(Map.of(500, 2));
        atm.checkBalance();

        assertEquals(AtmStatus.AUTHENTICATED, atm.status());
        assertEquals(3, atm.journal().size());
    }

    // ------------------------------------------------------------------ PIN handling

    @Test
    void wrongPinReportsAttemptsLeftAndCorrectPinStillWorks() {
        atm.insertCard(ALICE);

        AtmException e = assertDeclined(() -> atm.enterPin("0000"));
        assertTrue(e.getMessage().contains("2 attempt(s) left"));
        atm.enterPin("1234");
        assertEquals(AtmStatus.AUTHENTICATED, atm.status());
    }

    @Test
    void threeWrongPinsBlockAndRetainTheCard() {
        List<Card> retained = new ArrayList<>();
        atm.addListener(new AtmEventListener() {
            @Override
            public void onCardRetained(Card card) {
                retained.add(card);
            }
        });
        atm.insertCard(BOB);
        assertDeclined(() -> atm.enterPin("1111"));
        assertDeclined(() -> atm.enterPin("2222"));
        AtmException blocked = assertDeclined(() -> atm.enterPin("3333"));

        assertTrue(blocked.getMessage().contains("retained"));
        assertEquals(AtmStatus.IDLE, atm.status());
        assertEquals(List.of(BOB), retained);
        assertTrue(bank.isCardBlocked(BOB));

        assertDeclined(() -> atm.insertCard(BOB));             // a blocked card is kept on insertion too
        assertEquals(AtmStatus.IDLE, atm.status());
        assertEquals(List.of(BOB, BOB), retained);
    }

    @Test
    void successfulPinResetsTheFailureCounter() {
        atm.insertCard(ALICE);
        assertDeclined(() -> atm.enterPin("0000"));
        assertDeclined(() -> atm.enterPin("0000"));
        atm.enterPin("1234");
        atm.ejectCard();

        atm.insertCard(ALICE);
        assertDeclined(() -> atm.enterPin("0000"));             // counter restarted: 2 left, not blocked
        assertFalse(bank.isCardBlocked(ALICE));
    }

    @Test
    void unknownCardIsReturnedNotRetained() {
        Card stranger = new Card("6011000000000004");
        atm.insertCard(stranger);

        AtmException e = assertDeclined(() -> atm.enterPin("1234"));
        assertTrue(e.getMessage().contains("not recognised"));
        assertEquals(AtmStatus.IDLE, atm.status());
    }

    // ------------------------------------------------------------------ withdrawals

    @Test
    void withdrawalDebitsAccountAndDispensesFewestNotes() {
        loginAlice();

        Map<Integer, Integer> notes = atm.withdraw(3700);

        assertEquals(Map.of(2000, 1, 500, 3, 200, 1), notes);
        assertEquals(46_300, atm.checkBalance());
        assertEquals(28_000 - 3700, atm.cashAvailable());
    }

    @Test
    void withdrawalValidation() {
        loginAlice();

        assertDeclined(() -> atm.withdraw(0));
        assertDeclined(() -> atm.withdraw(150));                   // not a multiple of 100
        assertDeclined(() -> atm.withdraw(20_100));                // over the per-transaction maximum
        assertEquals(50_000, atm.checkBalance());                  // nothing was charged
    }

    @Test
    void insufficientFundsIsDeclinedAndNothingIsDispensed() {
        atm.insertCard(BOB);
        atm.enterPin("4321");

        AtmException e = assertDeclined(() -> atm.withdraw(3500));
        assertEquals("Insufficient funds", e.getMessage());
        assertEquals(28_000, atm.cashAvailable());
    }

    @Test
    void dailyLimitSpansSessionsAndResetsNextDay() {
        loginAlice();
        atm.withdraw(20_000);
        atm.ejectCard();

        loginAlice();
        AtmException e = assertDeclined(() -> atm.withdraw(6000));   // 20,000 + 6,000 > 25,000
        assertTrue(e.getMessage().contains("Daily limit"));
        atm.withdraw(5000);                                         // exactly up to the limit
        atm.ejectCard();

        clock.now = clock.now.plusSeconds(24 * 3600);               // next day
        atm.insertCard(ALICE);
        atm.enterPin("1234");
        assertDoesNotThrow(() -> atm.withdraw(1000));
    }

    @Test
    void amountTheMachineCannotMakeIsDeclinedBeforeCharging() {
        CashInventory onlyBig = new CashInventory(2000, 500);
        onlyBig.add(Map.of(2000, 3));
        Atm small = new Atm("T-2", bank, new CashDispenser(onlyBig, new OptimalDispenseStrategy(),
                CashHardware.ALWAYS_WORKS), 20_000, clock);
        small.insertCard(ALICE);
        small.enterPin("1234");

        assertDeclined(() -> small.withdraw(2500));                 // no 500 notes left
        assertDeclined(() -> small.withdraw(8000));                 // more than the machine holds
        assertEquals(50_000, small.checkBalance());
    }

    @Test
    void dispenserJamRefundsTheCustomerAndTakesTheAtmOffline() {
        loginAlice();
        jam.set(true);

        AtmException e = assertDeclined(() -> atm.withdraw(2000));

        assertTrue(e.getMessage().contains("not charged"));
        assertEquals(50_000, atm.checkBalance());                  // compensating refund
        assertEquals(28_000, atm.cashAvailable());                 // notes never left the cassette
        atm.ejectCard();
        assertEquals(AtmStatus.OUT_OF_SERVICE, atm.status());      // needs an engineer

        jam.set(false);
        atm.finishMaintenance();
        assertEquals(AtmStatus.IDLE, atm.status());
    }

    @Test
    void refundAlsoRestoresTheDailyAllowance() {
        loginAlice();
        jam.set(true);
        assertDeclined(() -> atm.withdraw(20_000));
        jam.set(false);
        atm.ejectCard();
        atm.finishMaintenance();

        loginAlice();
        assertDoesNotThrow(() -> atm.withdraw(20_000));             // the failed attempt did not use the limit
    }

    // ------------------------------------------------------------------ deposits, balance, journal

    @Test
    void depositAddsToAccountAndCassettes() {
        loginAlice();
        atm.deposit(Map.of(500, 4, 100, 3));

        assertEquals(52_300, atm.checkBalance());
        assertEquals(28_000 + 2300, atm.cashAvailable());
        assertDeclined(() -> atm.deposit(Map.of(50, 1)));            // unknown note
        assertDeclined(() -> atm.deposit(Map.of()));
    }

    @Test
    void journalRecordsSuccessAndFailureWithMaskedCard() {
        loginAlice();
        atm.withdraw(1000);
        assertDeclined(() -> atm.withdraw(150));
        atm.checkBalance();

        List<TransactionRecord> journal = atm.journal();
        assertEquals(3, journal.size());
        assertEquals(TransactionType.WITHDRAWAL, journal.get(0).type());
        assertTrue(journal.get(0).success());
        assertEquals(49_000, journal.get(0).balance());
        assertFalse(journal.get(1).success());
        assertEquals("**** 1111", journal.get(2).maskedCard());
        assertEquals(List.of(1L, 2L, 3L), journal.stream().map(TransactionRecord::id).toList());
    }

    // ------------------------------------------------------------------ out of service

    @Test
    void emptyingTheCashTakesTheAtmOfflineAfterTheSession() {
        CashInventory little = new CashInventory(500, 100);
        little.add(Map.of(500, 2));
        Atm small = new Atm("T-3", bank, new CashDispenser(little, new OptimalDispenseStrategy(),
                CashHardware.ALWAYS_WORKS), 20_000, clock);
        small.insertCard(ALICE);
        small.enterPin("1234");
        small.withdraw(1000);                                       // takes the last notes

        assertEquals(AtmStatus.AUTHENTICATED, small.status());      // current customer is not cut off
        small.ejectCard();
        assertEquals(AtmStatus.OUT_OF_SERVICE, small.status());
        assertDeclined(small::finishMaintenance);                  // cannot come back without cash
        small.refill(Map.of(100, 50));
        small.finishMaintenance();
        assertEquals(AtmStatus.IDLE, small.status());
    }

    @Test
    void atmStartsOfflineWhenBuiltWithoutCash() {
        Atm empty = new Atm("T-4", bank, new CashDispenser(new CashInventory(100), new OptimalDispenseStrategy(),
                CashHardware.ALWAYS_WORKS), 20_000, clock);

        assertEquals(AtmStatus.OUT_OF_SERVICE, empty.status());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Card("12"));
        assertThrows(IllegalArgumentException.class, () -> bank.openAccount(new Card("1234567890123"), "12", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> cash.add(Map.of(50, 1)));
        assertEquals("**** 1111", ALICE.masked());
    }
}
