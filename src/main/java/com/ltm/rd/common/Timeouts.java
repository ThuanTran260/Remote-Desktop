package com.ltm.rd.common;

import java.time.Duration;
import java.util.Objects;

/** Every deadline and period of a session in one place, so tests can shorten them. */
public record Timeouts(
        Duration handshake,
        Duration reserve,
        Duration clientAuth,
        Duration clientActivation,
        Duration pingInterval,
        Duration liveness,
        Duration cmdSoTimeout,
        Duration frameStall,
        Duration frameTimeout,
        Duration imgSoTimeout,
        Duration cmdWriteLimit,
        Duration imgWriteLimit,
        Duration closeGrace,
        Duration connect,
        Duration wrongCodeDelay,
        Duration ipLock,
        Duration tick) {

    public static final Timeouts DEFAULTS = new Timeouts(
            Duration.ofSeconds(3),
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(7),
            Duration.ofSeconds(2),
            Duration.ofSeconds(8),
            Duration.ofSeconds(10),
            Duration.ofSeconds(3),
            Duration.ofSeconds(15),
            Duration.ofSeconds(20),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofMillis(500),
            Duration.ofSeconds(3),
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofMillis(100));

    public Timeouts {
        Objects.requireNonNull(handshake);
        Objects.requireNonNull(reserve);
        Objects.requireNonNull(clientAuth);
        Objects.requireNonNull(clientActivation);
        Objects.requireNonNull(pingInterval);
        Objects.requireNonNull(liveness);
        Objects.requireNonNull(cmdSoTimeout);
        Objects.requireNonNull(frameStall);
        Objects.requireNonNull(frameTimeout);
        Objects.requireNonNull(imgSoTimeout);
        Objects.requireNonNull(cmdWriteLimit);
        Objects.requireNonNull(imgWriteLimit);
        Objects.requireNonNull(closeGrace);
        Objects.requireNonNull(connect);
        Objects.requireNonNull(wrongCodeDelay);
        Objects.requireNonNull(ipLock);
        Objects.requireNonNull(tick);
    }
}
