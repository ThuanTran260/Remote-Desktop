package com.ltm.rd.host;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionLifecycle;
import com.ltm.rd.common.Ticker;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.host.AdapterFactory;
import com.ltm.rd.host.FakeInputDevice;
import com.ltm.rd.host.FakeScreenSource;
import com.ltm.rd.host.InputDevice;
import com.ltm.rd.host.InputExecutor;
import com.ltm.rd.host.ScreenGeometry;
import com.ltm.rd.host.ScreenSource;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** Test-only bridge: re-exposes the package-private session lifecycle to integration tests in other packages. */
public final class HostTestAccess {
    private HostTestAccess() {}

    public static SessionLifecycle<HostPhase> lifecycle(HostSession session) {
        return session.lifecycle();
    }

    public static java.net.Socket channelSocket(HostSession session, boolean image) {
        return session.lifecycle().atomically(tx -> {
            try {
                var field = HostSession.class.getDeclaredField(image ? "imageChannel" : "cmdChannel");
                field.setAccessible(true);
                return ((com.ltm.rd.common.Channel) field.get(session)).socket();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }

    /** Read-only fixture probe under the real lock, so a loss test closes an attached image socket. */
    public static boolean imageAttached(HostSession session) {
        return session.lifecycle().atomically(tx -> {
            try {
                var field = HostSession.class.getDeclaredField("imageChannel");
                field.setAccessible(true);
                return field.get(session) != null;
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Host image handover fixture probe failed", failure);
            }
        });
    }

    /** One input executor per fixture owner, shared by all its Host sessions. */
    public static final class HostResources implements AutoCloseable {
        private volatile ScreenGeometry bounds = new ScreenGeometry(new Rectangle(0, 0, 1920, 1080), 1, 1);
        public ScreenSource source = new FakeScreenSource(1920, 1080);
        public Runnable beforeSenderStart = () -> { };
        public final InputExecutor input;
        public final FakeInputDevice device;
        private final boolean ownsInput;
        private final List<HostCore> cores = new ArrayList<>();
        private final List<HostSession> sessions = new ArrayList<>();
        public AdapterFactory factory;
        private final AdapterFactory adapters = new AdapterFactory() {
            @Override public void refreshInputDevice() throws com.ltm.rd.host.AdapterException {
                if (factory != null) factory.refreshInputDevice();
            }
            @Override public ScreenSource newScreenSource() throws com.ltm.rd.host.AdapterException {
                return factory == null ? source : factory.newScreenSource();
            }
            @Override public InputDevice inputDevice() { throw new AssertionError("session cannot create input"); }
        };

        public HostResources() {
            device = new FakeInputDevice();
            input = new InputExecutor(device, () -> bounds.bounds(), 256,
                    InputExecutor.defaultThreadFactory(), error -> { });
            ownsInput = true;
        }

        public HostResources(InputExecutor input) {
            this.input = input;
            device = null;
            ownsInput = false;
        }

        public HostCore core(HostConfig cfg) { return core(cfg, HostCore.Seams.none()); }

        HostCore core(HostConfig cfg, HostCore.Seams seams) {
            HostCore core = new HostCore(cfg, input, adapters, geometry -> bounds = geometry,
                    () -> beforeSenderStart.run(), session -> { }, seams);
            cores.add(core);
            return core;
        }

        HostSession.Env env(Timeouts timeouts, LongSupplier clock, Ticker ticker, BooleanSupplier shuttingDown,
                Runnable afterTokenCheck, UnaryOperator<CompletableFuture<Void>> inputReleaseSeam) {
            return new HostSession.Env(timeouts, clock, ticker, shuttingDown, afterTokenCheck, inputReleaseSeam,
                    input, adapters, geometry -> bounds = geometry, () -> beforeSenderStart.run());
        }

        HostSession session(long generation, byte[] token, java.net.InetAddress peer, long reservedAt,
                SessionLifecycle<HostPhase> lifecycle) {
            HostSession session = new HostSession(generation, token, peer, reservedAt, lifecycle,
                    env(Timeouts.DEFAULTS, System::nanoTime, null, () -> false, null, null));
            sessions.add(session);
            return session;
        }

        @Override public void close() {
            cores.forEach(HostCore::close);
            for (HostSession session : sessions) {
                session.close(new CloseInfo(Packet.DisconnectReason.USER, "test cleanup", false));
            }
            if (ownsInput) {
                try { input.shutdownAsync().get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Host fixture cleanup failed", failure); }
            }
        }
    }
}
