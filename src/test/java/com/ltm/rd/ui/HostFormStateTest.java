package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HostFormStateTest {

    private Snapshot cleanup(Snapshot.Cleanup cleanup) {
        return new Snapshot(LaunchOptions.Role.HOST, UiPhase.CLOSING, 1, 0, "", null, 5001, 5000,
                null, 0, 0, null, 0, false, TrafficStats.ZERO, cleanup);
    }

    @Test void inputPendingTakesPriorityOverWorkers() {
        assertEquals("Đang nhả phím/chuột của phiên trước…", HostFormState.of(cleanup(Snapshot.Cleanup.INPUT_PENDING), true).statusText());
        assertEquals("Đang dọn phiên trước…", HostFormState.of(cleanup(Snapshot.Cleanup.WORKERS), true).statusText());
    }

    @Test void hostCleanupStatesHideCodeAndPreventRetry() {
        for (Snapshot.Cleanup cleanup : Snapshot.Cleanup.values()) {
            if (cleanup == Snapshot.Cleanup.NONE) continue;
            HostFormState state = HostFormState.of(cleanup(cleanup), false);
            assertFalse(state.regenerateEnabled());
            assertFalse(state.disconnectEnabled());
            assertFalse(state.retryEnabled());
        }
        assertEquals(Messages.INPUT_RELEASE_FAILED, HostFormState.of(cleanup(Snapshot.Cleanup.INPUT_FAILED), false).statusText());
        assertEquals(Messages.INPUT_FAULTED, HostFormState.of(cleanup(Snapshot.Cleanup.INPUT_FAULTED), false).statusText());
    }

    static Snapshot server(UiPhase phase, String message) {
        return new Snapshot(LaunchOptions.Role.HOST, phase, 0, 0, message, "192.168.1.5", 5001, 5000, "123456", 1920,
                1080, null, 0, false, TrafficStats.ZERO, Snapshot.Cleanup.NONE);
    }

    @Test
    void regenerateEnabledOnlyWhenListening() {
        for (UiPhase p : UiPhase.values()) {
            assertEquals(p == UiPhase.LISTENING, HostFormState.of(server(p, ""), false).regenerateEnabled(), p.name());
        }
    }

    @Test
    void controlWindowVisibleOnlyWhenActive() {
        for (UiPhase p : UiPhase.values()) {
            assertEquals(p == UiPhase.ACTIVE, HostFormState.of(server(p, ""), false).sharingBarVisible(), p.name());
        }
    }

    @Test
    void disconnectEnabledWhenReservedOrActive() {
        Set<UiPhase> enabled = EnumSet.of(UiPhase.RESERVED, UiPhase.ACTIVE);
        for (UiPhase p : UiPhase.values()) {
            assertEquals(enabled.contains(p), HostFormState.of(server(p, ""), false).disconnectEnabled(), p.name());
        }
    }

    @Test
    void statusTextShowsPeerReasonAndClosingWarning() {
        assertTrue(HostFormState.of(server(UiPhase.ACTIVE, ""), false).statusText().contains("192.168.1.5"));
        assertTrue(HostFormState.of(server(UiPhase.LISTENING, "Đã ngắt kết nối."), false).statusText()
                .startsWith("Đã ngắt kết nối."));
        assertEquals("Đang dọn phiên trước…",
                HostFormState.of(server(UiPhase.CLOSING, ""), true).statusText());
        String bind = Messages.forThrowable(new java.net.BindException());
        assertEquals(bind, HostFormState.of(server(UiPhase.FAILED, bind), false).statusText());
        for (UiPhase p : UiPhase.values()) {
            assertFalse(HostFormState.of(server(p, ""), false).statusText().isBlank(), p.name());
        }
    }
}
