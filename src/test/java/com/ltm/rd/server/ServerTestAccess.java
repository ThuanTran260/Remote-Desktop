package com.ltm.rd.server;

import com.ltm.rd.common.SessionLifecycle;

/** Test-only bridge: re-exposes the package-private session lifecycle to integration tests in other packages. */
public final class ServerTestAccess {
    private ServerTestAccess() {}

    public static SessionLifecycle<ServerPhase> lifecycle(ServerSession session) {
        return session.lifecycle();
    }
}
