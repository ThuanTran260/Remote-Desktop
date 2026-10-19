package com.ltm.rd.common;

/**
 * A packet or value violates the protocol. Unchecked so that packet factories stay usable
 * without throws clauses; reader loops must catch it explicitly (it is not an IOException).
 */
public class ProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
