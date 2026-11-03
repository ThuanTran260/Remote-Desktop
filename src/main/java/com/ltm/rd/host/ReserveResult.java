package com.ltm.rd.host;

import com.ltm.rd.common.Packet;

/** Outcome of a reservation attempt; {@code session} is non-null only when the status is OK. */
public record ReserveResult(Packet.AuthStatus status, HostSession session) {
    public ReserveResult {
        if ((status == Packet.AuthStatus.OK) != (session != null)) {
            throw new IllegalArgumentException("session must be present exactly when status is OK");
        }
    }
}
