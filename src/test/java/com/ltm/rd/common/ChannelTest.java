package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ChannelTest {
    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) i;
        return b;
    }

    // ---- TrafficCounter --------------------------------------------------------------------

    @Test
    void outputCountsByteArrayExactlyOnce() throws IOException {
        TrafficCounter c = new TrafficCounter();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream out = c.countingOutput(sink);
        out.write(bytes(10));
        assertEquals(10, c.bytesOut());
        out.write(bytes(10), 2, 5);
        assertEquals(15, c.bytesOut());
        assertEquals(15, sink.size());
        assertEquals(0, c.bytesIn());
    }

    @Test
    void outputCountsSingleBytes() throws IOException {
        TrafficCounter c = new TrafficCounter();
        OutputStream out = c.countingOutput(new ByteArrayOutputStream());
        out.write(1);
        out.write(2);
        assertEquals(2, c.bytesOut());
    }

    @Test
    void outputByteArrayIsNotWrittenByteByByte() throws IOException {
        TrafficCounter c = new TrafficCounter();
        int[] bulkCalls = {0};
        int[] singleCalls = {0};
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
                singleCalls[0]++;
            }

            @Override
            public void write(byte[] b, int off, int len) {
                bulkCalls[0]++;
            }
        };
        c.countingOutput(sink).write(bytes(100));
        assertEquals(1, bulkCalls[0]);
        assertEquals(0, singleCalls[0]);
    }

    @Test
    void inputCountsArrayAndSingleBytesAndSkipOnce() throws IOException {
        TrafficCounter c = new TrafficCounter();
        InputStream in = c.countingInput(new ByteArrayInputStream(bytes(100)));
        assertEquals(0, in.read());
        assertEquals(1, c.bytesIn());
        byte[] buf = new byte[10];
        assertEquals(10, in.read(buf));
        assertEquals(11, c.bytesIn());
        assertEquals(5, in.read(buf, 2, 5));
        assertEquals(16, c.bytesIn());
        assertEquals(4, in.skip(4));
        assertEquals(20, c.bytesIn());
        assertEquals(0, c.bytesOut());
    }

    @Test
    void inputEofIsNotCounted() throws IOException {
        TrafficCounter c = new TrafficCounter();
        InputStream in = c.countingInput(new ByteArrayInputStream(new byte[0]));
        assertEquals(-1, in.read());
        assertEquals(-1, in.read(new byte[4]));
        assertEquals(0, c.bytesIn());
    }

    @Test
    void totalsAreThreadSafe() throws Exception {
        TrafficCounter c = new TrafficCounter();
        OutputStream out = c.countingOutput(OutputStream.nullOutputStream());
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                try {
                    for (int k = 0; k < 10_000; k++) out.write(1);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(40_000, c.bytesOut());
    }

    // ---- Channel ---------------------------------------------------------------------------

    @Test
    void wrapCountsTrafficThroughBothDirections() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                Socket client = new Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort());
                Socket accepted = server.accept()) {
            TrafficCounter cc = new TrafficCounter();
            TrafficCounter sc = new TrafficCounter();
            Channel a = Channel.wrap(client, cc);
            Channel b = Channel.wrap(accepted, sc);
            PacketIO.write(a.out(), Packet.ping(7));
            a.out().flush();
            var p = PacketIO.read(b.in(), Protocol.CMD_MAX_LEN, java.util.EnumSet.of(PacketType.PING));
            assertEquals(Packet.ping(7), p.orElseThrow());
            assertEquals(9, cc.bytesOut());
            assertEquals(9, sc.bytesIn());
            assertSame(client, a.socket());
        }
    }

    @Test
    void closeRawSocketDoesNotFlushBufferedData() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                Socket client = new Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort());
                Socket accepted = server.accept()) {
            TrafficCounter counter = new TrafficCounter();
            Channel ch = Channel.wrap(client, counter);
            ch.out().writeInt(42); // sits in the BufferedOutputStream, never flushed
            ch.closeRawSocket();
            assertTrue(client.isClosed());
            assertEquals(-1, accepted.getInputStream().read(), "peer must see EOF without the buffered bytes");
            assertEquals(0, counter.bytesOut());
            ch.closeWrappersQuietly(); // must not throw even though the socket is gone
            ch.closeRawSocket(); // idempotent
            assertEquals(0, counter.bytesOut());
        }
    }
}
