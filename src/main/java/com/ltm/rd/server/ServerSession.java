package com.ltm.rd.server;

import com.ltm.rd.common.SessionLifecycle;
import java.net.InetAddress;
import java.util.Objects;

/**
 * Server side of one session. Skeleton: identity and lifecycle only; handshake completion,
 * channel attachment and workers are added with the server handshake.
 */
public final class ServerSession {
    private final long generation;
    private final byte[] token;
    private final InetAddress peer;
    private final long reservedAtNanos;
    private final SessionLifecycle<ServerPhase> lifecycle;

    public ServerSession(long generation, byte[] token, InetAddress peer, long reservedAtNanos,
            SessionLifecycle<ServerPhase> lifecycle) {
        this.generation = generation;
        this.token = Objects.requireNonNull(token).clone();
        this.peer = Objects.requireNonNull(peer);
        this.reservedAtNanos = reservedAtNanos;
        this.lifecycle = Objects.requireNonNull(lifecycle);
    }

    public long generation() {
        return generation;
    }

    /** Copy of the image-channel token; callers cannot alter the session's own copy. */
    public byte[] token() {
        return token.clone();
    }

    public SessionLifecycle<ServerPhase> lifecycle() {
        return lifecycle;
    }

    public InetAddress peer() {
        return peer;
    }

    public long reservedAtNanos() {
        return reservedAtNanos;
    }
}
