package com.ltm.rd.host;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class HostCoreLateBindTest {
    private final com.ltm.rd.host.HostTestAccess.HostResources hostResources = new com.ltm.rd.host.HostTestAccess.HostResources();

    @org.junit.jupiter.api.AfterEach void closeHostResources() { hostResources.close(); }

    private static final InetAddress LO = InetAddress.getLoopbackAddress();

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, LO)) {
            return s.getLocalPort();
        }
    }

    @Test
    void closeBetweenBindAndPublishLeavesNoListeningPort() throws Exception {
        int cmd = freePort();
        int img = freePort();
        HostCore[] holder = new HostCore[1];
        // close() runs after both sockets are bound but before start() published them
        HostCore.Seams seams = new HostCore.Seams(null, null, null, false, () -> holder[0].close());
        HostCore core = hostResources.core(new HostConfig(LO, cmd, img, Timeouts.DEFAULTS, System::nanoTime,
                new SecureRandom()), seams);
        holder[0] = core;

        assertThrows(IllegalStateException.class, core::start);

        // both ports are free again: a late-bound socket would make these binds fail
        try (ServerSocket a = new ServerSocket(cmd, 1, LO); ServerSocket b = new ServerSocket(img, 1, LO)) {
            assertTrue(a.isBound() && b.isBound());
        }
        assertEquals(0, core.tickerRegistrations(), "no ticker was created for a closed core");
    }
}
