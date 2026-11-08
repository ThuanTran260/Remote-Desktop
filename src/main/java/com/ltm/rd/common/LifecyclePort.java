package com.ltm.rd.common;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Adapts a shell's lifecycle without exposing its phases to a role. */
public final class LifecyclePort<P extends Enum<P>> implements SessionPort {
    public interface Shell<P> {
        boolean isActive(P phase);
        boolean isActivationPending(P phase);
        boolean activationExpired(long nowNanos);
        CommandWriter writer();
        void tryActivate(SessionLifecycle.Tx<P> tx);
    }

    private final SessionLifecycle<P> lifecycle;
    private final long generation;
    private final Ticker ticker;
    private final LongSupplier clock;
    private final Shell<P> shell;

    public LifecyclePort(SessionLifecycle<P> lifecycle, long generation, Ticker ticker,
            LongSupplier clock, Shell<P> shell) {
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.generation = generation;
        this.ticker = ticker;
        this.clock = Objects.requireNonNull(clock);
        this.shell = Objects.requireNonNull(shell);
    }

    @Override public <R> R atomically(Function<SideTx, R> body) {
        Objects.requireNonNull(body);
        return lifecycle.atomically(tx -> body.apply(new View<>(this, tx)));
    }

    @Override public long generation() { return generation; }
    @Override public boolean isClosed() { return lifecycle.isClosed(); }
    @Override public boolean close(CloseInfo info) { return lifecycle.close(info); }

    @Override public CommandWriter.OfferResult offer(Packet p) {
        return lifecycle.atomically(tx -> {
            CommandWriter writer = shell.writer();
            return writer == null ? CommandWriter.OfferResult.CLOSED : writer.offer(p);
        });
    }

    @Override public Ticker.Registration register(Tickable t) {
        return ticker == null ? () -> {} : ticker.register(t);
    }

    @Override public void addStopHook(Runnable hook) { lifecycle.addStopHook(hook); }
    @Override public boolean startWorker(String name, Runnable body) { return lifecycle.startWorker(name, body); }

    @Override public void tryActivate(SideTx stx) {
        if (!(stx instanceof View<?> view) || view.port != this) {
            throw new IllegalArgumentException("SideTx belongs to another port");
        }
        view.activate();
    }

    private static final class View<P extends Enum<P>> implements SideTx {
        private final LifecyclePort<P> port;
        private final SessionLifecycle.Tx<P> tx;

        private View(LifecyclePort<P> port, SessionLifecycle.Tx<P> tx) {
            this.port = port;
            this.tx = tx;
        }

        @Override public boolean isClosed() { return tx.isClosed(); }
        @Override public boolean isActive() { return port.shell.isActive(tx.phase()); }
        @Override public boolean isActivationPending() { return port.shell.isActivationPending(tx.phase()); }
        @Override public boolean activationExpired() {
            tx.isClosed(); // validate lifetime before consulting the shell or clock
            return port.shell.activationExpired(port.clock.getAsLong());
        }
        @Override public boolean close(CloseInfo info) { return tx.close(info); }
        @Override public void after(Runnable action) { tx.after(action); }

        private void activate() {
            tx.isClosed(); // even a shell that ignores its argument must reject an expired transaction
            port.shell.tryActivate(tx);
        }
    }
}
