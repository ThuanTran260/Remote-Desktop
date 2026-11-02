package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.EventQueue;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class GuiAppTest {
    private void verify(ShutdownReport report, boolean exceptional, boolean warns) throws Exception {
        FakeController controller = new FakeController();
        List<String> events = new CopyOnWriteArrayList<>();
        EventQueue.invokeAndWait(() -> GuiApp.exitAfterShutdown(controller, text -> {
            assertTrue(EventQueue.isDispatchThread());
            assertEquals("Chưa xác nhận dọn sạch kết nối, luồng và phím/chuột. Hãy kiểm tra máy Host trước khi tiếp tục.", text);
            events.add("warning");
        }, () -> {
            assertTrue(EventQueue.isDispatchThread());
            events.add("exit");
        }));
        assertTrue(events.isEmpty(), "shutdown request returns while cleanup is pending");
        CompletableFuture.runAsync(() -> {
            if (exceptional) controller.shutdownFuture().completeExceptionally(new IllegalStateException("secret123456"));
            else controller.shutdownFuture().complete(report);
        }).get(5, TimeUnit.SECONDS);
        EventQueue.invokeAndWait(() -> { });
        assertEquals(warns ? List.of("warning", "exit") : List.of("exit"), events);
    }

    @Test void uncleanShutdownWarnsOnEdtBeforeExit() throws Exception {
        for (InputRelease input : InputRelease.values()) {
            for (boolean sockets : new boolean[] {false, true}) {
                for (boolean workers : new boolean[] {false, true}) {
                    boolean warns = !sockets || !workers || input == InputRelease.FAILED || input == InputRelease.UNCONFIRMED;
                    if (warns) verify(new ShutdownReport(sockets, workers, input), false, true);
                }
            }
        }
    }

    @Test void cleanShutdownExitsWithoutWarning() throws Exception {
        verify(new ShutdownReport(true, true, InputRelease.NOT_NEEDED), false, false);
        verify(new ShutdownReport(true, true, InputRelease.RELEASED), false, false);
    }

    @Test void shutdownFailureWarnsBeforeExit() throws Exception {
        verify(null, true, true);
        verify(null, false, true);
    }
}
