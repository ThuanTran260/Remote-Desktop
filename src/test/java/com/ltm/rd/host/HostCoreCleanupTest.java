package com.ltm.rd.host;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class HostCoreCleanupTest {
    private final com.ltm.rd.host.HostTestAccess.HostResources hostResources = new com.ltm.rd.host.HostTestAccess.HostResources();

    @org.junit.jupiter.api.AfterEach void closeHostResources() { hostResources.close(); }

    private static final InetAddress LO = InetAddress.getLoopbackAddress();
    private static final long WAIT_S = 10;

    private HostCore core() {
        return hostResources.core(new HostConfig(LO, 0, 0, Timeouts.DEFAULTS, System::nanoTime,
                new SecureRandom()));
    }

    @Test
    void acceptedSocketOptionsRequestImageBufferOnlyForImage() throws Exception {
        class RecordingSocket extends java.net.Socket {
            final java.util.List<String> calls = new java.util.ArrayList<>();
            @Override public void setTcpNoDelay(boolean value) { calls.add("nodelay:" + value); }
            @Override public void setSendBufferSize(int value) { calls.add("sendBuffer:" + value); }
            @Override public void setSoTimeout(int value) { calls.add("timeout:" + value); }
        }
        RecordingSocket command = new RecordingSocket();
        RecordingSocket image = new RecordingSocket();
        HostCore.configureAcceptedSocket(command, HandshakeHandler.Kind.COMMAND, 3000);
        HostCore.configureAcceptedSocket(image, HandshakeHandler.Kind.IMAGE, 3000);
        assertEquals(java.util.List.of("nodelay:true", "timeout:3000"), command.calls);
        assertEquals(java.util.List.of("nodelay:true", "sendBuffer:65536", "timeout:3000"), image.calls);
    }

    @Test
    void defaultConfigurationReleasesPendingRegistrationAndPorts() throws Exception {
        Set<Thread> before = liveRdThreads();
        HostCore core = core();
        try {
            core.start();
            int cmd = core.cmdPort();
            int img = core.imgPort();
            assertEquals(1, core.tickerRegistrations(), "the default pending sweep is registered");

            core.close();
            assertEquals(0, core.tickerRegistrations(), "close releases the pending sweep");
            core.close();
            assertEquals(0, core.tickerRegistrations(), "repeated close keeps the registration released");
            try (ServerSocket a = new ServerSocket(cmd, 1, LO); ServerSocket b = new ServerSocket(img, 1, LO)) {
                assertTrue(a.isBound() && b.isBound(), "both listening ports are reusable");
            }
            awaitNoNewRdThreads(before);
        } finally {
            core.close();
        }
    }

    @Test
    void closeBeforeStartDoesNotCreateResources() {
        Set<Thread> before = liveRdThreads();
        HostCore core = core();
        try {
            core.close();
            assertEquals(0, core.tickerRegistrations());
            core.close();
            assertEquals(0, core.tickerRegistrations());
            assertThrows(IllegalStateException.class, core::start);
            assertEquals(0, core.tickerRegistrations(), "a rejected start creates no pending sweep");
            awaitNoNewRdThreads(before);
        } finally {
            core.close();
        }
    }

    private static Set<Thread> liveRdThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("rd-"))
                .collect(Collectors.toSet());
    }

    private static Set<String> newRdThreadNames(Set<Thread> before) {
        Set<String> names = new TreeSet<>();
        for (Thread t : liveRdThreads()) {
            if (!before.contains(t)) names.add(t.getName());
        }
        return names;
    }

    private static void awaitNoNewRdThreads(Set<Thread> before) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        Set<String> leaked = newRdThreadNames(before);
        while (!leaked.isEmpty() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000);
            leaked = newRdThreadNames(before);
        }
        assertEquals(Set.of(), leaked, "all new rd-* threads have stopped");
    }
}
