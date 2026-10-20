package com.ltm.rd.ui;

/** Answer of {@link Controller#sendInput}. */
public enum SendResult { SENT, STALE_GENERATION, STALE_EPOCH, GATE_CLOSED, OVERFLOW, NO_SESSION, SHUTTING_DOWN, WRONG_ROLE }
