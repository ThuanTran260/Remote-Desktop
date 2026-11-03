package com.ltm.rd.host;

import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.Timeouts;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** Where and how the server listens. Port 0 binds an ephemeral port (tests). */
public record HostConfig(InetAddress bindAddress, int cmdPort, int imgPort, Timeouts timeouts,
        LongSupplier nanoClock, SecureRandom random, UnaryOperator<OutputStream> imageOutputDecorator) {

    public HostConfig {
        Objects.requireNonNull(bindAddress);
        Objects.requireNonNull(timeouts);
        Objects.requireNonNull(nanoClock);
        Objects.requireNonNull(random);
        Objects.requireNonNull(imageOutputDecorator);
    }

    public HostConfig(InetAddress bindAddress, int cmdPort, int imgPort, Timeouts timeouts,
            LongSupplier nanoClock, SecureRandom random) {
        this(bindAddress, cmdPort, imgPort, timeouts, nanoClock, random, UnaryOperator.identity());
    }

    public static HostConfig defaults() {
        return new HostConfig(new InetSocketAddress(0).getAddress(), Protocol.CMD_PORT, Protocol.IMG_PORT,
                Timeouts.DEFAULTS, System::nanoTime, new SecureRandom());
    }
}
