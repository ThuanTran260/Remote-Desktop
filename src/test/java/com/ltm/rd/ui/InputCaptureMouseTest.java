package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Mouse rules of InputCapture with a recording sink; every event is delivered on the EDT. */
@Timeout(60)
class InputCaptureMouseTest {
    private static final int B1 = MouseEvent.BUTTON1;
    private static final int B2 = MouseEvent.BUTTON2;
    private static final int B3 = MouseEvent.BUTTON3;

    private static final class Fixture {
        final FakeController sink = new FakeController();
        final RemotePanel panel;
        final InputCapture capture;
        volatile boolean focus = true;
        final AtomicInteger focusRequests = new AtomicInteger();

        Fixture(int pw, int ph, int rw, int rh, boolean applySnapshot) throws Exception {
            panel = new RemotePanel();
            panel.setSize(pw, ph);
            capture = new InputCapture(sink, panel, () -> focus, focusRequests::incrementAndGet);
            if (applySnapshot) {
                edt(() -> {
                    panel.apply(new Snapshot(LaunchOptions.Role.SERVER, UiPhase.ACTIVE, 7, 3, "", "10.0.0.2", 5001, 5000,
                            "123456", rw, rh, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), 1, true,
                            TrafficStats.ZERO));
                    capture.onApplied(7, 3);
                });
            }
        }

        Fixture() throws Exception {
            this(960, 540, 1920, 1080, true);
        }

        void press(int x, int y, int button) throws Exception {
            edt(() -> capture.mousePressed(event(MouseEvent.MOUSE_PRESSED, x, y, button, down(button))));
        }

        void release(int x, int y, int button) throws Exception {
            edt(() -> capture.mouseReleased(event(MouseEvent.MOUSE_RELEASED, x, y, button, 0)));
        }

        void drag(int x, int y, int... buttonsDown) throws Exception {
            int mods = 0;
            for (int b : buttonsDown) mods |= down(b);
            int m = mods;
            edt(() -> capture.mouseDragged(event(MouseEvent.MOUSE_DRAGGED, x, y, 0, m)));
        }

        void move(int x, int y) throws Exception {
            edt(() -> capture.mouseMoved(event(MouseEvent.MOUSE_MOVED, x, y, 0, 0)));
        }

