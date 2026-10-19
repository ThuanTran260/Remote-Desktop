package com.ltm.rd.common;

import java.util.Objects;

/** Why a session is closing and whether a DISCONNECT packet should be sent to the peer. */
public record CloseInfo(Packet.DisconnectReason reason, String message, boolean sendDisconnect) {
    public CloseInfo {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(message);
    }
}
