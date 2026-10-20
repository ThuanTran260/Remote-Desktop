package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class ServerCoreLateBindTest {
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
        ServerCore[] holder = new ServerCore[1];
        // close() runs after both sockets are bound but before start() published them
        ServerCore.Seams seams = new ServerCore.Seams(null, null, null, false, () -> holder[0].close());
        ServerCore core = new ServerCore(new ServerConfig(LO, cmd, img, Timeouts.DEFAULTS, System::nanoTime,
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
