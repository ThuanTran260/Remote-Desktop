package com.ltm.rd.common;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * An immutable, already context-free-validated packet. The canonical constructor is the single
 * validation path: factories and {@link PacketIO#read} both build packets through it, so the
 * serializer and the parser enforce identical limits.
 */
public record Packet(PacketType type, byte[] payload) {

    public enum AuthStatus {
        OK(0), WRONG_CODE(1), BUSY(2), LOCKED(3), BAD_VERSION(4);

        private final int code;

        AuthStatus(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        static AuthStatus fromCode(int code) {
            for (AuthStatus s : values()) if (s.code == code) return s;
            throw new ProtocolException("unknown AUTH_RESULT status " + code);
        }
    }

    public enum DisconnectReason {
        USER(0), TIMEOUT(1), PROTOCOL_ERROR(2), SCREEN_CHANGED(3), OVERFLOW(4), SHUTDOWN(5);

        private final int code;

        DisconnectReason(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        /** Unknown codes map to PROTOCOL_ERROR; the session closes anyway. */
        static DisconnectReason fromCode(int code) {
            for (DisconnectReason r : values()) if (r.code == code) return r;
            return PROTOCOL_ERROR;
        }
    }

    public record AuthPayload(int version, String code) {}

    public record AuthResultPayload(AuthStatus status, byte[] token) {}

    public record AttachPayload(int version, byte[] token) {}

    /** Width/height pair, used by SCREEN_INFO. */
    public record SizePayload(int width, int height) {}

    public record FramePayload(int width, int height, byte[] jpeg) {}

    /** Pointer position; {@code button} is 0 for MOUSE_MOVE, 1..3 for press/release. */
    public record PointerPayload(int x, int y, int button) {}

    public record DisconnectPayload(DisconnectReason reason, String message) {}

    public Packet {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        payload = payload.clone();
        validate(type, payload);
    }

    /** Returns a copy; callers can never mutate the packet through it. */
    @Override
    public byte[] payload() {
        return payload.clone();
    }

    /** Internal view for PacketIO.write; must never be modified or leaked. */
    byte[] rawPayload() {
        return payload;
    }

    // ---- factories -------------------------------------------------------------------------

    public static Packet auth(String code) {
        return auth(Protocol.VERSION, code);
    }

    /** Allows a non-current version so a server's BAD_VERSION path can be exercised. */
    public static Packet auth(int version, String code) {
        Objects.requireNonNull(code, "code");
        byte[] ascii = code.getBytes(StandardCharsets.ISO_8859_1);
        if (ascii.length != code.length()) throw new ProtocolException("AUTH code is not ASCII");
        ByteBuffer b = ByteBuffer.allocate(5 + ascii.length);
        b.putInt(Protocol.MAGIC).put((byte) version).put(ascii);
        return new Packet(PacketType.AUTH, b.array());
    }

    public static Packet authResult(AuthStatus status, byte[] token) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(token, "token");
        ByteBuffer b = ByteBuffer.allocate(1 + token.length);
        b.put((byte) status.code).put(token);
        return new Packet(PacketType.AUTH_RESULT, b.array());
    }

    public static Packet screenInfo(int width, int height) {
        return new Packet(PacketType.SCREEN_INFO, ints(width, height));
    }

    public static Packet attach(byte[] token) {
        Objects.requireNonNull(token, "token");
        ByteBuffer b = ByteBuffer.allocate(5 + token.length);
        b.putInt(Protocol.MAGIC).put((byte) Protocol.VERSION).put(token);
        return new Packet(PacketType.ATTACH, b.array());
    }

    public static Packet sessionActive() {
        return new Packet(PacketType.SESSION_ACTIVE, new byte[0]);
    }

    public static Packet frame(int width, int height, byte[] jpeg) {
        Objects.requireNonNull(jpeg, "jpeg");
        if (jpeg.length > Protocol.IMG_MAX_LEN - 8) throw new ProtocolException("FRAME too large");
        ByteBuffer b = ByteBuffer.allocate(8 + jpeg.length);
        b.putInt(width).putInt(height).put(jpeg);
        return new Packet(PacketType.FRAME, b.array());
    }

    public static Packet mouseMove(int x, int y) {
        return new Packet(PacketType.MOUSE_MOVE, ints(x, y));
    }

    public static Packet mousePress(int x, int y, int button) {
        return pointerWithButton(PacketType.MOUSE_PRESS, x, y, button);
    }

    public static Packet mouseRelease(int x, int y, int button) {
        return pointerWithButton(PacketType.MOUSE_RELEASE, x, y, button);
    }

    public static Packet wheel(int notches) {
        return new Packet(PacketType.WHEEL, ints(notches));
    }

    public static Packet keyPress(int keyCode) {
        return new Packet(PacketType.KEY_PRESS, ints(keyCode));
    }

    public static Packet keyRelease(int keyCode) {
        return new Packet(PacketType.KEY_RELEASE, ints(keyCode));
    }

    public static Packet releaseAll() {
        return new Packet(PacketType.RELEASE_ALL, new byte[0]);
    }

    public static Packet ping(int seq) {
        return new Packet(PacketType.PING, ints(seq));
    }

    public static Packet pong(int seq) {
        return new Packet(PacketType.PONG, ints(seq));
    }

    /**
     * Control characters are stripped and the message is cut to DISCONNECT_MSG_MAX UTF-8 bytes
     * on a code point boundary.
     */
    public static Packet disconnect(DisconnectReason reason, String message) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < message.length(); ) {
            int cp = message.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || (cp < 0x10000 && Character.isSurrogate((char) cp))) {
                continue;
            }
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > Protocol.DISCONNECT_MSG_MAX) break;
            bytes += len;
            sb.appendCodePoint(cp);
        }
        byte[] msg = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[1 + msg.length];
        raw[0] = (byte) reason.code;
        System.arraycopy(msg, 0, raw, 1, msg.length);
        return new Packet(PacketType.DISCONNECT, raw);
    }

    private static Packet pointerWithButton(PacketType t, int x, int y, int button) {
        ByteBuffer b = ByteBuffer.allocate(9);
        b.putInt(x).putInt(y).put((byte) button);
        // (byte) cast would alias e.g. 257 to 1, so range-check the int first
        if (button < 1 || button > 3) throw new ProtocolException("button " + button + " not in 1..3");
        return new Packet(t, b.array());
    }

    private static byte[] ints(int... v) {
        ByteBuffer b = ByteBuffer.allocate(4 * v.length);
        for (int i : v) b.putInt(i);
        return b.array();
    }

    // ---- typed decoders --------------------------------------------------------------------

    public AuthPayload authPayload() {
        expect(PacketType.AUTH);
        ByteBuffer b = ByteBuffer.wrap(payload);
        b.getInt();
        int version = b.get() & 0xFF;
        byte[] code = new byte[Protocol.CODE_LEN];
        b.get(code);
        return new AuthPayload(version, new String(code, StandardCharsets.US_ASCII));
    }

    public AuthResultPayload authResultPayload() {
        expect(PacketType.AUTH_RESULT);
        return new AuthResultPayload(AuthStatus.fromCode(payload[0] & 0xFF),
                Arrays.copyOfRange(payload, 1, 17));
    }

    public AttachPayload attachPayload() {
        expect(PacketType.ATTACH);
        return new AttachPayload(payload[4] & 0xFF, Arrays.copyOfRange(payload, 5, 21));
    }

    public SizePayload sizePayload() {
        expect(PacketType.SCREEN_INFO);
        ByteBuffer b = ByteBuffer.wrap(payload);
        return new SizePayload(b.getInt(), b.getInt());
    }

    public FramePayload framePayload() {
        expect(PacketType.FRAME);
        ByteBuffer b = ByteBuffer.wrap(payload);
        return new FramePayload(b.getInt(), b.getInt(), Arrays.copyOfRange(payload, 8, payload.length));
    }

    public PointerPayload pointerPayload() {
        if (type != PacketType.MOUSE_MOVE && type != PacketType.MOUSE_PRESS
                && type != PacketType.MOUSE_RELEASE) {
            throw new IllegalStateException("not a pointer packet: " + type);
        }
        ByteBuffer b = ByteBuffer.wrap(payload);
        int x = b.getInt();
        int y = b.getInt();
        return new PointerPayload(x, y, b.hasRemaining() ? b.get() & 0xFF : 0);
    }

    public int wheelNotches() {
        expect(PacketType.WHEEL);
        return ByteBuffer.wrap(payload).getInt();
    }

    public int keyCode() {
        if (type != PacketType.KEY_PRESS && type != PacketType.KEY_RELEASE) {
            throw new IllegalStateException("not a key packet: " + type);
        }
        return ByteBuffer.wrap(payload).getInt();
    }

    public int seq() {
        if (type != PacketType.PING && type != PacketType.PONG) {
            throw new IllegalStateException("not a ping/pong packet: " + type);
        }
        return ByteBuffer.wrap(payload).getInt();
    }

    /** Invalid UTF-8 yields an empty message; control characters are stripped. */
    public DisconnectPayload disconnectPayload() {
        expect(PacketType.DISCONNECT);
        DisconnectReason reason = DisconnectReason.fromCode(payload[0] & 0xFF);
        String message;
        try {
            message = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload, 1, payload.length - 1))
                    .toString();
        } catch (CharacterCodingException e) {
            message = "";
        }
        StringBuilder sb = new StringBuilder(message.length());
        message.codePoints().filter(cp -> !Character.isISOControl(cp)).forEach(sb::appendCodePoint);
        return new DisconnectPayload(reason, sb.toString());
    }

    private void expect(PacketType expected) {
        if (type != expected) throw new IllegalStateException("expected " + expected + " but was " + type);
    }

    // ---- validation (single path for factories and parser) ---------------------------------

    private static void validate(PacketType type, byte[] p) {
        if (p.length < type.minLength() || p.length > type.maxLength()) {
            throw new ProtocolException(type + " payload length " + p.length + " not in "
                    + type.minLength() + ".." + type.maxLength());
        }
        ByteBuffer b = ByteBuffer.wrap(p);
        switch (type) {
            case AUTH -> {
                checkMagic(b.getInt(), type);
                b.get(); // version is decoded by the caller; a mismatch is not a protocol error
                for (int i = 0; i < Protocol.CODE_LEN; i++) {
                    byte c = b.get();
                    if (c < '0' || c > '9') throw new ProtocolException("AUTH code must be digits");
                }
            }
            case AUTH_RESULT -> {
                int status = b.get() & 0xFF;
                AuthStatus s = AuthStatus.fromCode(status);
                if (s != AuthStatus.OK) {
                    for (int i = 1; i < p.length; i++) {
                        if (p[i] != 0) throw new ProtocolException("non-OK AUTH_RESULT needs zero token");
                    }
                }
            }
            case SCREEN_INFO -> {
                int w = b.getInt();
                int h = b.getInt();
                if (w < 1 || w > Protocol.MAX_SCREEN_DIM || h < 1 || h > Protocol.MAX_SCREEN_DIM
                        || (long) w * h > Protocol.MAX_SCREEN_PIXELS) {
                    throw new ProtocolException("SCREEN_INFO " + w + "x" + h + " out of range");
                }
            }
            case ATTACH -> checkMagic(b.getInt(), type);
            case FRAME -> {
                int w = b.getInt();
                int h = b.getInt();
                if (w < 1 || w > Protocol.MAX_FRAME_WIDTH || h < 1) {
                    throw new ProtocolException("FRAME " + w + "x" + h + " out of range");
                }
            }
            case MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE -> {
                int x = b.getInt();
                int y = b.getInt();
                if (x < 0 || y < 0) throw new ProtocolException("negative pointer coordinate");
                if (type != PacketType.MOUSE_MOVE) {
                    int button = b.get() & 0xFF;
                    if (button < 1 || button > 3) throw new ProtocolException("button " + button);
                }
            }
            case WHEEL -> {
                int n = b.getInt();
                if (n == 0 || n < -Protocol.MAX_WHEEL_NOTCHES || n > Protocol.MAX_WHEEL_NOTCHES) {
                    throw new ProtocolException("wheel notches " + n);
                }
            }
            case KEY_PRESS, KEY_RELEASE -> {
                if (b.getInt() <= 0) throw new ProtocolException("key code must be positive");
            }
            default -> { } // SESSION_ACTIVE, RELEASE_ALL, PING, PONG, DISCONNECT: length check only
        }
    }

    private static void checkMagic(int magic, PacketType type) {
        if (magic != Protocol.MAGIC) throw new ProtocolException(type + " bad magic");
    }

    // ---- value semantics for the array component -------------------------------------------

    @Override
    public boolean equals(Object o) {
        return o instanceof Packet other && type == other.type && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "Packet[" + type + ", " + payload.length + " bytes]";
    }
}
