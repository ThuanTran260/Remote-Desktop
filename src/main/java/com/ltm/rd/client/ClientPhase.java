package com.ltm.rd.client;

/** Phases of a client-side session; CLOSING is the lifecycle's terminal phase. */
public enum ClientPhase { CONNECTING, AUTHENTICATING, ATTACHING, ACTIVE, CLOSING }
