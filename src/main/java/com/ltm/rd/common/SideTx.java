package com.ltm.rd.common;

/** Role view of a session, valid only within the port's current atomic callback. */
public interface SideTx {
    boolean isClosed();
    boolean isActive();
    boolean isActivationPending();
    boolean activationExpired();
    boolean close(CloseInfo info);
    void after(Runnable action);
}
