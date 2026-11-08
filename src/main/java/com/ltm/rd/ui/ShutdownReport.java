package com.ltm.rd.ui;

/** What a shutdown achieved within its budget. */
public record ShutdownReport(boolean socketsClosed, boolean workersStopped, InputRelease inputRelease) {}
