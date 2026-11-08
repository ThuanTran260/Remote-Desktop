package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class ChannelTrafficTest {

    @Test
    void wrapExposesTheCounterItCounts() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, lo);
                Socket a = new Socket(lo, server.getLocalPort());
                Socket b = server.accept()) {
            TrafficCounter counterA = new TrafficCounter();
            Channel ca = Channel.wrap(a, counterA);
            Channel cb = Channel.wrap(b, new TrafficCounter());
            assertSame(counterA, ca.traffic());

            ca.out().write(new byte[10]);
            ca.out().flush();
            cb.in().readFully(new byte[10]);

            assertEquals(10, ca.traffic().bytesOut());
            assertEquals(10, cb.traffic().bytesIn());
            assertEquals(10, ca.traffic().bytesOut(), "bytes are counted once, not once per wrapper");
            assertEquals(0, ca.traffic().bytesIn());
            assertEquals(0, cb.traffic().bytesOut());
        }
    }

    @Test
    void threeArgConstructorGivesIndependentCounter() throws Exception {
        Channel one = new Channel(new Socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
        Channel two = new Channel(new Socket(), new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(OutputStream.nullOutputStream()));
        assertNotNull(one.traffic());
        assertNotSame(one.traffic(), two.traffic());
        assertEquals(0, one.traffic().bytesIn());
        assertEquals(0, one.traffic().bytesOut());
    }
}
