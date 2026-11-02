package com.ltm.rd.client;

/** Test hook on the connect worker after ATTACH flush, before the image-channel handover. */
public record ClientHooks(Runnable afterAttachFlushed) {
    public ClientHooks {
        if (afterAttachFlushed == null) afterAttachFlushed = () -> { };
    }

    public static ClientHooks none() { return new ClientHooks(null); }
}
