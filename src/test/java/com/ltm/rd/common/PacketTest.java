package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PacketTest {
    private static final byte[] TOKEN = new byte[Protocol.TOKEN_LEN];

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    void packetTypeTable() {
        assertEquals(0x01, PacketType.AUTH.code());
        assertEquals(11, PacketType.AUTH.minLength());
        assertEquals(11, PacketType.AUTH.maxLength());
        assertTrue(PacketType.AUTH.isFixed());
        assertEquals(17, PacketType.AUTH_RESULT.minLength());
        assertEquals(8, PacketType.SCREEN_INFO.minLength());
        assertEquals(21, PacketType.ATTACH.minLength());
        assertEquals(0, PacketType.SESSION_ACTIVE.minLength());
        assertEquals(0x10, PacketType.FRAME.code());
        assertEquals(9, PacketType.FRAME.minLength());
        assertEquals(Protocol.IMG_MAX_LEN, PacketType.FRAME.maxLength());
        assertFalse(PacketType.FRAME.isFixed());
        assertEquals(8, PacketType.MOUSE_MOVE.minLength());
        assertEquals(9, PacketType.MOUSE_PRESS.minLength());
        assertEquals(9, PacketType.MOUSE_RELEASE.minLength());
        assertEquals(4, PacketType.WHEEL.minLength());
        assertEquals(4, PacketType.KEY_PRESS.minLength());
        assertEquals(4, PacketType.KEY_RELEASE.minLength());
        assertEquals(0, PacketType.RELEASE_ALL.minLength());
        assertEquals(4, PacketType.PING.minLength());
        assertEquals(4, PacketType.PONG.minLength());
        assertEquals(0x3F, PacketType.DISCONNECT.code());
        assertEquals(1, PacketType.DISCONNECT.minLength());
        assertEquals(201, PacketType.DISCONNECT.maxLength());
    }

    @Test
    void fromCodeUnknownIsEmpty() {
        assertEquals(PacketType.PING, PacketType.fromCode(0x30).orElseThrow());
        assertTrue(PacketType.fromCode(0x00).isEmpty());
        assertTrue(PacketType.fromCode(0x99).isEmpty());
        assertTrue(PacketType.fromCode(-1).isEmpty());
        assertTrue(PacketType.fromCode(0x130).isEmpty());
    }

    @Test
    void protocolConstants() {
        assertEquals(5001, Protocol.CMD_PORT);
        assertEquals(5000, Protocol.IMG_PORT);
        assertEquals(0x4C544D52, Protocol.MAGIC);
        assertEquals(1, Protocol.VERSION);
        assertEquals(1024, Protocol.CMD_MAX_LEN);
        assertEquals(8 * 1024 * 1024, Protocol.IMG_MAX_LEN);
        assertEquals(8192, Protocol.MAX_SCREEN_DIM);
        assertEquals(33_554_432L, Protocol.MAX_SCREEN_PIXELS);
        assertEquals(1280, Protocol.MAX_FRAME_WIDTH);
        assertEquals(20, Protocol.MAX_WHEEL_NOTCHES);
        assertEquals(200, Protocol.DISCONNECT_MSG_MAX);
        assertEquals(16, Protocol.TOKEN_LEN);
        assertEquals(6, Protocol.CODE_LEN);
    }

    @Test
    void authDecode() {
        Packet.AuthPayload a = Packet.auth("123456").authPayload();
        assertEquals(Protocol.VERSION, a.version());
        assertEquals("123456", a.code());
    }

    @Test
    void authVersionMismatchIsDecodedNotRejected() {
        assertEquals(7, Packet.auth(7, "000000").authPayload().version());
    }

    @Test
    void authBadMagicRejected() {
        byte[] raw = Packet.auth("123456").payload();
        raw[0] ^= 1;
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.AUTH, raw));
    }

    @Test
    void authCodeMustBeSixDigits() {
        assertThrows(ProtocolException.class, () -> Packet.auth("12345"));
        assertThrows(ProtocolException.class, () -> Packet.auth("1234567"));
        assertThrows(ProtocolException.class, () -> Packet.auth("12345a"));
        assertThrows(ProtocolException.class, () -> Packet.auth("12345/"));
        assertThrows(ProtocolException.class, () -> Packet.auth("12345:"));
        assertThrows(ProtocolException.class, () -> Packet.auth("١٢٣٤٥٦"));
        byte[] raw = Packet.auth("123456").payload();
        raw[6] = (byte) 0xFF;
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.AUTH, raw));
    }

    @Test
    void authResultStatusAndToken() {
        byte[] tok = new byte[16];
        for (int i = 0; i < 16; i++) tok[i] = (byte) (i + 1);
        Packet ok = Packet.authResult(Packet.AuthStatus.OK, tok);
        assertEquals(Packet.AuthStatus.OK, ok.authResultPayload().status());
        assertArrayEquals(tok, ok.authResultPayload().token());

        for (Packet.AuthStatus s : Packet.AuthStatus.values()) {
            if (s == Packet.AuthStatus.OK) continue;
            Packet p = Packet.authResult(s, TOKEN);
            assertEquals(s, p.authResultPayload().status());
            assertThrows(ProtocolException.class, () -> Packet.authResult(s, tok));
        }
        assertEquals(0, Packet.AuthStatus.OK.code());
        assertEquals(4, Packet.AuthStatus.BAD_VERSION.code());
    }

    @Test
    void authResultRejectsUnknownStatusAndWrongTokenLength() {
        byte[] raw = new byte[17];
        raw[0] = 5;
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.AUTH_RESULT, raw));
        raw[0] = (byte) 0xFF;
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.AUTH_RESULT, raw));
        assertThrows(ProtocolException.class,
                () -> Packet.authResult(Packet.AuthStatus.OK, new byte[15]));
        byte[] bad = new byte[17];
        bad[0] = 1;
        bad[16] = 1;
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.AUTH_RESULT, bad));
    }

    @Test
    void screenInfoBounds() {
        Packet p = Packet.screenInfo(1920, 1080);
        assertEquals(1920, p.sizePayload().width());
        assertEquals(1080, p.sizePayload().height());
        Packet.screenInfo(1, 1);
        Packet.screenInfo(8192, 4096); // exactly 33_554_432 pixels
        assertThrows(ProtocolException.class, () -> Packet.screenInfo(0, 100));
        assertThrows(ProtocolException.class, () -> Packet.screenInfo(100, 0));
        assertThrows(ProtocolException.class, () -> Packet.screenInfo(-1, 100));
        assertThrows(ProtocolException.class, () -> Packet.screenInfo(8193, 1));
        assertThrows(ProtocolException.class, () -> Packet.screenInfo(1, 8193));
        assertThrows(ProtocolException.class, () -> Packet.screenInfo(8192, 4097));
        assertThrows(ProtocolException.class, () -> Packet.screenInfo(8192, 8192));
    }

    @Test
    void attachDecodeAndMagic() {
        byte[] tok = new byte[16];
        Arrays.fill(tok, (byte) 0xAB);
        Packet p = Packet.attach(tok);
        assertEquals(Protocol.VERSION, p.attachPayload().version());
        assertArrayEquals(tok, p.attachPayload().token());
        assertThrows(ProtocolException.class, () -> Packet.attach(new byte[15]));
        byte[] raw = p.payload();
        raw[3] ^= 0x40;
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.ATTACH, raw));
    }

    @Test
    void frameBounds() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8};
        Packet.FramePayload f = Packet.frame(1280, 720, jpeg).framePayload();
        assertEquals(1280, f.width());
        assertEquals(720, f.height());
        assertArrayEquals(jpeg, f.jpeg());
        Packet.frame(1, 1, new byte[] {1});
        Packet.frame(1280, 100000, jpeg); // height only needs >= 1 context-free
        assertThrows(ProtocolException.class, () -> Packet.frame(0, 10, jpeg));
        assertThrows(ProtocolException.class, () -> Packet.frame(1281, 10, jpeg));
        assertThrows(ProtocolException.class, () -> Packet.frame(10, 0, jpeg));
        assertThrows(ProtocolException.class, () -> Packet.frame(10, -5, jpeg));
        assertThrows(ProtocolException.class, () -> Packet.frame(10, 10, new byte[0]));
    }

    @Test
    void frameSizeLimit() {
        Packet.frame(10, 10, new byte[Protocol.IMG_MAX_LEN - 8]);
        assertThrows(ProtocolException.class,
                () -> Packet.frame(10, 10, new byte[Protocol.IMG_MAX_LEN - 7]));
    }

    @Test
    void pointerPackets() {
        Packet m = Packet.mouseMove(10, 20);
        assertEquals(10, m.pointerPayload().x());
        assertEquals(20, m.pointerPayload().y());
        assertEquals(3, Packet.mousePress(1, 2, 3).pointerPayload().button());
        assertEquals(PacketType.MOUSE_RELEASE, Packet.mouseRelease(0, 0, 1).type());
        assertThrows(ProtocolException.class, () -> Packet.mouseMove(-1, 0));
        assertThrows(ProtocolException.class, () -> Packet.mouseMove(0, -1));
        assertThrows(ProtocolException.class, () -> Packet.mousePress(0, 0, 0));
        assertThrows(ProtocolException.class, () -> Packet.mousePress(0, 0, 4));
        assertThrows(ProtocolException.class, () -> Packet.mouseRelease(0, 0, -1));
        assertThrows(ProtocolException.class, () -> Packet.mouseRelease(-1, 0, 1));
        // the upper bound is screen-dependent, so context-free validation accepts it
        Packet.mouseMove(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    void wheelBounds() {
        assertEquals(-20, Packet.wheel(-20).wheelNotches());
        assertEquals(20, Packet.wheel(20).wheelNotches());
        assertEquals(1, Packet.wheel(1).wheelNotches());
        assertThrows(ProtocolException.class, () -> Packet.wheel(0));
        assertThrows(ProtocolException.class, () -> Packet.wheel(21));
        assertThrows(ProtocolException.class, () -> Packet.wheel(-21));
        assertThrows(ProtocolException.class, () -> Packet.wheel(Integer.MIN_VALUE));
    }

    @Test
    void keyCodeIsPositiveInt32() {
        assertEquals(1, Packet.keyPress(1).keyCode());
        assertEquals(0x10000, Packet.keyRelease(0x10000).keyCode());
        assertEquals(Integer.MAX_VALUE, Packet.keyPress(Integer.MAX_VALUE).keyCode());
        assertThrows(ProtocolException.class, () -> Packet.keyPress(0));
        assertThrows(ProtocolException.class, () -> Packet.keyPress(-1));
        assertThrows(ProtocolException.class, () -> Packet.keyRelease(Integer.MIN_VALUE));
    }

    @Test
    void pingPongSeqAnyInt() {
        assertEquals(-5, Packet.ping(-5).seq());
        assertEquals(Integer.MAX_VALUE, Packet.pong(Integer.MAX_VALUE).seq());
    }

    @Test
    void emptyPackets() {
        assertEquals(0, Packet.sessionActive().payload().length);
        assertEquals(0, Packet.releaseAll().payload().length);
    }

    @Test
    void disconnectRoundTripAndTruncation() {
        Packet.DisconnectPayload d =
                Packet.disconnect(Packet.DisconnectReason.TIMEOUT, "xin chào").disconnectPayload();
        assertEquals(Packet.DisconnectReason.TIMEOUT, d.reason());
        assertEquals("xin chào", d.message());

        Packet t = Packet.disconnect(Packet.DisconnectReason.USER, "a".repeat(500));
        assertEquals(1 + Protocol.DISCONNECT_MSG_MAX, t.payload().length);

        // multi-byte chars must not be split at the truncation point
        Packet m = Packet.disconnect(Packet.DisconnectReason.USER, "é".repeat(150)); // 300 bytes
        assertEquals("é".repeat(100), m.disconnectPayload().message());

        assertEquals("",
                Packet.disconnect(Packet.DisconnectReason.USER, "").disconnectPayload().message());
    }

    @Test
    void disconnectUnknownReasonMapsToProtocolError() {
        Packet p = new Packet(PacketType.DISCONNECT, bytes(0x77, 'h', 'i'));
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, p.disconnectPayload().reason());
        assertEquals("hi", p.disconnectPayload().message());
        Packet p2 = new Packet(PacketType.DISCONNECT, bytes(0xFF));
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, p2.disconnectPayload().reason());
        assertEquals(0, Packet.DisconnectReason.USER.code());
        assertEquals(5, Packet.DisconnectReason.SHUTDOWN.code());
    }

    @Test
    void disconnectInvalidUtf8GivesEmptyMessage() {
        Packet p = new Packet(PacketType.DISCONNECT, bytes(0, 0xC3, 0x28));
        assertEquals("", p.disconnectPayload().message());
        Packet truncated = new Packet(PacketType.DISCONNECT, bytes(0, 0xE2, 0x82));
        assertEquals("", truncated.disconnectPayload().message());
    }

    @Test
    void disconnectControlCharsStripped() {
        byte[] msg = "a\u0000b\nc\u007Fd\u0085e".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[1 + msg.length];
        System.arraycopy(msg, 0, raw, 1, msg.length);
        Packet p = new Packet(PacketType.DISCONNECT, raw);
        assertEquals("abcde", p.disconnectPayload().message());
        assertEquals("abc", Packet.disconnect(Packet.DisconnectReason.USER, "a\tb\rc")
                .disconnectPayload().message());
    }

    @Test
    void disconnectLengthLimits() {
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.DISCONNECT, new byte[0]));
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.DISCONNECT, new byte[202]));
        new Packet(PacketType.DISCONNECT, new byte[201]);
    }

    @Test
    void constructorRejectsWrongFixedLength() {
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.PING, new byte[3]));
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.PING, new byte[5]));
        assertThrows(ProtocolException.class, () -> new Packet(PacketType.RELEASE_ALL, new byte[1]));
    }

    @Test
    void payloadIsDefensivelyCopiedOnConstruction() {
        byte[] src = {0, 0, 0, 7};
        Packet p = new Packet(PacketType.PING, src);
        src[3] = 99;
        assertEquals(7, p.seq());
    }

    @Test
    void payloadAccessorReturnsCopy() {
        Packet p = Packet.ping(7);
        byte[] out = p.payload();
        out[3] = 99;
        assertEquals(7, p.seq());
        assertArrayEquals(bytes(0, 0, 0, 7), p.payload());
    }

    @Test
    void factoryInputArraysAreCopied() {
        byte[] tok = new byte[16];
        Packet a = Packet.attach(tok);
        tok[0] = 5;
        assertEquals(0, a.attachPayload().token()[0]);
        byte[] jpeg = {1, 2, 3};
        Packet f = Packet.frame(10, 10, jpeg);
        jpeg[0] = 9;
        assertEquals(1, f.framePayload().jpeg()[0]);
    }

    @Test
    void wrongTypeAccessorThrows() {
        assertThrows(IllegalStateException.class, () -> Packet.ping(1).authPayload());
        assertThrows(IllegalStateException.class, () -> Packet.wheel(1).seq());
    }

    @Test
    void equalsAndHashCodeUseContent() {
        assertEquals(Packet.ping(3), Packet.ping(3));
        assertEquals(Packet.ping(3).hashCode(), Packet.ping(3).hashCode());
        assertNotEquals(Packet.ping(3), Packet.pong(3));
        assertNotEquals(Packet.ping(3), Packet.ping(4));
    }
}