        void wheel(double precise) throws Exception {
            edt(() -> capture.mouseWheelMoved(new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, 0, 0, 10, 10, 10, 10, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (int) Math.signum(precise) * Math.max(1, (int) Math.abs(precise)),
                    precise)));
        }

        int held() throws Exception {
            return edt(() -> capture.heldButtonCount());
        }

        double acc() throws Exception {
            return edt(() -> capture.wheelAccumulator());
        }

        MouseEvent event(int id, int x, int y, int button, int modifiersEx) {
            return new MouseEvent(panel, id, 0, modifiersEx, x, y, 1, false, button);
        }
    }

    private static int down(int button) {
        return switch (button) {
            case MouseEvent.BUTTON1 -> InputEvent.BUTTON1_DOWN_MASK;
            case MouseEvent.BUTTON2 -> InputEvent.BUTTON2_DOWN_MASK;
            case MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK;
            default -> 0;
        };
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static <T> T edt(Supplier<T> s) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(s.get()));
        return out.get();
    }

    // ---- focus and swallow ---------------------------------------------------------------------

    @Test
    void firstClickWithoutFocusOnlyRequestsFocus() throws Exception {
        Fixture f = new Fixture();
        f.focus = false;
        f.press(480, 270, B1);
        assertEquals(1, f.focusRequests.get());
        assertEquals(List.of(), f.sink.described());
        f.focus = true; // the focus arrived while the button is still down
        f.drag(500, 280, B1);
        f.release(500, 280, B1);
        assertEquals(List.of(), f.sink.described(), "the swallowed press, drag and release send nothing");
        assertEquals(0, f.held());
    }

    @Test
    void swallowIsPerButton() throws Exception {
        Fixture f = new Fixture();
        f.focus = false;
        f.press(480, 270, B1);
        f.focus = true;
        f.press(480, 270, B3);
        assertEquals(List.of("press 960,540,3"), f.sink.described());
        f.release(480, 270, B1);
        assertEquals(List.of("press 960,540,3"), f.sink.described(), "b1 release is swallowed");
        f.release(480, 270, B3);
        assertEquals(List.of("press 960,540,3", "release 960,540,3"), f.sink.described());
    }

    @Test
    void newPressClearsStaleSwallowOfSameButton() throws Exception {
        Fixture f = new Fixture();
        f.focus = false;
        f.press(480, 270, B1); // swallowed, its release never arrives
        f.focus = true;
        f.press(480, 270, B1);
        assertEquals(List.of("press 960,540,1"), f.sink.described());
        assertEquals(1, f.held());
    }

    @Test
    void dragWithOnlySwallowedButtonsDownIsDropped() throws Exception {
        Fixture f = new Fixture();
        f.focus = false;
        f.press(480, 270, B1);
        f.focus = true;
        f.drag(490, 280, B1);
        assertEquals(List.of(), f.sink.described());
        f.press(480, 270, B3); // b3 is a real press now
        f.drag(490, 280, B1, B3);
        assertEquals(List.of("press 960,540,3", "move 980,560"), f.sink.described());
    }

    // ---- press and gate ------------------------------------------------------------------------

    @Test
    void pressUsesEventCoordinates() throws Exception {
        Fixture f = new Fixture();
        f.press(480, 270, B1);
        assertEquals(List.of("press 960,540,1"), f.sink.described());
        FakeController.Sent s = f.sink.sent().get(0);
        assertEquals(7, s.generation());
        assertEquals(3, s.inputEpoch());
        assertEquals(1, f.held());
    }

    @Test
    void pressOutsideImageIsIgnored() throws Exception {
        Fixture f = new Fixture(1000, 400, 1600, 900, true);
        f.press(143, 10, B1);
        assertEquals(List.of(), f.sink.described());
        assertEquals(0, f.held());
    }

    @Test
    void pressRejectedByGateIsNotHeld() throws Exception {
        Fixture f = new Fixture();
        f.sink.answer(SendResult.GATE_CLOSED);
        f.press(480, 270, B1);
        assertEquals(0, f.held());
        f.sink.answer(SendResult.SENT);
        f.release(480, 270, B1);
        assertEquals(List.of("press 960,540,1"), f.sink.described(), "no release for a press that was not accepted");
    }

    @Test
    void stalePressResultsNeverMarkHeld() throws Exception {
        Fixture f = new Fixture();
        f.sink.answer(SendResult.STALE_EPOCH);
        f.press(480, 270, B1);
        f.sink.answer(SendResult.STALE_GENERATION);
        f.press(480, 270, B2);
        assertEquals(0, f.held());
    }

    @Test
    void buttonsOutsideOneToThreeAreIgnored() throws Exception {
        Fixture f = new Fixture();
        // button 4 cannot be constructed headless (the constructor asks the display), so the event reports it
        MouseEvent fourth = new MouseEvent(f.panel, MouseEvent.MOUSE_PRESSED, 0, 0, 480, 270, 1, false) {
            @Override
            public int getButton() {
                return 4;
            }
        };
        edt(() -> f.capture.mousePressed(fourth));
        assertEquals(List.of(), f.sink.described());
        assertEquals(0, f.held());
    }

    @Test
    void eventsIgnoredWithoutGate() throws Exception {
        Fixture f = new Fixture(960, 540, 1920, 1080, false);
        f.press(480, 270, B1);
        f.move(480, 270);
        f.wheel(3.0);
        assertEquals(List.of(), f.sink.described());
        f.focus = false;
        f.press(480, 270, B1);
        assertEquals(1, f.focusRequests.get(), "an unfocused press still takes focus");
    }

    // ---- move, drag, release ---------------------------------------------------------------------

    @Test
    void dragOutsideImageClampsAtEdge() throws Exception {
        Fixture f = new Fixture(1000, 400, 1600, 900, true);
        f.press(500, 200, B1);
        f.drag(143, 10, B1);
        assertEquals("move 0,22", f.sink.described().get(1));
    }

    @Test
    void moveOutsideImageWithoutButtonIsIgnored() throws Exception {
        Fixture f = new Fixture(1000, 400, 1600, 900, true);
        f.move(143, 10);
        assertEquals(List.of(), f.sink.described());
        f.move(500, 200);
        assertEquals(1, f.sink.described().size());
    }

    @Test
    void releaseOutsideImageIsStillSentClamped() throws Exception {
        Fixture f = new Fixture(1000, 400, 1600, 900, true);
        f.press(500, 200, B1);
        f.release(900, 10, B1);
        assertEquals("release 1599,22,1", f.sink.described().get(1));
        assertEquals(0, f.held());
    }

    @Test
    void multipleButtonsHeldIndependently() throws Exception {
        Fixture f = new Fixture();
        f.press(480, 270, B1);
        f.press(480, 270, B2);
        assertEquals(2, f.held());
        f.release(480, 270, B1);
        assertEquals(1, f.held());
        f.release(480, 270, B2);
        assertEquals(0, f.held());
        assertEquals(List.of("press 960,540,1", "press 960,540,2", "release 960,540,1", "release 960,540,2"),
                f.sink.described());
    }

    // ---- wheel -----------------------------------------------------------------------------------

    @Test
    void wheelFractionAccumulates() throws Exception {
        Fixture f = new Fixture();
        f.wheel(0.5);
        assertEquals(List.of(), f.sink.described());
        f.wheel(0.5);
        assertEquals(List.of("wheel 1"), f.sink.described());
        assertEquals(0.0, f.acc());
        f.wheel(-0.5);
        f.wheel(-0.5);
        assertEquals(List.of("wheel 1", "wheel -1"), f.sink.described());
        f.wheel(-0.5);
        assertEquals(List.of("wheel 1", "wheel -1"), f.sink.described());
        assertEquals(-0.5, f.acc());
    }

    @Test
    void wheelSplitsIntoPacketsOfTwenty() throws Exception {
        Fixture f = new Fixture();
        f.wheel(45.0);
        assertEquals(List.of("wheel 20", "wheel 20", "wheel 5"), f.sink.described());
        f.wheel(-45.0);
        assertEquals(List.of("wheel 20", "wheel 20", "wheel 5", "wheel -20", "wheel -20", "wheel -5"),
                f.sink.described());
    }

    @Test
    void wheelCapsAtThreePacketsAndDropsExcessInteger() throws Exception {
        Fixture f = new Fixture();
        f.wheel(100.0);
        assertEquals(List.of("wheel 20", "wheel 20", "wheel 20"), f.sink.described());
        assertEquals(0.0, f.acc());
        f.wheel(61.5);
        assertEquals(6, f.sink.described().size());
        assertEquals(List.of("wheel 20", "wheel 20", "wheel 20"), f.sink.described().subList(3, 6));
        assertEquals(0.5, f.acc());
    }

    @Test
    void wheelRequiresFocusAndGate() throws Exception {
        Fixture f = new Fixture();
        f.focus = false;
        f.wheel(3.0);
        assertEquals(List.of(), f.sink.described());
        assertEquals(0.0, f.acc(), "nothing accumulates while input cannot be sent");
        Fixture noGate = new Fixture(960, 540, 1920, 1080, false);
        noGate.wheel(3.0);
        assertEquals(List.of(), noGate.sink.described());
    }

    // ---- held state follows (generation, epoch) -------------------------------------------------

    @Test
    void epochChangeClearsHeldAndWheelAcc() throws Exception {
        Fixture f = new Fixture();
        f.press(480, 270, B1);
        f.wheel(0.5);
        assertEquals(1, f.held());
        assertEquals(0.5, f.acc());
        edt(() -> f.capture.onApplied(7, 4));
        assertEquals(0, f.held());
        assertEquals(0.0, f.acc());
        f.release(480, 270, B1);
        assertEquals(List.of("press 960,540,1"), f.sink.described(), "a late release after the reset sends nothing");
    }

    @Test
    void generationChangeClearsHeld() throws Exception {
        Fixture f = new Fixture();
        f.press(480, 270, B1);
        edt(() -> f.capture.onApplied(8, 0));
        assertEquals(0, f.held());
    }

    @Test
    void sameGenerationAndEpochKeepsHeld() throws Exception {
        Fixture f = new Fixture();
        f.press(480, 270, B1);
        edt(() -> f.capture.onApplied(7, 3));
        assertEquals(1, f.held());
    }

    @Test
    void releaseWhileGateEmptyOnlyClearsHeld() throws Exception {
        Fixture f = new Fixture();
        f.press(480, 270, B1);
        // a stall: the same generation, a newer epoch, control disabled
        edt(() -> f.panel.apply(new Snapshot(LaunchOptions.Role.SERVER, UiPhase.ACTIVE, 7, 3, "", "10.0.0.2", 5001, 5000,
                "123456", 1920, 1080, new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), 1, false,
                TrafficStats.ZERO)));
        f.release(480, 270, B1);
        assertEquals(List.of("press 960,540,1"), f.sink.described());
        assertEquals(0, f.held());
    }
}
