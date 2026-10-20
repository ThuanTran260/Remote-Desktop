package com.ltm.rd.ui;

import com.ltm.rd.common.Packet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A Controller that records every input it is given as (generation, epoch, packet) with the result
 * it answered. The result is configurable; with a delegate the call is forwarded and the delegate's
 * answer is recorded.
 */
final class FakeController implements Controller {
    record Sent(long generation, long inputEpoch, Packet packet, SendResult result) {}

    private final Controller delegate;
    private final List<Sent> sent = new ArrayList<>();
    private final List<Long> releases = new ArrayList<>();
    private volatile SendResult result = SendResult.SENT;

    FakeController() {
        this(null);
    }

    FakeController(Controller delegate) {
        this.delegate = delegate;
    }

    void answer(SendResult result) {
        this.result = result;
    }

    synchronized List<Sent> sent() {
        return new ArrayList<>(sent);
    }

    synchronized List<Long> releases() {
        return new ArrayList<>(releases);
    }

    /** The recorded packets as short text, for readable assertions. */
    synchronized List<String> described() {
        List<String> out = new ArrayList<>();
        for (Sent s : sent) out.add(describe(s.packet()));
        return out;
    }

    static String describe(Packet p) {
        return switch (p.type()) {
            case MOUSE_MOVE -> "move " + p.pointerPayload().x() + "," + p.pointerPayload().y();
            case MOUSE_PRESS -> "press " + p.pointerPayload().x() + "," + p.pointerPayload().y() + ","
                    + p.pointerPayload().button();
            case MOUSE_RELEASE -> "release " + p.pointerPayload().x() + "," + p.pointerPayload().y() + ","
                    + p.pointerPayload().button();
            case WHEEL -> "wheel " + p.wheelNotches();
            case KEY_PRESS -> "keyPress " + p.keyCode();
            case KEY_RELEASE -> "keyRelease " + p.keyCode();
            default -> p.type().name();
        };
    }

    @Override
    public SendResult sendInput(long generation, long inputEpoch, Packet inputPacket) {
        SendResult answer = delegate == null ? result : delegate.sendInput(generation, inputEpoch, inputPacket);
        synchronized (this) {
            sent.add(new Sent(generation, inputEpoch, inputPacket, answer));
        }
        return answer;
    }

    @Override
    public boolean releaseControlInput(long generation) {
        synchronized (this) {
            releases.add(generation);
        }
        return delegate == null || delegate.releaseControlInput(generation);
    }

    @Override
    public Snapshot snapshot() {
        if (delegate != null) return delegate.snapshot();
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Void> startServer() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean regenerateCode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConnectOutcome connect(String host, String code) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean cancel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean disconnect() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ShutdownReport> shutdownAsync() {
        throw new UnsupportedOperationException();
    }
}
