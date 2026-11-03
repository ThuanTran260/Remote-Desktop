package com.ltm.rd.viewer;

/** Test hook on the connect worker after ATTACH flush, before the image-channel handover. */
public record ViewerHooks(Runnable afterAttachFlushed) {
    public ViewerHooks {
        if (afterAttachFlushed == null) afterAttachFlushed = () -> { };
    }

    public static ViewerHooks none() { return new ViewerHooks(null); }
}
