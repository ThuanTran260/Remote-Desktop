package com.ltm.rd.server;

/** Result of an epoch-checked input offer; gate, epoch and queueing are decided in one transaction. */
public enum InputOutcome { SENT, GATE_CLOSED, STALE_EPOCH, OVERFLOW }
