package com.ltm.rd.common;

/** Thrown when something is attached to a session that is already closed. Unchecked on purpose. */
public class ClosedSessionException extends IllegalStateException {
    public ClosedSessionException(String message) {
        super(message);
    }
}
