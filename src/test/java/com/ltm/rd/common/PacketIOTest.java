package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PacketIOTest {
    private static final Set<PacketType> ALL = EnumSet.allOf(PacketType.class);

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] serialize(Packet p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PacketIO.write(new DataOutputStream(bos), p);
        return bos.toByteArray();
    }

    private static DataInputStream in(byte[] b) {
        return new DataInputStream(new ByteArrayInputStream(b));
    }

    /** Stream that returns at most one byte per read call. */
    private static final class OneByteStream extends InputStream {
        private final byte[] data;
        private int pos;

        OneByteStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) return 0;
            int c = read();
            if (c < 0) return -1;
            b[off] = (byte) c;
            return 1;
        }
    }

    private static List<Packet> samplePackets() {
        byte[] tok = new byte[16];
        for (int i = 0; i < 16; i++) tok[i] = (byte) (0xF0 + i);
        return List.of(
                Packet.auth("012345"),
                Packet.authResult(Packet.AuthStatus.OK, tok),
                Packet.authResult(Packet.AuthStatus.WRONG_CODE, new byte[16]),
                Packet.screenInfo(1920, 1080),
                Packet.attach(tok),
                Packet.sessionActive(),
                Packet.frame(1280, 720, new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3}),
                Packet.mouseMove(100, 200),
                Packet.mousePress(5, 6, 1),
                Packet.mouseRelease(5, 6, 3),
                Packet.wheel(-3),
                Packet.keyPress(65),
                Packet.keyRelease(0x1000A),
                Packet.releaseAll(),
                Packet.ping(42),
                Packet.pong(-42),
                Packet.disconnect(Packet.DisconnectReason.SCREEN_CHANGED, "màn hình đổi"));
    }

    @Test
    void roundTripEveryType() throws IOException {
        Set<PacketType> covered = EnumSet.noneOf(PacketType.class);
        for (Packet p : samplePackets()) {
            ByteArrayInputStream bis = new ByteArrayInputStream(serialize(p));
            Packet back = PacketIO.read(new DataInputStream(bis), Protocol.IMG_MAX_LEN, ALL).orElseThrow();
            assertEquals(p, back);
            assertEquals(0, bis.available(), "reader must consume exactly one packet");
            covered.add(p.type());
        }
        assertEquals(EnumSet.allOf(PacketType.class), covered);
    }

    @Test
    void roundTripWithOneBytePerRead() throws IOException {
        for (Packet p : samplePackets()) {
            DataInputStream din = new DataInputStream(new OneByteStream(serialize(p)));
            assertEquals(p, PacketIO.read(din, Protocol.IMG_MAX_LEN, ALL).orElseThrow());
        }
    }

    @Test
    void backToBackPackets() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        PacketIO.write(out, Packet.ping(1));
        PacketIO.write(out, Packet.releaseAll());
        PacketIO.write(out, Packet.pong(2));
        DataInputStream din = in(bos.toByteArray());
        assertEquals(Packet.ping(1), PacketIO.read(din, 1024, ALL).orElseThrow());
        assertEquals(Packet.releaseAll(), PacketIO.read(din, 1024, ALL).orElseThrow());
        assertEquals(Packet.pong(2), PacketIO.read(din, 1024, ALL).orElseThrow());
        assertTrue(PacketIO.read(din, 1024, ALL).isEmpty());
    }

    @Test
    void goldenAuth() throws IOException {
        byte[] expected = bytes(0x01, 0, 0, 0, 0x0B, 0x4C, 0x54, 0x4D, 0x52, 0x01,
                0x31, 0x32, 0x33, 0x34, 0x35, 0x36);
        assertArrayEquals(expected, serialize(Packet.auth("123456")));
    }

    @Test
    void goldenAttach() throws IOException {
        byte[] tok = new byte[16];
        for (int i = 0; i < 16; i++) tok[i] = (byte) i;
        byte[] expected = bytes(0x04, 0, 0, 0, 0x15, 0x4C, 0x54, 0x4D, 0x52, 0x01,
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        assertArrayEquals(expected, serialize(Packet.attach(tok)));
    }

    @Test
    void goldenMousePress() throws IOException {
        byte[] expected = bytes(0x21, 0, 0, 0, 9, 0, 0, 0, 100, 0, 0, 0, 200, 1);
        assertArrayEquals(expected, serialize(Packet.mousePress(100, 200, 1)));
    }

    @Test
    void goldenFrameHeader() throws IOException {
        byte[] expected = bytes(0x10, 0, 0, 0, 10, 0, 0, 0x02, 0x80, 0, 0, 0x01, 0xE0, 0xFF, 0xD8);
        assertArrayEquals(expected, serialize(Packet.frame(640, 480, bytes(0xFF, 0xD8))));
    }

    @Test
    void goldenAuthResultAndDisconnect() throws IOException {
        byte[] tok = new byte[16];
        Arrays.fill(tok, (byte) 0xEE);
        byte[] exp = new byte[22];
        exp[0] = 0x02;
        exp[4] = 17;
        Arrays.fill(exp, 6, 22, (byte) 0xEE);
        assertArrayEquals(exp, serialize(Packet.authResult(Packet.AuthStatus.OK, tok)));
        assertArrayEquals(bytes(0x3F, 0, 0, 0, 3, 2, 'h', 'i'),
                serialize(Packet.disconnect(Packet.DisconnectReason.PROTOCOL_ERROR, "hi")));
    }

    @Test
    void bigEndianInts() throws IOException {
        assertArrayEquals(bytes(0x30, 0, 0, 0, 4, 1, 2, 3, 4), serialize(Packet.ping(0x01020304)));
        Packet p = PacketIO.read(in(bytes(0x24, 0, 0, 0, 4, 0, 1, 0, 0)), 1024, ALL).orElseThrow();
        assertEquals(0x10000, p.keyCode());
    }

    @Test
    void writeDoesNotFlush() throws IOException {
        int[] flushes = {0};
        OutputStream os = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                flushes[0]++;
            }
        };
        PacketIO.write(new DataOutputStream(os), Packet.ping(1));
        assertEquals(0, flushes[0]);
    }

    @Test
    void cleanEofAtFirstByteIsEmpty() throws IOException {
        assertTrue(PacketIO.read(in(new byte[0]), 1024, ALL).isEmpty());
    }

    @Test
    void eofAtEveryPositionInsideAPacketIsEofException() throws IOException {
        for (Packet p : samplePackets()) {
            byte[] wire = serialize(p);
            for (int cut = 1; cut < wire.length; cut++) {
                byte[] part = Arrays.copyOf(wire, cut);
                assertThrows(EOFException.class,
                        () -> PacketIO.read(in(part), Protocol.IMG_MAX_LEN, ALL),
                        p.type() + " cut at " + cut);
                assertThrows(EOFException.class,
                        () -> PacketIO.read(new DataInputStream(new OneByteStream(part)),
                                Protocol.IMG_MAX_LEN, ALL),
                        p.type() + " (1 byte/read) cut at " + cut);
            }
        }
    }

    @Test
    void unknownTypeRejectedBeforeLength() {
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes(0x77, 0, 0, 0, 4, 1, 2, 3, 4));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(new DataInputStream(bis), 1024, ALL));
        assertEquals(8, bis.available(), "only the type byte may be consumed");
    }

    @Test
    void disallowedTypeRejectedWithoutConsumingLengthOrPayload() {
        byte[] wire = bytes(0x10, 0x00, 0x7F, 0xFF, 0xFF, 1, 2, 3);
        ByteArrayInputStream bis = new ByteArrayInputStream(wire);
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(new DataInputStream(bis), Protocol.IMG_MAX_LEN,
                        EnumSet.of(PacketType.ATTACH)));
        assertEquals(wire.length - 1, bis.available());
    }

    @Test
    void emptyAllowedSetRejectsEverything() throws IOException {
        byte[] wire = serialize(Packet.ping(1));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(wire), 1024, EnumSet.noneOf(PacketType.class)));
    }

    @Test
    void hugeFrameLengthNeverAllocated() {
        // Declares ~2 GiB but supplies no payload; allocation would OOM or the read would hit EOF.
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x10, 0x7F, 0xFF, 0xFF, 0xFF)), Protocol.IMG_MAX_LEN, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x10, 0x00, 0x80, 0x00, 0x01)), Protocol.IMG_MAX_LEN, ALL));
    }

    @Test
    void lengthBoundaries() {
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x30, 0xFF, 0xFF, 0xFF, 0xFF)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x10, 0x80, 0, 0, 0)), Protocol.IMG_MAX_LEN, ALL));
        // fixed types with wrong length; no payload supplied, so only a pre-read check can reject
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x30, 0, 0, 0, 3)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x30, 0, 0, 0, 5)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x26, 0, 0, 0, 1)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x10, 0, 0, 0, 8)), Protocol.IMG_MAX_LEN, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x3F, 0, 0, 0, 0)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x3F, 0, 0, 0, 202)), 1024, ALL));
    }

    @Test
    void channelMaxLenApplies() {
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x3F, 0, 0, 0, 101)), 100, ALL));
        // exactly at the channel limit is accepted (then EOF because no payload follows)
        assertThrows(EOFException.class,
                () -> PacketIO.read(in(bytes(0x3F, 0, 0, 0, 100)), 100, ALL));
    }

    @Test
    void maxSizeFrameRoundTrip() throws IOException {
        Packet p = Packet.frame(1280, 720, new byte[Protocol.IMG_MAX_LEN - 8]);
        assertEquals(p, PacketIO.read(in(serialize(p)), Protocol.IMG_MAX_LEN, ALL).orElseThrow());
    }

    @Test
    void decodeValidationRunsOnParsedPackets() throws IOException {
        byte[] badMagic = bytes(0x01, 0, 0, 0, 11, 0x4C, 0x54, 0x4D, 0x00, 1, '1', '2', '3', '4', '5', '6');
        assertThrows(ProtocolException.class, () -> PacketIO.read(in(badMagic), 1024, ALL));
        byte[] badCode = bytes(0x01, 0, 0, 0, 11, 0x4C, 0x54, 0x4D, 0x52, 1, '1', '2', '3', '4', '5', 'x');
        assertThrows(ProtocolException.class, () -> PacketIO.read(in(badCode), 1024, ALL));
        // a version mismatch is NOT an exception: the server answers BAD_VERSION
        byte[] v9 = bytes(0x01, 0, 0, 0, 11, 0x4C, 0x54, 0x4D, 0x52, 9, '1', '2', '3', '4', '5', '6');
        assertEquals(9, PacketIO.read(in(v9), 1024, ALL).orElseThrow().authPayload().version());

        byte[] st = new byte[22];
        st[0] = 0x02;
        st[4] = 17;
        st[5] = 5;
        assertThrows(ProtocolException.class, () -> PacketIO.read(in(st), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x03, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 4, 0)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x20, 0, 0, 0, 8, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0)),
                        1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x21, 0, 0, 0, 9, 0, 0, 0, 1, 0, 0, 0, 1, 4)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x23, 0, 0, 0, 4, 0, 0, 0, 0)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x23, 0, 0, 0, 4, 0, 0, 0, 21)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x24, 0, 0, 0, 4, 0, 0, 0, 0)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x25, 0, 0, 0, 4, 0x80, 0, 0, 0)), 1024, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x10, 0, 0, 0, 9, 0, 0, 5, 1, 0, 0, 0, 1, 7)),
                        Protocol.IMG_MAX_LEN, ALL));
        assertThrows(ProtocolException.class,
                () -> PacketIO.read(in(bytes(0x10, 0, 0, 0, 9, 0, 0, 0, 1, 0, 0, 0, 0, 7)),
                        Protocol.IMG_MAX_LEN, ALL));
    }

    @Test
    void parsedDisconnectWithInvalidUtf8() throws IOException {
        Packet p = PacketIO.read(in(bytes(0x3F, 0, 0, 0, 3, 0, 0xC3, 0x28)), 1024, ALL).orElseThrow();
        assertEquals("", p.disconnectPayload().message());
    }

    @Test
    void serializedPacketUnaffectedByCallerBufferMutation() throws IOException {
        byte[] jpeg = {1, 2, 3, 4};
        Packet p = Packet.frame(10, 10, jpeg);
        byte[] before = serialize(p);
        Arrays.fill(jpeg, (byte) 0x55);
        assertArrayEquals(before, serialize(p));
    }
}
