package com.ltm.rd.it;

import com.ltm.rd.common.Packet;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.RobotAdapterFactory;
import com.ltm.rd.ui.AppConfig;
import com.ltm.rd.ui.AppRuntime;
import com.ltm.rd.ui.ConnectOutcome;
import com.ltm.rd.ui.Controller;
import com.ltm.rd.ui.RemotePanel;
import com.ltm.rd.ui.SendResult;
import com.ltm.rd.ui.ShutdownReport;
import com.ltm.rd.ui.Snapshot;
import com.ltm.rd.ui.UiPhase;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.PrintStream;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Test classpath entrypoint only; stdout is a private, bounded parent-child control pipe. */
public final class TwoJvmPeerMain {
    private final String role;
    private final PrintStream wire;
    private final BlockingQueue<String> commands = new ArrayBlockingQueue<>(32);
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "two-jvm-snapshot-monitor");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
    private volatile Controller runtime;
    private volatile Snapshot latest;
    private volatile boolean active;
    private volatile boolean stopping;
    private boolean closedEvent;
    private JFrame window;
    private Surface surface;
    private RemotePanel panel;
    private String stage = "startup";
    private ShutdownReport shutdownReport;
    private java.util.function.Consumer<String> diagnostic;

    private TwoJvmPeerMain(String role, PrintStream wire) {
        this.role = role;
        this.wire = wire;
    }

    public static void main(String[] args) throws Exception {
        PrintStream privateWire = System.out;
        // Keep private control stdout isolated from product output and structured diagnostics.
        var diagnostic = TwoJvmDiagnostics.install(System.err);
        if (args.length != 2 || !args[0].equals("--child-role")
                || (!args[1].equals("host") && !args[1].equals("viewer"))) {
            diagnostic.accept("DIAG failure phase=arguments reason=InvalidRole");
            privateWire.println("FAILURE arguments InvalidRole");
            System.exit(2);
            return;
        }
        TwoJvmPeerMain peer = new TwoJvmPeerMain(args[1], privateWire);
        peer.diagnostic = diagnostic;
        int exit = 0;
        try {
            peer.run();
        } catch (Throwable failure) {
            TwoJvmDiagnostics.failure(diagnostic, peer.stage, failure);
            peer.event("FAILURE " + TwoJvmDiagnostics.phase(peer.stage) + " " + TwoJvmDiagnostics.reason(failure));
            exit = 1;
        } finally {
            try { peer.cleanup(); }
            catch (Throwable failure) {
                TwoJvmDiagnostics.failure(diagnostic, "cleanup", failure);
                peer.event("FAILURE cleanup " + TwoJvmDiagnostics.reason(failure));
                exit = 2;
            }
        }
        // Swing owns a non-daemon EDT; all owned runtime/window cleanup completed before this bounded child exit.
        System.exit(exit);
    }

    private void run() throws Exception {
        Thread reader = new Thread(() -> TwoJvmPeer.pump(System.in, line -> {
            if (!commands.offer(line)) asynchronousFailure.compareAndSet(null, new IllegalStateException("CommandOverflow"));
        }, () -> commands.offer("STOP")), "two-jvm-command-reader");
        reader.setDaemon(true);
        reader.start();
        if (role.equals("host")) {
            stage = "desktop_paint_focus_Robot";
            if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("HostDesktopUnavailable");
            createSurface();
            runtime = AppRuntime.forHost(config(0, 0), new RobotAdapterFactory());
            try { runtime.startHost().get(7, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException("HostNotListening", failure); }
            Snapshot listening = runtime.snapshot();
            if (listening.phase() != UiPhase.LISTENING || listening.cmdPort() <= 0 || listening.imgPort() <= 0
                    || listening.code() == null || !listening.code().matches("[0-9]{6}")) {
                throw new IllegalStateException("HostNotListening");
            }
            event("READY host " + listening.cmdPort() + " " + listening.imgPort() + " " + listening.code());
        } else {
            stage = "headless_viewer_classloading";
            if (!GraphicsEnvironment.isHeadless()) throw new IllegalStateException("ViewerMustBeHeadless");
            panel = edt(() -> {
                RemotePanel remote = new RemotePanel();
                remote.setSize(640, 400);
                return remote;
            });
            event("READY viewer");
        }
        monitor.scheduleWithFixedDelay(this::sample, 0, 20, TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + Duration.ofSeconds(110).toNanos();
        while (true) {
            checkAsynchronousFailure();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("ChildLifetimeExceeded");
            String command = commands.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            if (command == null) continue;
            String[] fields = command.split(" ");
            // The stage is a fixed diagnostic identifier, never a raw command or authentication payload.
            stage = "private_command";
            switch (fields[0]) {
                case "CONNECT" -> connect(fields);
                case "FRAMES" -> {
                    requireRole("viewer");
                    stage = "decoded_frames";
                    await(() -> {
                        Snapshot snapshot = latest;
                        return snapshot != null && snapshot.frameCount() >= 30 && completeFrame(snapshot);
                    }, Duration.ofSeconds(15));
                    Snapshot snapshot = latest;
                    event("FRAMES " + snapshot.frameCount() + " " + snapshot.frame().getWidth() + " " + snapshot.frame().getHeight());
                }
                case "ARM_INPUT" -> {
                    requireRole("host");
                    stage = "native_input_focus";
                    edt(() -> {
                        surface.recorder.arm();
                        window.toFront();
                        surface.requestFocusInWindow();
                        return null;
                    });
                    try {
                        await(() -> uncheckedEdt(() -> surface.isFocusOwner() && surface.painted.getCount() == 0), Duration.ofSeconds(5));
                    } catch (java.util.concurrent.TimeoutException failure) {
                        throw new IllegalStateException("TargetNotFocused", failure);
                    }
                    event("FOCUSED");
                }
                case "INPUT" -> input();
                case "OBSERVE_INPUT" -> {
                    requireRole("host");
                    stage = "actual_native_events";
                    await(() -> uncheckedEdt(() -> surface.recorder.ready()), Duration.ofSeconds(10));
                    // A lower bound only: recording stays armed through session close and the native fence.
                    event("INPUT_OBSERVED");
                }
                case "EVENTS" -> {
                    requireRole("host");
                    stage = "actual_native_events";
                    List<String> keys = TwoJvmInputCompletion.finish(surface.recorder, () -> {
                        if (shutdownReport == null || !shutdownReport.socketsClosed() || !shutdownReport.workersStopped()
                                || (shutdownReport.inputRelease() != com.ltm.rd.ui.InputRelease.RELEASED
                                && shutdownReport.inputRelease() != com.ltm.rd.ui.InputRelease.NOT_NEEDED)) {
                            throw new IllegalStateException("UncleanRuntimeShutdown");
                        }
                        // Stop recurring snapshot/EDT work before synchronizing the native event queue.
                        monitor.shutdownNow();
                        if (!monitor.awaitTermination(2, TimeUnit.SECONDS)) throw new IllegalStateException("MonitorNotStopped");
                        TwoJvmInputCompletion.requireSupportedDesktop();
                        edt(() -> { surface.requireFocus(); return null; });
                        return null;
                    }, () -> {
                        try {
                            java.awt.Robot probe = new java.awt.Robot();
                            try { probe.keyPress(TwoJvmInputRecorder.PROBE_KEY); }
                            finally { probe.keyRelease(TwoJvmInputRecorder.PROBE_KEY); }
                        } catch (java.awt.AWTException failure) {
                            throw new IllegalStateException("NativeSetupFailed", failure);
                        } catch (IllegalArgumentException failure) {
                            throw new IllegalStateException("NativeFenceUnsupported", failure);
                        }
                    });
                    edt(() -> { surface.requireFocus(); surface.recorder.audit(); return null; });
                    event("INPUT_EVENTS " + String.join(",", keys));
                    event("INPUT_PROBE 2");
                    event("INPUT_HELD 0");
                }
                case "SHUTDOWN" -> {
                    stage = "shutdown";
                    ShutdownReport report = shutdown();
                    shutdownReport = report;
                    event("SHUTDOWN " + report.socketsClosed() + " " + report.workersStopped() + " " + report.inputRelease());
                }
                case "STOP" -> { return; }
                default -> throw new IllegalArgumentException("UnknownPrivateCommand");
            }
        }
    }

    private void connect(String[] fields) throws Exception {
        requireRole("viewer");
        stage = "connect";
        if (runtime != null || fields.length != 4 || !fields[3].matches("[0-9]{6}")) {
            throw new IllegalArgumentException("InvalidConnectContract");
        }
        int cmdPort = Integer.parseInt(fields[1]);
        int imgPort = Integer.parseInt(fields[2]);
        if (cmdPort <= 0 || cmdPort > 65535 || imgPort <= 0 || imgPort > 65535) {
            throw new IllegalArgumentException("InvalidListenerPorts");
        }
        runtime = AppRuntime.forViewer(config(cmdPort, imgPort));
        if (runtime.connect("127.0.0.1", fields[3]) != ConnectOutcome.STARTED) {
            throw new IllegalStateException("ConnectNotStarted");
        }
    }

    private void input() throws Exception {
        requireRole("viewer");
        stage = "painted_gate_input";
        if (!active) throw new IllegalStateException("ViewerNotActive");
        long generation = latest.generation();
        try {
            for (int pair = 0; pair < 50; pair++) {
                Snapshot snapshot = runtime.snapshot(); // Never read runtime snapshots on EDT.
                RemotePanel.InputGate gate = paint(snapshot);
                if (gate == null) throw new IllegalStateException("PaintedInputGateClosed");
                int key = pair % 2 == 0 ? KeyEvent.VK_A : KeyEvent.VK_B;
                if (runtime.sendInput(gate.generation(), gate.inputEpoch(), Packet.keyPress(key)) != SendResult.SENT
                        || runtime.sendInput(gate.generation(), gate.inputEpoch(), Packet.keyRelease(key)) != SendResult.SENT) {
                    throw new IllegalStateException("InputNotSent");
                }
            }
            event("INPUT_SENT 100");
        } catch (Throwable failure) {
            runtime.releaseControlInput(generation);
            throw failure;
        }
    }

    private void sample() {
        try {
            Controller controller = runtime;
            if (controller == null) return;
            Snapshot snapshot = controller.snapshot();
            latest = snapshot;
            RemotePanel.InputGate gate = role.equals("viewer") ? paint(snapshot) : null;
            if (!active && snapshot.phase() == UiPhase.ACTIVE
                    && (role.equals("host") || (completeFrame(snapshot) && gate != null))) {
                active = true;
                event("ACTIVE");
            }
            UiPhase idle = role.equals("host") ? UiPhase.LISTENING : UiPhase.IDLE;
            if (active && !closedEvent && snapshot.phase() == idle && snapshot.generation() == 0
                    && snapshot.cleanup() == Snapshot.Cleanup.NONE) {
                closedEvent = true;
                event("CLOSED " + idle + " " + snapshot.cleanup());
            }
        } catch (Throwable failure) {
            if (!stopping && asynchronousFailure.compareAndSet(null, failure)) {
                TwoJvmDiagnostics.failure(diagnostic, "snapshot_monitor", failure);
                event("FAILURE snapshot_monitor " + TwoJvmDiagnostics.reason(failure));
            }
        }
    }

    private RemotePanel.InputGate paint(Snapshot snapshot) throws Exception {
        return edt(() -> {
            panel.apply(snapshot);
            BufferedImage image = new BufferedImage(640, 400, BufferedImage.TYPE_INT_RGB);
            Graphics graphics = image.getGraphics();
            try { panel.paint(graphics); } finally { graphics.dispose(); }
            return panel.inputGate().orElse(null);
        });
    }

    private void createSurface() throws Exception {
        edt(() -> {
            surface = new Surface();
            window = new JFrame("Host / Viewer native validation surface");
            window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            window.setContentPane(surface);
            window.setSize(640, 400);
            window.setLocationRelativeTo(null);
            window.setVisible(true);
            window.toFront();
            surface.requestFocusInWindow();
            return null;
        });
        if (!surface.painted.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("TargetNotPainted");
        try { await(() -> uncheckedEdt(() -> surface.isFocusOwner()), Duration.ofSeconds(5)); }
        catch (java.util.concurrent.TimeoutException failure) { throw new IllegalStateException("TargetNotFocused", failure); }
    }

    private static final class Surface extends JPanel {
        final CountDownLatch painted = new CountDownLatch(1);
        final TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        boolean disposing;

        Surface() {
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent event) {
                    if (!disposing) recorder.reject(TwoJvmDiagnostics.Reason.TargetNotFocused);
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent event) { record("P", event); }
                @Override public void keyReleased(KeyEvent event) { record("R", event); }
            });
        }

        private void record(String kind, KeyEvent event) {
            if (!isFocusOwner()) recorder.reject(TwoJvmDiagnostics.Reason.TargetNotFocused);
            recorder.record(kind, event);
        }

        private void requireFocus() {
            if (!isFocusOwner() || !isShowing()) throw new IllegalStateException("TargetNotFocused");
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            graphics.setColor(new Color(0x245c80));
            graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.setColor(Color.WHITE);
            graphics.drawString("Controlled native input target: 50 balanced A/B pairs", 24, 48);
            painted.countDown();
        }
    }

    private void await(BooleanSupplier condition, Duration timeout) throws Exception {
        CompletableFuture<Void> reached = new CompletableFuture<>();
        var watcher = monitor.scheduleWithFixedDelay(() -> {
            try {
                checkAsynchronousFailure();
                if (condition.getAsBoolean()) reached.complete(null);
            } catch (Throwable failure) { reached.completeExceptionally(failure); }
        }, 0, 20, TimeUnit.MILLISECONDS);
        try { reached.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        finally { watcher.cancel(false); }
    }

    private void checkAsynchronousFailure() {
        if (asynchronousFailure.get() != null) throw new IllegalStateException("AsynchronousPeerFailure", asynchronousFailure.get());
    }

    private ShutdownReport shutdown() throws Exception {
        if (runtime == null) throw new IllegalStateException("RuntimeNotCreated");
        return runtime.shutdownAsync().get(7, TimeUnit.SECONDS);
    }

    private void cleanup() throws Exception {
        stopping = true;
        monitor.shutdownNow();
        try {
            if (runtime != null) {
                ShutdownReport report = shutdown();
                if (!report.socketsClosed() || !report.workersStopped()
                        || (report.inputRelease() != com.ltm.rd.ui.InputRelease.RELEASED
                        && report.inputRelease() != com.ltm.rd.ui.InputRelease.NOT_NEEDED)) {
                    throw new IllegalStateException("UncleanRuntimeShutdown");
                }
            }
        } finally {
            Exception auditFailure = null;
            try {
                edt(() -> {
                    try { if (surface != null) surface.recorder.audit(); }
                    finally {
                        if (surface != null) surface.disposing = true;
                        if (window != null) window.dispose();
                    }
                    return null;
                });
            } catch (Exception failure) { auditFailure = failure; }
            try {
                edt(() -> {
                    if (surface != null) {
                        try { surface.recorder.audit(); }
                        finally { surface.recorder.disarm(); }
                    }
                    return null;
                });
            } catch (Exception failure) {
                if (auditFailure == null) auditFailure = failure;
                else auditFailure.addSuppressed(failure);
            }
            if (!monitor.awaitTermination(2, TimeUnit.SECONDS)) throw new IllegalStateException("MonitorNotStopped");
            if (auditFailure != null) throw auditFailure;
        }
    }

    private synchronized void event(String event) {
        wire.println(event);
        wire.flush();
        if (wire.checkError()) throw new IllegalStateException("PrivateEventPipeClosed");
    }

    private void requireRole(String expected) {
        if (!role.equals(expected)) throw new IllegalArgumentException("CommandRoleMismatch");
    }

    private static AppConfig config(int cmdPort, int imgPort) throws Exception {
        return new AppConfig(InetAddress.getByName("127.0.0.1"), cmdPort, imgPort, "127.0.0.1",
                Timeouts.DEFAULTS, System::nanoTime, Duration.ofSeconds(5));
    }

    private static boolean completeFrame(Snapshot snapshot) {
        return snapshot.frameCount() > 0 && snapshot.frame() != null
                && snapshot.frame().getWidth() > 0 && snapshot.frame().getHeight() > 0;
    }

    private static <T> T edt(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        SwingUtilities.invokeLater(task);
        try { return task.get(3, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException failure) { throw new IllegalStateException("EdtUnavailable", failure); }
    }

    private static boolean uncheckedEdt(Callable<Boolean> work) {
        try { return edt(work); }
        catch (Exception failure) { throw new IllegalStateException("EdtUnavailable", failure); }
    }
}
