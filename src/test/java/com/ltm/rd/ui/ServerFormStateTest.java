package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerFormStateTest {

    static Snapshot server(UiPhase phase, String message) {
        return new Snapshot(LaunchOptions.Role.SERVER, phase, 0, 0, message, "192.168.1.5", 5001, 5000, "123456", 1920,
                1080, null, 0, false, TrafficStats.ZERO);
    }

    @Test
    void regenerateEnabledOnlyWhenListening() {
        for (UiPhase p : UiPhase.values()) {
            assertEquals(p == UiPhase.LISTENING, ServerFormState.of(server(p, ""), false).regenerateEnabled(), p.name());
        }
    }

    @Test
    void controlWindowVisibleOnlyWhenActive() {
        for (UiPhase p : UiPhase.values()) {
            assertEquals(p == UiPhase.ACTIVE, ServerFormState.of(server(p, ""), false).controlWindowVisible(), p.name());
        }
    }

    @Test
    void disconnectEnabledWhenReservedOrActive() {
        Set<UiPhase> enabled = EnumSet.of(UiPhase.RESERVED, UiPhase.ACTIVE);
        for (UiPhase p : UiPhase.values()) {
            assertEquals(enabled.contains(p), ServerFormState.of(server(p, ""), false).disconnectEnabled(), p.name());
        }
    }

    @Test
    void statusTextShowsPeerReasonAndClosingWarning() {
        assertTrue(ServerFormState.of(server(UiPhase.ACTIVE, ""), false).statusText().contains("192.168.1.5"));
        assertTrue(ServerFormState.of(server(UiPhase.LISTENING, "Đã ngắt kết nối."), false).statusText()
                .startsWith("Đã ngắt kết nối."));
        assertEquals("Đang chờ nhả phím/nút trên máy này…",
                ServerFormState.of(server(UiPhase.CLOSING, ""), true).statusText());
        String bind = Messages.forThrowable(new java.net.BindException());
        assertEquals(bind, ServerFormState.of(server(UiPhase.FAILED, bind), false).statusText());
        for (UiPhase p : UiPhase.values()) {
            assertFalse(ServerFormState.of(server(p, ""), false).statusText().isBlank(), p.name());
        }
    }
}
