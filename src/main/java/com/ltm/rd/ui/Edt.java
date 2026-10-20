package com.ltm.rd.ui;

import javax.swing.SwingUtilities;

/** Guards for the thread rules: Swing state changes on the EDT, blocking work never. */
public final class Edt {
    private Edt() {}

    /** @throws IllegalStateException if the caller is not on the event dispatch thread */
    public static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("must run on the EDT, was " + Thread.currentThread().getName());
        }
    }

    /** @throws IllegalStateException if the caller is on the event dispatch thread */
    public static void requireNotEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("must not run on the EDT");
        }
    }
}
