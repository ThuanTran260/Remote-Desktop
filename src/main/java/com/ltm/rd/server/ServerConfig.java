package com.ltm.rd.server;

import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Where and how the server listens. Port 0 binds an ephemeral port (tests). */
public record ServerConfig(InetAddress bindAddress, int cmdPort, int imgPort, Timeouts timeouts,
        LongSupplier nanoClock, SecureRandom random) {

    public ServerConfig {
        Objects.requireNonNull(bindAddress);
        Objects.requireNonNull(timeouts);
        Objects.requireNonNull(nanoClock);
        Objects.requireNonNull(random);
    }

    public static ServerConfig defaults() {
        return new ServerConfig(new InetSocketAddress(0).getAddress(), Protocol.CMD_PORT, Protocol.IMG_PORT,
                Timeouts.DEFAULTS, System::nanoTime, new SecureRandom());
    }
}
