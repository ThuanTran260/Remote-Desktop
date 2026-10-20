package com.ltm.rd.client;

/** Screen or input adapters could not be created; sharing must not start. */
public class AdapterException extends Exception {
    public enum Kind { HEADLESS, ROBOT_UNAVAILABLE, NO_SCREEN }

    private final Kind kind;

    public AdapterException(Kind kind, Throwable cause) {
        super(kind.name(), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
