package com.ltm.rd.ui;

/** What the user interface shows; derived from the current session objects, never stored as a flag. */
public enum UiPhase { IDLE, STARTING, LISTENING, RESERVED, CONNECTING, AUTHENTICATING, ATTACHING, ACTIVE,
        CLOSING, FAILED, SHUTTING_DOWN }
