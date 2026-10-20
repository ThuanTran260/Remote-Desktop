package com.ltm.rd.ui;

/** Immediate answer of {@link Controller#connect}; the heavy work continues on {@code rd-app}. */
public enum ConnectOutcome { STARTED, INVALID_INPUT, BUSY, WAITING_RELEASE, INPUT_FAULTED, SHUTTING_DOWN, WRONG_ROLE }
