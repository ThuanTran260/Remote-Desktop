package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Keyboard and focus-loss rules of InputCapture with a recording sink; events are delivered on the EDT. */
@Timeout(60)
class InputCaptureKeyboardTest {

    private static final class Fixture {
        final FakeController sink = new FakeController();
        final RemotePanel panel = new RemotePanel();
        final InputCapture capture;
        volatile boolean focus = true;

        Fixture(RepeatPolicy policy, boolean applySnapshot) throws Exception {
            panel.setSize(960, 540);
            capture = new InputCapture(sink, panel, () -> focus, () -> { }, policy);
            if (applySnapshot) apply(true);
        }

        Fixture() throws Exception {
            this(RepeatPolicy.SEND_ALL, true);
        }

        void apply(boolean controlEnabled) throws Exception {
            edt(() -> {
                panel.apply(new Snapshot(LaunchOptions.Role.SERVER, UiPhase.ACTIVE, 7, 3, "", "10.0.0.2", 5001, 5000,
                        "123456", 1920, 1080, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), 1, controlEnabled,
                        TrafficStats.ZERO));
                capture.onApplied(7, 3);
            });
        }

        KeyEvent press(int code) throws Exception {
            KeyEvent e = new KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
            edt(() -> capture.keyPressed(e));
            return e;
        }

