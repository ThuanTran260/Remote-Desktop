package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BarLayoutGateTest {
    @Test
    void firstCallNeedsLayout() {
        assertTrue(new BarLayoutGate().needsLayout("a", true));
    }

    @Test
    void sameTextWhileVisibleSkipsLayout() {
        BarLayoutGate gate = new BarLayoutGate();
        gate.needsLayout("a", false);
        assertFalse(gate.needsLayout("a", true));
        assertFalse(gate.needsLayout("a", true));
    }

    @Test
    void changedTextNeedsLayout() {
        BarLayoutGate gate = new BarLayoutGate();
        gate.needsLayout("a", true);
        assertTrue(gate.needsLayout("b", true));
    }

    @Test
    void sameTextAfterHideNeedsLayout() {
        BarLayoutGate gate = new BarLayoutGate();
        gate.needsLayout("a", true);
        assertTrue(gate.needsLayout("a", false));
    }
}
