package com.ltm.rd.ui;

import com.ltm.rd.common.Timeouts;
import java.time.Duration;

/** Timeouts for runtime tests: generous deadlines (nothing may fire by accident) and a short close grace. */
final class TestTimeouts {
    private TestTimeouts() {}

    static Timeouts relaxed() {
        return new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofMillis(200), Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20), Duration.ofSeconds(5),
                Duration.ofSeconds(10), Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ZERO,
                Duration.ofSeconds(30), Duration.ofMillis(100));
    }
}
