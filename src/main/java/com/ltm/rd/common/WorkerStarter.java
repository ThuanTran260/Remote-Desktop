package com.ltm.rd.common;

/** Starts a named background worker; returns false when the owner refuses (e.g. session closed). */
@FunctionalInterface
public interface WorkerStarter {
    boolean start(String name, Runnable body);
}
