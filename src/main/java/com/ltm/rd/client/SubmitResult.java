package com.ltm.rd.client;

public enum SubmitResult {
    ACCEPTED,
    /** The generation is not the currently open one. */
    STALE,
    /** The bounded deque is full; the caller must close the session. */
    OVERFLOW
}
