package com.ltm.rd.common;

import java.util.Optional;

/** Packet types with their wire code and allowed payload length range. */
public enum PacketType {
    AUTH(0x01, 11),
    AUTH_RESULT(0x02, 17),
    SCREEN_INFO(0x03, 8),
    ATTACH(0x04, 21),
    SESSION_ACTIVE(0x05, 0),
    FRAME(0x10, 9, Protocol.IMG_MAX_LEN),
    MOUSE_MOVE(0x20, 8),
    MOUSE_PRESS(0x21, 9),
    MOUSE_RELEASE(0x22, 9),
    WHEEL(0x23, 4),
    KEY_PRESS(0x24, 4),
    KEY_RELEASE(0x25, 4),
    RELEASE_ALL(0x26, 0),
    PING(0x30, 4),
    PONG(0x31, 4),
    DISCONNECT(0x3F, 1, 1 + Protocol.DISCONNECT_MSG_MAX);

    private static final PacketType[] BY_CODE = new PacketType[256];

    static {
        for (PacketType t : values()) BY_CODE[t.code] = t;
    }

    private final int code;
    private final int minLength;
    private final int maxLength;

    PacketType(int code, int fixedLength) {
        this(code, fixedLength, fixedLength);
    }

    PacketType(int code, int minLength, int maxLength) {
        this.code = code;
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public int code() {
        return code;
    }

    public int minLength() {
        return minLength;
    }

    public int maxLength() {
        return maxLength;
    }

    public boolean isFixed() {
        return minLength == maxLength;
    }

    /** Looks up a type by its wire byte; values outside 0..255 are unknown. */
    public static Optional<PacketType> fromCode(int code) {
        if (code < 0 || code > 255) return Optional.empty();
        return Optional.ofNullable(BY_CODE[code]);
    }
}
