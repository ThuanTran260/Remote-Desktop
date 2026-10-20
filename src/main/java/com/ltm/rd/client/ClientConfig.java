package com.ltm.rd.client;

import com.ltm.rd.common.Timeouts;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * Where and how the client connects. {@code imageOutputDecorator} wraps the raw image socket
 * output before counting and buffering (identity by default; tests use it to throttle).
 */
public record ClientConfig(String host, int cmdPort, int imgPort, String code, Timeouts timeouts,
        LongSupplier nanoClock, UnaryOperator<OutputStream> imageOutputDecorator) {

    public ClientConfig {
        Objects.requireNonNull(host);
        Objects.requireNonNull(code);
        Objects.requireNonNull(timeouts);
        Objects.requireNonNull(nanoClock);
        if (imageOutputDecorator == null) imageOutputDecorator = UnaryOperator.identity();
    }

    public ClientConfig(String host, int cmdPort, int imgPort, String code, Timeouts timeouts,
            LongSupplier nanoClock) {
        this(host, cmdPort, imgPort, code, timeouts, nanoClock, UnaryOperator.identity());
    }

    @Override
    public String toString() { // never print the code
        return "ClientConfig[host=" + host + ", cmdPort=" + cmdPort + ", imgPort=" + imgPort + "]";
    }
}
