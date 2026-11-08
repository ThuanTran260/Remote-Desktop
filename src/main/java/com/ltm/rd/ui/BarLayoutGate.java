package com.ltm.rd.ui;

import java.util.Objects;

/** Decides when the sharing bar must be re-packed: only when its text changes or it is not yet shown. */
final class BarLayoutGate {
    private String lastText;

    /** EDT. True when the caller must pack and relocate; records {@code text} as applied. */
    boolean needsLayout(String text, boolean visible) {
        boolean changed = !Objects.equals(lastText, text);
        lastText = text;
        return changed || !visible;
    }
}
