package com.ltm.rd.client;

/**
 * Test seams, both no-ops by default. {@code afterAttachFlushed} runs on the connect thread once
 * ATTACH was flushed, before the image channel is handed over; {@code beforeSenderStart} runs
 * right before the screen sender worker is started.
 */
public record ClientHooks(Runnable afterAttachFlushed, Runnable beforeSenderStart) {
    private static final Runnable NO_OP = () -> { };

    public ClientHooks {
        if (afterAttachFlushed == null) afterAttachFlushed = NO_OP;
        if (beforeSenderStart == null) beforeSenderStart = NO_OP;
    }

    public static ClientHooks none() {
        return new ClientHooks(null, null);
    }
}
