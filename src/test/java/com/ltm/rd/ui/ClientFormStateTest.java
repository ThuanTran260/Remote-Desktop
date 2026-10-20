package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientFormStateTest {

    static Snapshot client(UiPhase phase, String message) {
        return new Snapshot(LaunchOptions.Role.CLIENT, phase, 1, 0, message, "192.168.1.5", 5001, 5000, null, 1920,
                1080, null, 0, false, TrafficStats.ZERO);
    }

    @Test
    void idleAllowsConnect() {
        ClientFormState s = ClientFormState.of(client(UiPhase.IDLE, ""), false);
        assertTrue(s.fieldsEnabled());
        assertTrue(s.connectEnabled());
        assertFalse(s.cancelVisible());
        assertFalse(s.disconnectVisible());
        assertFalse(s.sharingBarVisible());
    }

    @Test
    void preActivePhasesShowCancelAndLockFields() {
        for (UiPhase p : List.of(UiPhase.CONNECTING, UiPhase.AUTHENTICATING, UiPhase.ATTACHING)) {
            ClientFormState s = ClientFormState.of(client(p, ""), false);
            assertTrue(s.cancelVisible(), p.name());
            assertFalse(s.fieldsEnabled(), p.name());
            assertFalse(s.connectEnabled(), p.name());
            assertFalse(s.sharingBarVisible(), p.name());
        }
    }

    @Test
    void activeShowsSharingBarWithPeerAndStop() {
        ClientFormState s = ClientFormState.of(client(UiPhase.ACTIVE, ""), false);
        assertTrue(s.sharingBarVisible());
        assertEquals("Đang bị điều khiển bởi 192.168.1.5", s.statusText());
        assertTrue(s.disconnectVisible());
        assertFalse(s.connectEnabled());
        assertFalse(s.cancelVisible());
    }

    @Test
    void closingKeepsConnectDisabled() {
        ClientFormState s = ClientFormState.of(client(UiPhase.CLOSING, ""), false);
        assertFalse(s.connectEnabled());
        assertFalse(s.fieldsEnabled());
        assertFalse(s.sharingBarVisible());
    }

    @Test
    void closingWarningAfterTwoSecondsDoesNotEnableConnect() {
        ClientFormState s = ClientFormState.of(client(UiPhase.CLOSING, ""), true);
        assertEquals("Đang chờ nhả phím/nút trên máy này…", s.statusText());
        assertFalse(s.connectEnabled());
    }

    @Test
    void failedInputKeepsConnectDisabledAndShowsMessage() {
        ClientFormState s = ClientFormState.of(client(UiPhase.FAILED, Messages.INPUT_RELEASE_FAILED), false);
        assertFalse(s.connectEnabled());
        assertFalse(s.fieldsEnabled());
        assertEquals(Messages.INPUT_RELEASE_FAILED, s.statusText());
    }

    @Test
    void idleWithMessageShowsReasonAndAllowsRetry() {
        ClientFormState s = ClientFormState.of(client(UiPhase.IDLE, "Mã không đúng."), false);
        assertTrue(s.connectEnabled());
        assertEquals("Mã không đúng.", s.statusText());
    }

    @Test
    void everyPhaseHasStatusText() {
        for (UiPhase p : UiPhase.values()) {
            assertFalse(ClientFormState.of(client(p, ""), false).statusText().isBlank(), p.name());
        }
    }

    @Test
    void connectOutcomesHaveUserTexts() {
        assertEquals("", ClientFormState.textFor(ConnectOutcome.STARTED));
        for (ConnectOutcome o : ConnectOutcome.values()) {
            if (o != ConnectOutcome.STARTED) assertFalse(ClientFormState.textFor(o).isBlank(), o.name());
        }
        assertEquals("Đang chờ nhả phím/nút trên máy này…", ClientFormState.textFor(ConnectOutcome.WAITING_RELEASE));
        assertEquals(Messages.INPUT_RELEASE_FAILED, ClientFormState.textFor(ConnectOutcome.INPUT_FAULTED));
    }
}
