package com.ltm.rd.ui;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.common.Timeouts;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Everything a runtime needs besides its role; tests shorten timeouts and inject a clock. */
public record AppConfig(InetAddress bindAddress, int cmdPort, int imgPort, String presetHost, Timeouts timeouts,
        LongSupplier nanoClock, Duration shutdownBudget) {

    public AppConfig {
        Objects.requireNonNull(bindAddress);
        Objects.requireNonNull(timeouts);
        Objects.requireNonNull(nanoClock);
        Objects.requireNonNull(shutdownBudget);
    }

    /** Defaults for production: wildcard bind, system clock, a five second shutdown budget. */
    public static AppConfig from(LaunchOptions o) {
        return new AppConfig(new InetSocketAddress(0).getAddress(), o.cmdPort(), o.imgPort(), o.host(),
                Timeouts.DEFAULTS, System::nanoTime, Duration.ofSeconds(5));
    }
}
