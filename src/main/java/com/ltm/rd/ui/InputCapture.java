package com.ltm.rd.ui;

import com.ltm.rd.common.Packet;
import com.ltm.rd.common.Protocol;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Turns mouse events of a {@link RemotePanel} into input packets for {@link Controller#sendInput}.
 * Everything runs on the EDT. Each packet carries the (generation, epoch) of the frame the panel
 * shows, so the Server refuses input aimed at a picture that is gone. Nothing about the events is
 * logged.
 *
 * <p>The first press on an unfocused panel only takes focus; that button's press, drags and
 * release are swallowed (tracked per button, not with one shared flag). A release is sent only for
 * a button whose press was accepted, and held state is dropped whenever the applied generation or
 * epoch changes, never restored.
 */
public final class InputCapture
        implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener, FocusListener {
    private static final int MAX_WHEEL_PACKETS = 3;

    /**
     * How key repeats are handled unless a caller says otherwise. Not yet confirmed by the Robot
     * spike of Task 3.1 (no result is in): SEND_ALL is the plan's default. Change it here, in one
     * place, once the spike result and the keyboard trial decide.
     */
    public static final RepeatPolicy DEFAULT_REPEAT_POLICY = RepeatPolicy.SEND_ALL;

    private final Controller sink;
    private final RemotePanel panel;
    private final BooleanSupplier focusOwner;
    private final Runnable requestFocus;
    private final RepeatPolicy repeatPolicy;

    // EDT only
    private final Set<Integer> swallowed = new HashSet<>();
    private final Set<Integer> held = new HashSet<>();
    private final Set<Integer> heldKeys = new HashSet<>();
    private boolean acceptingInput = true;
    private boolean releaseRequested; // a focus-loss release is pending: later notifications are duplicates
    private double wheelAcc;
    private long appliedGeneration = Long.MIN_VALUE;
    private long appliedEpoch = Long.MIN_VALUE;

    public InputCapture(Controller sink, RemotePanel panel, BooleanSupplier focusOwner, Runnable requestFocus) {
        this(sink, panel, focusOwner, requestFocus, DEFAULT_REPEAT_POLICY);
    }

    public InputCapture(Controller sink, RemotePanel panel, BooleanSupplier focusOwner, Runnable requestFocus,
            RepeatPolicy repeatPolicy) {
        this.sink = sink;
        this.panel = panel;
        this.focusOwner = focusOwner;
        this.requestFocus = requestFocus;
        this.repeatPolicy = repeatPolicy;
        panel.setFocusable(true);
        panel.setFocusTraversalKeysEnabled(false); // Tab is input for the remote machine, not focus traversal
    }

    /** Call after {@link RemotePanel#apply}; a changed generation or epoch drops the held state. */
    public void onApplied(long generation, long inputEpoch) {
        Edt.requireEdt();
        if (generation != appliedGeneration || inputEpoch != appliedEpoch) {
            resetHeldState();
            appliedGeneration = generation;
            appliedEpoch = inputEpoch;
        }
    }

    public int heldButtonCount() {
        Edt.requireEdt();
        return held.size();
    }

    public double wheelAccumulator() {
        Edt.requireEdt();
        return wheelAcc;
    }

    /** Whether new events are accepted: false from a focus loss until the focus is back. */
    public boolean acceptingInput() {
        Edt.requireEdt();
        return acceptingInput;
    }

    public int heldKeyCount() {
        Edt.requireEdt();
        return heldKeys.size();
    }

    /** Forgets held keys, buttons and the wheel remainder. Swallowed buttons stay: each ends with its own release. */
    private void resetHeldState() {
        held.clear();
        heldKeys.clear();
        wheelAcc = 0;
    }

    // ---- buttons ---------------------------------------------------------------------------------

    @Override
    public void mousePressed(MouseEvent e) {
        Edt.requireEdt();
        int button = e.getButton();
        swallowed.remove(button); // a new press starts a new operation for this button
        if (!focusOwner.getAsBoolean()) {
            requestFocus.run();
            swallowed.add(button);
            return;
        }
        if (button < 1 || button > 3 || !acceptingInput) return;
        Optional<RemotePanel.InputGate> gate = panel.inputGate();
        if (gate.isEmpty()) return;
        Optional<CoordinateMapper.RemotePoint> p = panel.toRemote(e.getX(), e.getY());
        if (p.isEmpty()) return;
        SendResult result = send(gate.get(), Packet.mousePress(p.get().x(), p.get().y(), button));
        if (result == SendResult.SENT) held.add(button);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        Edt.requireEdt();
        int button = e.getButton();
        if (swallowed.remove(button)) return;
        if (!held.remove(button)) return;
        Optional<RemotePanel.InputGate> gate = panel.inputGate();
        if (gate.isEmpty()) return; // stalled: the Server already sent RELEASE_ALL at the stall
        Optional<CoordinateMapper.RemotePoint> p = panel.toRemoteClamped(e.getX(), e.getY());
        if (p.isEmpty()) return;
        send(gate.get(), Packet.mouseRelease(p.get().x(), p.get().y(), button));
    }

    // ---- motion ----------------------------------------------------------------------------------

    @Override
    public void mouseDragged(MouseEvent e) {
        moved(e);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        moved(e);
    }

    private void moved(MouseEvent e) {
        Edt.requireEdt();
        Set<Integer> down = buttonsDown(e.getModifiersEx());
        if (!down.isEmpty() && swallowed.containsAll(down)) return;
        Optional<RemotePanel.InputGate> gate = panel.inputGate();
        if (gate.isEmpty()) return;
        Optional<CoordinateMapper.RemotePoint> p = held.isEmpty() ? panel.toRemote(e.getX(), e.getY())
                : panel.toRemoteClamped(e.getX(), e.getY());
        if (p.isEmpty()) return;
        send(gate.get(), Packet.mouseMove(p.get().x(), p.get().y()));
    }

    private static Set<Integer> buttonsDown(int modifiersEx) {
        Set<Integer> down = new HashSet<>();
        if ((modifiersEx & InputEvent.BUTTON1_DOWN_MASK) != 0) down.add(MouseEvent.BUTTON1);
        if ((modifiersEx & InputEvent.BUTTON2_DOWN_MASK) != 0) down.add(MouseEvent.BUTTON2);
        if ((modifiersEx & InputEvent.BUTTON3_DOWN_MASK) != 0) down.add(MouseEvent.BUTTON3);
        return down;
    }

    // ---- wheel -----------------------------------------------------------------------------------

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        Edt.requireEdt();
        if (!acceptingInput || !focusOwner.getAsBoolean()) return;
        Optional<RemotePanel.InputGate> gate = panel.inputGate();
        if (gate.isEmpty()) return;
        wheelAcc += e.getPreciseWheelRotation();
        int whole = (int) wheelAcc; // truncates toward zero
        wheelAcc -= whole;
        int max = Protocol.MAX_WHEEL_NOTCHES;
        for (int packets = 0; packets < MAX_WHEEL_PACKETS && whole != 0; packets++) {
            int chunk = Math.max(-max, Math.min(max, whole));
            send(gate.get(), Packet.wheel(chunk));
            whole -= chunk;
        } // what is left of the integer part is dropped; the fraction stays in wheelAcc
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        // not used: press and release carry everything
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        // not used
    }

    @Override
    public void mouseExited(MouseEvent e) {
        // not used
    }

    // ---- keyboard --------------------------------------------------------------------------------

    /** Virtual key codes only; the panel never sees keyTyped content, and no input method is involved. */
    @Override
    public void keyPressed(KeyEvent e) {
        Edt.requireEdt();
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_UNDEFINED) return;
        Optional<RemotePanel.InputGate> gate = keyGate();
        if (gate.isEmpty()) return;
        e.consume();
        boolean repeat = heldKeys.contains(code);
        if (repeat && repeatPolicy == RepeatPolicy.FIRST_PRESS_ONLY) return;
        if (send(gate.get(), Packet.keyPress(code)) == SendResult.SENT) heldKeys.add(code);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        Edt.requireEdt();
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_UNDEFINED || !heldKeys.remove(code)) return;
        e.consume();
        Optional<RemotePanel.InputGate> gate = panel.inputGate();
        if (gate.isEmpty()) return; // stalled: the Server already sent RELEASE_ALL
        send(gate.get(), Packet.keyRelease(code));
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // not used: only key codes are forwarded
    }

    /** Keys count only with focus, a displayed frame under control and no pending focus-loss release. */
    private Optional<RemotePanel.InputGate> keyGate() {
        if (!acceptingInput || !focusOwner.getAsBoolean()) return Optional.empty();
        return panel.inputGate();
    }

    // ---- focus loss and disconnect ---------------------------------------------------------------

    @Override
    public void focusGained(FocusEvent e) {
        Edt.requireEdt();
        acceptingInput = true;
        releaseRequested = false;
    }

    @Override
    public void focusLost(FocusEvent e) {
        loseInput();
    }

    /** Called when the window with the panel is deactivated (for example Alt+Tab). */
    public void windowDeactivated() {
        Edt.requireEdt();
        loseInput();
    }

    /** Called when the user disconnects or closes the control window. */
    public void disconnecting() {
        Edt.requireEdt();
        loseInput();
    }

    /**
     * Stops accepting events and asks the Server to have the controlled side release everything,
     * once per loss, even when the image gate is closed. Held state is dropped and never restored.
     */
    private void loseInput() {
        Edt.requireEdt();
        if (releaseRequested) return; // duplicate notification of the same loss
        releaseRequested = true;
        acceptingInput = false;
        resetHeldState();
        if (appliedGeneration > 0) sink.releaseControlInput(appliedGeneration);
    }

    private SendResult send(RemotePanel.InputGate gate, Packet packet) {
        return sink.sendInput(gate.generation(), gate.inputEpoch(), packet);
    }
}