        KeyEvent release(int code) throws Exception {
            KeyEvent e = new KeyEvent(panel, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
            edt(() -> capture.keyReleased(e));
            return e;
        }

        void lost() throws Exception {
            edt(() -> capture.focusLost(new FocusEvent(panel, FocusEvent.FOCUS_LOST)));
        }

        void gained() throws Exception {
            edt(() -> capture.focusGained(new FocusEvent(panel, FocusEvent.FOCUS_GAINED)));
        }

        int heldKeys() throws Exception {
            return edt(() -> capture.heldKeyCount());
        }

        int heldButtons() throws Exception {
            return edt(() -> capture.heldButtonCount());
        }

        double acc() throws Exception {
            return edt(() -> capture.wheelAccumulator());
        }

        void mousePress(int button) throws Exception {
            MouseEvent e = new MouseEvent(panel, MouseEvent.MOUSE_PRESSED, 0, InputEvent.BUTTON1_DOWN_MASK, 480, 270, 1,
                    false, button);
            edt(() -> capture.mousePressed(e));
        }
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T edt(Supplier<T> s) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(s.get()));
        return out.get();
    }

    @Test
    void shiftAndCtrlHeldAreSentThenReleased() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_SHIFT);
        f.press(KeyEvent.VK_CONTROL);
        f.press(KeyEvent.VK_A);
        f.release(KeyEvent.VK_A);
        f.release(KeyEvent.VK_CONTROL);
        f.release(KeyEvent.VK_SHIFT);
        assertEquals(List.of("keyPress 16", "keyPress 17", "keyPress 65", "keyRelease 65", "keyRelease 17",
                "keyRelease 16"), f.sink.described());
        for (FakeController.Sent s : f.sink.sent()) {
            assertEquals(7, s.generation());
            assertEquals(3, s.inputEpoch());
        }
        assertEquals(0, f.heldKeys());
    }

    @Test
    void backspaceAndTabAreForwardedAndConsumed() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.press(KeyEvent.VK_BACK_SPACE).isConsumed());
        assertTrue(f.press(KeyEvent.VK_TAB).isConsumed());
        assertEquals(List.of("keyPress 8", "keyPress 9"), f.sink.described());
        assertFalse(f.panel.getFocusTraversalKeysEnabled(), "Tab must reach the key listener");
        assertTrue(f.panel.isFocusable());
    }

    @Test
    void keyTypedIsNeverSent() throws Exception {
        Fixture f = new Fixture();
        KeyEvent typed = new KeyEvent(f.panel, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, 'a');
        edt(() -> f.capture.keyTyped(typed));
        assertEquals(List.of(), f.sink.described());
    }

    @Test
    void undefinedKeyIsIgnored() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_UNDEFINED);
        assertEquals(List.of(), f.sink.described());
        assertEquals(0, f.heldKeys());
    }

    @Test
    void sendAllForwardsRepeats() throws Exception {
        Fixture f = new Fixture(RepeatPolicy.SEND_ALL, true);
        f.press(KeyEvent.VK_BACK_SPACE);
        f.press(KeyEvent.VK_BACK_SPACE);
        f.press(KeyEvent.VK_BACK_SPACE);
        assertEquals(List.of("keyPress 8", "keyPress 8", "keyPress 8"), f.sink.described());
        assertEquals(1, f.heldKeys());
    }

    @Test
    void firstPressOnlyForwardsOnce() throws Exception {
        Fixture f = new Fixture(RepeatPolicy.FIRST_PRESS_ONLY, true);
        assertTrue(f.press(KeyEvent.VK_BACK_SPACE).isConsumed());
        assertTrue(f.press(KeyEvent.VK_BACK_SPACE).isConsumed(), "a swallowed repeat is still consumed");
        f.press(KeyEvent.VK_BACK_SPACE);
        assertEquals(List.of("keyPress 8"), f.sink.described());
        f.release(KeyEvent.VK_BACK_SPACE);
        assertEquals(List.of("keyPress 8", "keyRelease 8"), f.sink.described());
        f.press(KeyEvent.VK_BACK_SPACE); // a new hold sends its first press again
        assertEquals(3, f.sink.described().size());
    }

    @Test
    void defaultRepeatPolicyIsTheNamedConstant() throws Exception {
        assertEquals(RepeatPolicy.SEND_ALL, InputCapture.DEFAULT_REPEAT_POLICY);
        RemotePanel panel = new RemotePanel();
        FakeController sink = new FakeController();
        InputCapture capture = new InputCapture(sink, panel, () -> true, () -> { });
        assertNotNull(capture);
    }

    @Test
    void releaseWithoutHeldIsIgnored() throws Exception {
        Fixture f = new Fixture();
        f.release(KeyEvent.VK_A);
        assertEquals(List.of(), f.sink.described());
    }

    @Test
    void rejectedPressIsNotHeld() throws Exception {
        Fixture f = new Fixture();
        f.sink.answer(SendResult.GATE_CLOSED);
        f.press(KeyEvent.VK_A);
        assertEquals(0, f.heldKeys());
        f.sink.answer(SendResult.SENT);
        f.release(KeyEvent.VK_A);
        assertEquals(List.of("keyPress 65"), f.sink.described());
    }

    @Test
    void lateReleaseAfterFocusLossIsIgnored() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_CONTROL);
        f.lost();
        assertEquals(List.of(7L), f.sink.releases());
        f.release(KeyEvent.VK_CONTROL);
        assertEquals(List.of("keyPress 17"), f.sink.described());
    }

    @Test
    void focusLossSendsReleaseOnceAndClearsState() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_CONTROL);
        f.mousePress(1);
        edt(() -> f.capture.mouseWheelMoved(new java.awt.event.MouseWheelEvent(f.panel, MouseEvent.MOUSE_WHEEL, 0, 0, 10,
                10, 10, 10, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1, 0.5)));
        assertEquals(1, f.heldKeys());
        assertEquals(1, f.heldButtons());
        assertEquals(0.5, f.acc());

        f.lost();
        assertEquals(List.of(7L), f.sink.releases());
        assertEquals(0, f.heldKeys());
        assertEquals(0, f.heldButtons());
        assertEquals(0.0, f.acc());
        assertFalse(edt(() -> f.capture.acceptingInput()));
    }

    @Test
    void duplicateDeactivationNotificationsAreCoalesced() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_CONTROL);
        f.lost();
        edt(() -> f.capture.windowDeactivated());
        edt(() -> f.capture.disconnecting());
        assertEquals(List.of(7L), f.sink.releases());
    }

    @Test
    void eventsIgnoredUntilFocusGained() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_CONTROL);
        f.lost();
        f.press(KeyEvent.VK_A);
        assertEquals(List.of("keyPress 17"), f.sink.described(), "no new key while not accepting");
        f.gained();
        assertTrue(edt(() -> f.capture.acceptingInput()));
        f.press(KeyEvent.VK_A);
        f.release(KeyEvent.VK_CONTROL); // belongs to the key held before the loss
        assertEquals(List.of("keyPress 17", "keyPress 65"), f.sink.described());
    }

    @Test
    void focusRegainDoesNotRestoreOldHeldKeys() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_CONTROL);
        f.lost();
        f.gained();
        f.release(KeyEvent.VK_CONTROL);
        assertEquals(List.of("keyPress 17"), f.sink.described());
        assertEquals(0, f.heldKeys());
    }

    @Test
    void secondLossAfterRegainRequestsAgain() throws Exception {
        Fixture f = new Fixture();
        f.lost();
        f.gained();
        f.lost();
        assertEquals(List.of(7L, 7L), f.sink.releases());
    }

    @Test
    void altTabSequence() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_ALT);
        assertEquals(List.of("keyPress 18"), f.sink.described());
        edt(() -> f.capture.windowDeactivated());
        assertEquals(List.of(7L), f.sink.releases());
        f.release(KeyEvent.VK_ALT);
        assertEquals(List.of("keyPress 18"), f.sink.described());
    }

    @Test
    void releaseRequestedEvenWhenGateIsClosed() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_CONTROL);
        f.apply(false); // a stall: the same generation and epoch, control disabled
        f.lost();
        assertEquals(List.of(7L), f.sink.releases());
    }

    @Test
    void noKeysWithoutFocusOwnerOrGate() throws Exception {
        Fixture f = new Fixture();
        f.focus = false;
        assertFalse(f.press(KeyEvent.VK_A).isConsumed());
        assertEquals(List.of(), f.sink.described());
        Fixture noGate = new Fixture(RepeatPolicy.SEND_ALL, false);
        noGate.press(KeyEvent.VK_A);
        assertEquals(List.of(), noGate.sink.described());
        Fixture stalled = new Fixture();
        stalled.apply(false);
        stalled.press(KeyEvent.VK_A);
        assertEquals(List.of(), stalled.sink.described());
    }

    @Test
    void epochChangeClearsHeldKeys() throws Exception {
        Fixture f = new Fixture();
        f.press(KeyEvent.VK_CONTROL);
        assertEquals(1, f.heldKeys());
        edt(() -> f.capture.onApplied(7, 4));
        assertEquals(0, f.heldKeys());
        f.release(KeyEvent.VK_CONTROL);
        assertEquals(List.of("keyPress 17"), f.sink.described());
    }
}
