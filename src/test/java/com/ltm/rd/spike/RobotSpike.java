package com.ltm.rd.spike;

import com.ltm.rd.client.InputCommand;
import com.ltm.rd.client.InputDevice;
import com.ltm.rd.client.InputExecutor;
import com.ltm.rd.client.RobotAdapterFactory;
import com.ltm.rd.client.ScreenGeometry;
import com.ltm.rd.client.ScreenSource;
import com.ltm.rd.common.Validation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.LineBorder;

/**
 * Manual Robot spike for Task 3.1 (gate G0b). Not run by {@code mvn verify}: the class name does not
 * match a Surefire pattern. Run on each Windows machine, once per DPI setting:
 *
 * <pre>
 * ./mvnw.cmd -q test-compile
 * java -cp target/classes;target/test-classes com.ltm.rd.spike.RobotSpike
 * </pre>
 *
 * It opens a visible test window (mouse pad plus text area), drives it through the production
 * {@link RobotAdapterFactory} and {@link InputExecutor}, and prints a result block to paste into
 * docs/superpowers/reviews/2026-10-20-wave3-spike-robot-results.md. Do not touch the mouse or keyboard
 * while it runs. Closing the window aborts the run; held keys and buttons are always released.
 */
public final class RobotSpike {
    private static final int WAIT_SECONDS = 2;
    private static final int HOLD_SECONDS = 2;
    private static final int CLEANUP_WAIT_SECONDS = 5;
    private static final int PREFILL_CHARS = 400;

    // Keys/buttons the spike itself believes are held, independent of the executor's own tracking.
    private final Set<Integer> heldKeys = ConcurrentHashMap.newKeySet();
    private final Set<Integer> heldButtons = ConcurrentHashMap.newKeySet();
    private final CountDownLatch aborted = new CountDownLatch(1);
    private final List<String> report = new CopyOnWriteArrayList<>();

    private final MousePad pad = new MousePad();
    private final JTextArea text = new JTextArea(8, 40);
    private final JLabel status = new JLabel("starting");
    private JFrame frame;

    private InputDevice device;
    private ScreenSource source;
    private InputExecutor executor;
    private long generation;

