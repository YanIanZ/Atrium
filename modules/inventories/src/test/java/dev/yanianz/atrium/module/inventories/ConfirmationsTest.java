package dev.yanianz.atrium.module.inventories;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfirmationsTest {
    @Test
    void firstCallAsksAndSecondCallAccepts() {
        Confirmations confirmations = new Confirmations();
        assertFalse(confirmations.confirm("actor", "load|1", 1_000, 15_000));
        assertTrue(confirmations.confirm("actor", "load|1", 2_000, 15_000));
    }

    @Test
    void acceptedRequestMustBeConfirmedAgainAfterwards() {
        Confirmations confirmations = new Confirmations();
        assertFalse(confirmations.confirm("actor", "load|1", 0, 100));
        assertTrue(confirmations.confirm("actor", "load|1", 50, 100));
        assertFalse(confirmations.confirm("actor", "load|1", 60, 100));
    }

    @Test
    void expiredRequestStartsOver() {
        Confirmations confirmations = new Confirmations();
        assertFalse(confirmations.confirm("actor", "load|1", 0, 100));
        assertFalse(confirmations.confirm("actor", "load|1", 100, 100));
        assertTrue(confirmations.confirm("actor", "load|1", 199, 100));
    }

    @Test
    void differentRequestReplacesThePendingOne() {
        Confirmations confirmations = new Confirmations();
        assertFalse(confirmations.confirm("actor", "load|1", 0, 100));
        assertFalse(confirmations.confirm("actor", "load|2", 10, 100));
        assertFalse(confirmations.confirm("actor", "load|1", 20, 100));
        assertTrue(confirmations.confirm("actor", "load|1", 30, 100));
    }

    @Test
    void confirmationIsBoundToTheActor() {
        Confirmations confirmations = new Confirmations();
        assertFalse(confirmations.confirm("first", "load|1", 0, 100));
        assertFalse(confirmations.confirm("second", "load|1", 10, 100));
        assertTrue(confirmations.confirm("first", "load|1", 20, 100));
    }

    @Test
    void zeroTtlNeverAccepts() {
        Confirmations confirmations = new Confirmations();
        assertFalse(confirmations.confirm("actor", "load|1", 0, 0));
        assertFalse(confirmations.confirm("actor", "load|1", 1, 0));
    }

    @Test
    void quittingClearsOnlyThatActor() {
        Confirmations confirmations = new Confirmations();
        assertFalse(confirmations.confirm("first", "load|1", 0, 100));
        assertFalse(confirmations.confirm("second", "load|2", 0, 100));
        confirmations.clear("first");
        assertFalse(confirmations.confirm("first", "load|1", 10, 100));
        assertTrue(confirmations.confirm("second", "load|2", 10, 100));
    }
}
