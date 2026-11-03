package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewerFormStateTest {

    private static Snapshot idle(long generation, String reason) {
        return new Snapshot(LaunchOptions.Role.VIEWER, UiPhase.IDLE, generation, 0, reason, null, 5001, 5000,
                null, 0, 0, null, 0, false, TrafficStats.ZERO, Snapshot.Cleanup.NONE);
    }

    @Test void pendingInputErrorSurvivesRepeatedIdleWithOldReason() {
        Snapshot old = idle(0, "Đã ngắt kết nối.");
        var pending = ViewerFormState.pendingFor(ConnectOutcome.INVALID_INPUT, old.generation());
        for (int i = 0; i < 3; i++) {
            pending = pending.retainFor(old);
            assertNotNull(pending, "an old close reason cannot erase the newer input error");
            assertEquals(ViewerFormState.textFor(ConnectOutcome.INVALID_INPUT), pending.text());
        }
    }

    @Test void pendingTextClearsOnlyOnEditStartedOrNewAttempt() {
        var pending = ViewerFormState.pendingFor(ConnectOutcome.INVALID_INPUT, 0);
        assertSame(pending, pending.retainFor(idle(0, "")));
        assertNull(pending.retainFor(idle(1, "")));
        assertNull(pending.retainFor(client(UiPhase.CONNECTING, "")));
        assertNull(ViewerFormState.pendingFor(ConnectOutcome.STARTED, 0));
        assertNotNull(ViewerFormState.pendingFor(ConnectOutcome.BUSY, 0));
    }

    static Snapshot client(UiPhase phase, String message) {
        return new Snapshot(LaunchOptions.Role.VIEWER, phase, 1, 0, message, "192.168.1.5", 5001, 5000, null, 1920,
                1080, null, 0, false, TrafficStats.ZERO, Snapshot.Cleanup.NONE);
    }

    @Test
    void idleAllowsConnect() {
        ViewerFormState s = ViewerFormState.of(client(UiPhase.IDLE, ""), false);
        assertTrue(s.fieldsEnabled());
        assertTrue(s.connectEnabled());
        assertFalse(s.cancelVisible());
        assertFalse(s.disconnectVisible());
        assertFalse(s.controlWindowVisible());
    }

    @Test
    void preActivePhasesShowCancelAndLockFields() {
        for (UiPhase p : List.of(UiPhase.CONNECTING, UiPhase.AUTHENTICATING, UiPhase.ATTACHING)) {
            ViewerFormState s = ViewerFormState.of(client(p, ""), false);
            assertTrue(s.cancelVisible(), p.name());
            assertFalse(s.fieldsEnabled(), p.name());
            assertFalse(s.connectEnabled(), p.name());
            assertFalse(s.controlWindowVisible(), p.name());
        }
    }

    @Test
    void viewerControlWindowOnlyAfterActive() {
        for (UiPhase phase : UiPhase.values()) {
            ViewerFormState state = ViewerFormState.of(client(phase, ""), false);
            assertEquals(phase == UiPhase.ACTIVE, state.controlWindowVisible(), phase.name());
            assertEquals(phase == UiPhase.IDLE, state.fieldsEnabled(), phase.name());
            assertEquals(phase == UiPhase.IDLE, state.connectEnabled(), phase.name());
        }
        ViewerFormState s = ViewerFormState.of(client(UiPhase.ACTIVE, ""), false);
        assertTrue(s.controlWindowVisible());
        assertEquals("Đang chờ ảnh Host…", s.statusText());
        assertTrue(s.disconnectVisible());
        assertFalse(s.connectEnabled());
        assertFalse(s.cancelVisible());
    }

    @Test
    void closingKeepsConnectDisabled() {
        ViewerFormState s = ViewerFormState.of(client(UiPhase.CLOSING, ""), false);
        assertFalse(s.connectEnabled());
        assertFalse(s.fieldsEnabled());
        assertFalse(s.controlWindowVisible());
    }

    @Test
    void closingWarningAfterTwoSecondsDoesNotEnableConnect() {
        ViewerFormState s = ViewerFormState.of(client(UiPhase.CLOSING, ""), true);
        assertEquals("Đang đóng phiên…", s.statusText());
        assertFalse(s.connectEnabled());
    }

    @Test
    void failedInputKeepsConnectDisabledAndShowsMessage() {
        ViewerFormState s = ViewerFormState.of(client(UiPhase.FAILED, Messages.INPUT_RELEASE_FAILED), false);
        assertFalse(s.connectEnabled());
        assertFalse(s.fieldsEnabled());
        assertEquals(Messages.INPUT_RELEASE_FAILED, s.statusText());
    }

    @Test
    void idleWithMessageShowsReasonAndAllowsRetry() {
        ViewerFormState s = ViewerFormState.of(client(UiPhase.IDLE, "Mã không đúng."), false);
        assertTrue(s.connectEnabled());
        assertEquals("Mã không đúng.", s.statusText());
    }

    @Test
    void everyPhaseHasStatusText() {
        for (UiPhase p : UiPhase.values()) {
            assertFalse(ViewerFormState.of(client(p, ""), false).statusText().isBlank(), p.name());
        }
    }

    @Test
    void connectOutcomesHaveUserTexts() {
        assertEquals("", ViewerFormState.textFor(ConnectOutcome.STARTED));
        for (ConnectOutcome o : ConnectOutcome.values()) {
            if (o != ConnectOutcome.STARTED) assertFalse(ViewerFormState.textFor(o).isBlank(), o.name());
        }
        assertEquals("Đang đóng phiên…", ViewerFormState.textFor(ConnectOutcome.WAITING_RELEASE));
        assertEquals(Messages.INPUT_RELEASE_FAILED, ViewerFormState.textFor(ConnectOutcome.INPUT_FAULTED));
    }
}
