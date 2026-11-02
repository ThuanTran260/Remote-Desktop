package com.ltm.rd.client;

import com.ltm.rd.common.Timeouts;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Where and how the Viewer connects to the Host.
 */
public record ClientConfig(String host, int cmdPort, int imgPort, String code, Timeouts timeouts,
        LongSupplier nanoClock) {

    public ClientConfig {
        Objects.requireNonNull(host);
        Objects.requireNonNull(code);
        Objects.requireNonNull(timeouts);
        Objects.requireNonNull(nanoClock);
    }

    @Override
    public String toString() { // never print the code
        return "ClientConfig[host=" + host + ", cmdPort=" + cmdPort + ", imgPort=" + imgPort + "]";
    }
}