    private final AtomicInteger padKeyPresses = new AtomicInteger();
    private volatile int lastKeyModifiersEx;
    private volatile CountDownLatch sentinel = new CountDownLatch(0);

    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Headless environment: the spike needs a real desktop.");
            System.exit(2);
        }
        System.out.println("RobotSpike takes over the mouse and keyboard for about a minute.");
        System.out.println("Do not touch them while it runs. Press Enter to start, Ctrl+C to abort.");
        System.in.read();
        new RobotSpike().run();
    }

    private void run() throws Exception {
        // Last resort: even a killed JVM tries to release what the spike still believes is held.
        Runtime.getRuntime().addShutdownHook(new Thread(this::releaseHeldDirectly, "rd-spike-cleanup"));
        try {
            RobotAdapterFactory factory = new RobotAdapterFactory();
            device = new TrackingDevice(factory.inputDevice());
            source = factory.newScreenSource();
            executor = new InputExecutor(device, () -> source.geometry().bounds(), 256,
                    InputExecutor.defaultThreadFactory(), t -> report.add("input fault: " + t));
            EventQueue.invokeAndWait(this::openWindow);
            sleepUnlessAborted(1);
            runSteps();
        } catch (Throwable t) {
            report.add("spike error: " + t);
            t.printStackTrace();
        } finally {
            cleanup();
            printReport();
        }
    }

    // ---- window ----

    private void openWindow() {
        frame = new JFrame("RobotSpike - do not touch the mouse or keyboard");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setAlwaysOnTop(true);
        pad.setPreferredSize(new Dimension(500, 200));
        pad.setBorder(new LineBorder(Color.BLUE, 3));
        pad.setBackground(Color.WHITE);
        pad.setOpaque(true);
        text.setLineWrap(true);
        frame.add(status, BorderLayout.NORTH);
        frame.add(pad, BorderLayout.CENTER);
        frame.add(text, BorderLayout.SOUTH);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                aborted.countDown();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                aborted.countDown();
            }
        });
        pad.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_B) {
                    lastKeyModifiersEx = e.getModifiersEx();
                    padKeyPresses.incrementAndGet();
                }
            }
        });
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.toFront();
    }

    private void setStatus(String s) {
        EventQueue.invokeLater(() -> status.setText(s));
        System.out.println("step: " + s);
    }

    // ---- steps (all run off the EDT) ----

    private void runSteps() throws Exception {
        ScreenGeometry g = source.geometry();
        Rectangle b = g.bounds();
        report.add("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.add("JDK: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        report.add("bounds (logical): " + b.width + "x" + b.height + " at " + b.x + "," + b.y);
        report.add("scale DPI: " + g.scaleX() + " x " + g.scaleY());

        stepCapture(b);
        if (isAborted()) return;
        stepCorners(b);
        if (isAborted()) return;
        stepMousePad(b);
        if (isAborted()) return;
        stepTyping(b);
        if (isAborted()) return;
        stepModifiers(b);
        if (isAborted()) return;
        stepRepeat(b);
        if (isAborted()) return;
        stepHeldAtClose(b);
    }

    private void stepCapture(Rectangle b) throws Exception {
        setStatus("capture");
        BufferedImage img = source.capture(b);
        report.add("capture size: " + img.getWidth() + "x" + img.getHeight());
        String verdict;
        try {
            Validation.checkFrameHeader(img.getWidth(), img.getHeight(), b.width, b.height);
            verdict = "PASS";
        } catch (RuntimeException e) {
            verdict = "FAIL (" + e.getMessage() + ")";
        }
        report.add("checkFrameHeader(capture vs bounds): " + verdict);
    }

    private void stepCorners(Rectangle b) throws Exception {
        setStatus("four corners");
        int[][] corners = {{0, 0}, {b.width - 1, 0}, {0, b.height - 1}, {b.width - 1, b.height - 1}};
        int worst = 0;
        for (int[] c : corners) {
            runSession(new InputCommand.MouseMove(c[0], c[1]));
            Point p = MouseInfo.getPointerInfo().getLocation();
            int dev = Math.max(Math.abs(p.x - (b.x + c[0])), Math.abs(p.y - (b.y + c[1])));
            worst = Math.max(worst, dev);
            report.add("corner " + c[0] + "," + c[1] + " -> pointer " + p.x + "," + p.y + " deviation " + dev);
        }
        report.add("corners worst deviation (logical px, expected 0): " + worst);
    }

    private void stepMousePad(Rectangle b) throws Exception {
        setStatus("click, drag, wheel");
        Point c = padCenter(b);
        runSession(new InputCommand.MousePress(c.x, c.y, 1), new InputCommand.MouseRelease(c.x, c.y, 1));
        boolean clicked = awaitUntil(() -> pad.clicks.get() >= 1);
        report.add("click received: " + clicked + " (clicks=" + pad.clicks.get() + ")");

        runSession(new InputCommand.MousePress(c.x, c.y, 1), new InputCommand.MouseMove(c.x + 10, c.y + 5),
                new InputCommand.MouseMove(c.x + 30, c.y + 15), new InputCommand.MouseMove(c.x + 50, c.y + 30),
                new InputCommand.MouseRelease(c.x + 50, c.y + 30, 1));
        boolean dragged = awaitUntil(() -> pad.drags.get() >= 1 && pad.releases.get() >= 2);
        report.add("drag received: " + dragged + " (drag events=" + pad.drags.get() + ")");

        runSession(new InputCommand.MouseMove(c.x, c.y), new InputCommand.Wheel(3));
        awaitUntil(() -> pad.wheelSum.get() != 0);
        int down = pad.wheelSum.get();
        runSession(new InputCommand.Wheel(-3));
        awaitUntil(() -> pad.wheelSum.get() != down);
        report.add("wheel +3 -> sum " + down + " (positive means scroll down); then -3 -> sum " + pad.wheelSum.get());
    }

    private void stepTyping(Rectangle b) throws Exception {
        setStatus("typing");
        focusText(b);
        runSession(key(KeyEvent.VK_H), key(KeyEvent.VK_E), key(KeyEvent.VK_L), key(KeyEvent.VK_L),
                key(KeyEvent.VK_O));
        awaitUntil(() -> textNow().length() >= 5);
        report.add("typed 'hello' -> '" + textNow() + "'");

        runSession(key(KeyEvent.VK_BACK_SPACE));
        awaitUntil(() -> textNow().length() <= 4);
        report.add("after Backspace -> '" + textNow() + "' (expected 'hell')");

        runSession(key(KeyEvent.VK_LEFT), key(KeyEvent.VK_X));
        awaitUntil(() -> textNow().contains("x"));
        report.add("after Left + x -> '" + textNow() + "' (expected 'helxl')");
    }

    private void stepModifiers(Rectangle b) throws Exception {
        setStatus("modifiers");
        Point c = padCenter(b);
        runSession(new InputCommand.MousePress(c.x, c.y, 1), new InputCommand.MouseRelease(c.x, c.y, 1));
        int[] mods = {KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT};
        int[] masks = {KeyEvent.SHIFT_DOWN_MASK, KeyEvent.CTRL_DOWN_MASK, KeyEvent.ALT_DOWN_MASK};
        String[] names = {"Shift", "Ctrl", "Alt"};
        for (int i = 0; i < mods.length; i++) {
            int before = padKeyPresses.get();
            runSession(new InputCommand.KeyPress(mods[i]), new InputCommand.KeyPress(KeyEvent.VK_B),
                    new InputCommand.KeyRelease(KeyEvent.VK_B), new InputCommand.KeyRelease(mods[i]));
            boolean seen = awaitUntil(() -> padKeyPresses.get() > before);
            boolean ok = seen && (lastKeyModifiersEx & masks[i]) != 0;
            report.add("hold " + names[i] + " + B -> modifier seen by target: " + ok);
        }
        focusText(b);
        runSession(new InputCommand.KeyPress(KeyEvent.VK_SHIFT), new InputCommand.KeyPress(KeyEvent.VK_Z),
                new InputCommand.KeyRelease(KeyEvent.VK_Z), new InputCommand.KeyRelease(KeyEvent.VK_SHIFT));
        awaitUntil(() -> textNow().contains("Z"));
        report.add("Shift + z -> text '" + textNow() + "' (expected an upper-case Z)");
    }

    private void stepRepeat(Rectangle b) throws Exception {
        setStatus("key repeat: Backspace");
        focusText(b);
        setText("y".repeat(PREFILL_CHARS));
        int before = textNow().length();
        holdKey(KeyEvent.VK_BACK_SPACE);
        int deleted = before - textNow().length();
        report.add("hold Backspace " + HOLD_SECONDS + " s: deleted " + deleted + " of " + before
                + " characters (compare with holding the physical key for the same time)");

        setStatus("key repeat: letter");
        setText("");
        holdKey(KeyEvent.VK_X);
        report.add("hold 'x' " + HOLD_SECONDS + " s: typed " + textNow().length()
                + " characters (compare with holding the physical key; doubled counts mean SEND_ALL repeats twice)");
    }

    private void stepHeldAtClose(Rectangle b) throws Exception {
        setStatus("CLOSE THIS WINDOW NOW to test cleanup (auto-continues in " + CLEANUP_WAIT_SECONDS + " s)");
        focusText(b);
        executor.beginSession(++generation);
        executor.submit(generation, new InputCommand.KeyPress(KeyEvent.VK_SHIFT));
        Point c = padCenter(b);
        executor.submit(generation, new InputCommand.MousePress(c.x, c.y, 1));
        boolean closed = aborted.await(CLEANUP_WAIT_SECONDS, TimeUnit.SECONDS);
        report.add("window closed while Shift and left button were held: " + closed
                + " (afterwards check that Shift and the button are not stuck)");
        // cleanup() ends the session and releases everything held.
    }

    // ---- helpers ----

    /** Runs the commands in their own executor session and waits until held input is released again. */
    private void runSession(InputCommand... cmds) throws Exception {
        long gen = ++generation;
        if (!executor.beginSession(gen)) {
            throw new IllegalStateException("executor refused a new session");
        }
        for (InputCommand cmd : cmds) {
            executor.submit(gen, cmd);
        }
        drainAndEnd(gen);
    }

    /**
     * Wheel(0) is a sentinel: the device signals when the worker reaches it, so every command before it has run.
     * endSession then releases anything still held (it would otherwise drop queued commands).
     */
    private void drainAndEnd(long gen) throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        sentinel = reached;
        executor.submit(gen, new InputCommand.Wheel(0));
        if (!reached.await(WAIT_SECONDS * 2, TimeUnit.SECONDS)) {
            report.add("warning: executor did not drain in time");
        }
        executor.endSession(gen).get(WAIT_SECONDS * 2, TimeUnit.SECONDS);
    }

    private void holdKey(int vk) throws Exception {
        long gen = ++generation;
        executor.beginSession(gen);
        executor.submit(gen, new InputCommand.KeyPress(vk));
        aborted.await(HOLD_SECONDS, TimeUnit.SECONDS);
        executor.submit(gen, new InputCommand.KeyRelease(vk));
        drainAndEnd(gen);
    }

    private static InputCommand key(int vk) {
        return new InputCommand.KeyPress(vk);
    }

    private void focusText(Rectangle b) throws Exception {
        Point c = textCenter(b);
        runSession(new InputCommand.MousePress(c.x, c.y, 1), new InputCommand.MouseRelease(c.x, c.y, 1));
        awaitUntil(text::hasFocus);
    }

    private Point padCenter(Rectangle b) throws Exception {
        return centerOf(pad, b);
    }

    private Point textCenter(Rectangle b) throws Exception {
        return centerOf(text, b);
    }

    /** Screen position of the component centre, relative to the shared screen origin. */
    private static Point centerOf(javax.swing.JComponent comp, Rectangle b) throws Exception {
        Point[] out = new Point[1];
        SwingUtilities.invokeAndWait(() -> {
            Point p = comp.getLocationOnScreen();
            out[0] = new Point(p.x + comp.getWidth() / 2 - b.x, p.y + comp.getHeight() / 2 - b.y);
        });
        return out[0];
    }

    private String textNow() {
        String[] out = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = text.getText());
        } catch (Exception e) {
            return "";
        }
        return out[0];
    }

    private void setText(String s) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            text.setText(s);
            text.setCaretPosition(s.length());
        });
    }

    private boolean isAborted() {
        return aborted.getCount() == 0;
    }

    private void sleepUnlessAborted(int seconds) throws InterruptedException {
        aborted.await(seconds, TimeUnit.SECONDS);
    }

    /** Polls until the condition holds or the bounded wait ends; a spike only, never used in tests. */
    private boolean awaitUntil(BooleanSupplier cond) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        return cond.getAsBoolean();
    }

    // ---- cleanup and report ----

    /** Always runs: ends the session (releasing what the executor holds), stops it, then releases the rest. */
    private void cleanup() {
        if (executor != null) {
            try {
                executor.endSession(generation).get(CLEANUP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                report.add("cleanup: endSession did not complete: " + e);
            }
            try {
                executor.shutdownAsync().get(CLEANUP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                report.add("cleanup: executor shutdown did not complete: " + e);
            }
        }
        releaseHeldDirectly();
        if (frame != null) {
            EventQueue.invokeLater(frame::dispose);
        }
    }

    /** Releases every key and button the spike still believes is held, ignoring failures. */
    private void releaseHeldDirectly() {
        InputDevice d = device;
        if (d == null) {
            return;
        }
        for (Integer k : heldKeys.toArray(new Integer[0])) {
            try {
                d.keyRelease(k);
            } catch (RuntimeException ignored) {
                // Best effort.
            }
        }
        for (Integer btn : heldButtons.toArray(new Integer[0])) {
            try {
                d.mouseRelease(btn);
            } catch (RuntimeException ignored) {
                // Best effort.
            }
        }
        heldKeys.clear();
        heldButtons.clear();
    }

    private void printReport() {
        System.out.println();
        System.out.println("=== RobotSpike result (paste into the results file) ===");
        report.forEach(System.out::println);
        System.out.println("=== end ===");
        System.out.println("Now check by hand that no key or mouse button is stuck.");
        System.exit(0);
    }

    /** Wraps the device so the spike knows what it asked to hold, whatever the executor tracked. */
    private final class TrackingDevice implements InputDevice {
        private final InputDevice delegate;

        TrackingDevice(InputDevice delegate) {
            this.delegate = delegate;
        }

        @Override
        public void mouseMove(int absX, int absY) {
            delegate.mouseMove(absX, absY);
        }

        @Override
        public void mousePress(int button) {
            heldButtons.add(button);
            delegate.mousePress(button);
        }

        @Override
        public void mouseRelease(int button) {
            delegate.mouseRelease(button);
            heldButtons.remove(button);
        }

        @Override
        public void mouseWheel(int notches) {
            if (notches == 0) {
                sentinel.countDown();
                return;
            }
            delegate.mouseWheel(notches);
        }

        @Override
        public void keyPress(int keyCode) {
            heldKeys.add(keyCode);
            delegate.keyPress(keyCode);
        }

        @Override
        public void keyRelease(int keyCode) {
            delegate.keyRelease(keyCode);
            heldKeys.remove(keyCode);
        }
    }

    /** Visible mouse target that counts what it receives. */
    private final class MousePad extends JPanel {
        final AtomicInteger clicks = new AtomicInteger();
        final AtomicInteger drags = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger wheelSum = new AtomicInteger();

        MousePad() {
            setFocusable(true);
            add(new JLabel("mouse pad"));
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    releases.incrementAndGet();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    clicks.incrementAndGet();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    drags.incrementAndGet();
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    wheelSum.addAndGet(e.getWheelRotation());
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }
    }
}
