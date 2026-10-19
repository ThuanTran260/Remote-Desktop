package com.ltm.rd.common;

/** Wire-protocol constants shared by Server and Client. */
public final class Protocol {
    public static final int CMD_PORT = 5001;
    public static final int IMG_PORT = 5000;
    public static final int MAGIC = 0x4C544D52; // "LTMR"
    public static final int VERSION = 1;

    public static final int CMD_MAX_LEN = 1024;
    public static final int IMG_MAX_LEN = 8 * 1024 * 1024;

    public static final int MAX_SCREEN_DIM = 8192;
    public static final long MAX_SCREEN_PIXELS = 33_554_432L;
    public static final int MAX_FRAME_WIDTH = 1280;
    public static final int MAX_WHEEL_NOTCHES = 20;
    public static final int DISCONNECT_MSG_MAX = 200;

    public static final int TOKEN_LEN = 16;
    public static final int CODE_LEN = 6;

    private Protocol() {}
}
