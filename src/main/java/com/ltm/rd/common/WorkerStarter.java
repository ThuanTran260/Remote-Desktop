package com.ltm.rd.common;

/** Starts a named background worker; returns false when the owner refuses (e.g. session closed). */
@FunctionalInterface
public interface WorkerStarter {
    /**
     * @param onSkipped run by the owner, outside any lock, when it accepted the start but the body
     *     will never run (e.g. the session closed before the thread ran)
     */
    boolean start(String name, Runnable body, Runnable onSkipped);
}
