package com.ltm.rd.ui;

import com.ltm.rd.common.Packet;
import java.util.concurrent.CompletableFuture;

/**
 * The only thing the UI sees of the application. State-changing calls may be made from the EDT:
 * they decide inside a short lock and hand the blocking work to {@code rd-app}.
 */
public interface Controller {
    /** Fast read without I/O; only the snapshot pump calls it, never the EDT. */
    Snapshot snapshot();

    /** HOST; IDLE or FAILED to STARTING to LISTENING. */
    CompletableFuture<Void> startHost();

    /** HOST; true only while LISTENING. */
    boolean regenerateCode();

    /** VIEWER; returns at once, the heavy work runs on {@code rd-app}. */
    ConnectOutcome connect(String host, String code);

    /** VIEWER; true if the session is still CONNECTING, AUTHENTICATING or ATTACHING (or being prepared). */
    boolean cancel();

    /** Any role; true if there was a session to close. */
    boolean disconnect();

    /** VIEWER; the input is sent only if generation and epoch still describe what the user saw. */
    SendResult sendInput(long generation, long inputEpoch, Packet inputPacket);

    /** VIEWER; true if RELEASE_ALL was queued for writing. */
    boolean releaseControlInput(long generation);

    /** The same future for every call. */
    CompletableFuture<ShutdownReport> shutdownAsync();
}
