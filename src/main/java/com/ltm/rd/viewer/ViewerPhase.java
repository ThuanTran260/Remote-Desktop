package com.ltm.rd.viewer;

/** Phases of a client-side session; CLOSING is the lifecycle's terminal phase. */
public enum ViewerPhase { CONNECTING, AUTHENTICATING, ATTACHING, ACTIVE, CLOSING }
