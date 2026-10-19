package com.ltm.rd.server;

import com.ltm.rd.common.Packet.AuthStatus;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Owns the one-time code and the single session slot. Lock order: manager, then session; nothing
 * here calls a session's {@code atomically} while holding the manager lock.
 *
 * <p>Codes and tokens are never logged.
 */
public final class SessionManager {
    /**
     * Runs under the manager lock, so it must only construct an object that is not running yet: no
     * ticker, no thread, no {@code atomically}. Registration with the ticker and wiring of
     * {@code socketsClosed()} happen after {@link #tryReserve} returns.
     */
    @FunctionalInterface
    public interface SessionFactory {
        ServerSession create(long generation, byte[] token, InetAddress peer, long reservedAtNanos);
    }

    private final SecureRandom random;
    private final AuthGuard guard;
    private final Timeouts timeouts;
    private final LongSupplier nanoClock;
    private final SessionFactory factory;

    private final Object lock = new Object();
    private String code; // guarded by lock
    private ServerSession current; // guarded by lock; present from reservation until sockets are closed
    private long generation; // guarded by lock
    private volatile boolean shuttingDown;

    public SessionManager(SecureRandom random, AuthGuard guard, Timeouts timeouts, LongSupplier nanoClock,
            SessionFactory factory) {
        this.random = Objects.requireNonNull(random);
        this.guard = Objects.requireNonNull(guard);
        this.timeouts = Objects.requireNonNull(timeouts);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.factory = Objects.requireNonNull(factory);
        this.code = newCode(null);
    }

    public Timeouts timeouts() {
        return timeouts;
    }

    public String currentCode() {
        synchronized (lock) {
            return code;
        }
    }

    /** Replaces the code; refused while a session exists or the server is shutting down. */
    public boolean regenerateCode() {
        synchronized (lock) {
            if (current != null || shuttingDown) return false;
            code = newCode(code);
            return true;
        }
    }

    /** Checks in spec order: version, BUSY (any session, even CLOSING, or shutdown), LOCKED, code. */
    public ReserveResult tryReserve(InetAddress ip, int version, String presentedCode) {
        Objects.requireNonNull(ip);
        synchronized (lock) {
            if (version != Protocol.VERSION) return new ReserveResult(AuthStatus.BAD_VERSION, null);
            if (current != null || shuttingDown) return new ReserveResult(AuthStatus.BUSY, null);
            long now = nanoClock.getAsLong();
            if (guard.isLocked(ip, now)) return new ReserveResult(AuthStatus.LOCKED, null);
            if (!codeMatches(presentedCode)) {
                guard.recordFailure(ip, now);
                return new ReserveResult(AuthStatus.WRONG_CODE, null);
            }
            guard.recordSuccess(ip);
            byte[] token = new byte[Protocol.TOKEN_LEN];
            random.nextBytes(token);
            ServerSession session = factory.create(generation + 1, token, ip, now);
            generation++;
            current = session;
            return new ReserveResult(AuthStatus.OK, session);
        }
    }

    public Optional<ServerSession> current() {
        synchronized (lock) {
            return Optional.ofNullable(current);
        }
    }

    /** Volatile read without the manager lock, so it is safe to call from inside a session lock. */
    public boolean shuttingDown() {
        return shuttingDown;
    }

    public void setShuttingDown() {
        shuttingDown = true;
    }

    /** Releases the slot and issues a new code; ignored for a session that is no longer current. */
    public void onSessionSocketsClosed(ServerSession s) {
        synchronized (lock) {
            if (s != current) return;
            current = null;
            if (!shuttingDown) code = newCode(code);
        }
    }

    private boolean codeMatches(String presented) {
        if (presented == null) return false;
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
    }

    /** A fresh six-digit code that differs from {@code previous}. */
    private String newCode(String previous) {
        String next;
        do {
            next = String.format("%0" + Protocol.CODE_LEN + "d", random.nextInt(1_000_000));
        } while (next.equals(previous));
        return next;
    }
}
