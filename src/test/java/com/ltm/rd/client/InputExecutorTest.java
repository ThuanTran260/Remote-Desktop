package com.ltm.rd.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InputExecutorTest {
    private static final int T = 2; // bounded wait, seconds
    private static final int SENTINEL = 9999;
    private int flushCount;

    private FakeInputDevice device;
    private AtomicReference<Rectangle> bounds;
    private List<Throwable> faults;
    private InputExecutor exec;
    private final List<FakeInputDevice.Gate> gates = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        device = new FakeInputDevice();
        bounds = new AtomicReference<>(new Rectangle(0, 0, 1000, 1000));
        faults = new CopyOnWriteArrayList<>();
        exec = newExecutor(256);
    }

    @AfterEach
    void tearDown() throws Exception {
        gates.forEach(FakeInputDevice.Gate::open);
        exec.shutdownAsync().get(T, TimeUnit.SECONDS);
    }

    private InputExecutor newExecutor(int capacity) {
        return new InputExecutor(device, bounds::get, capacity,
                InputExecutor.defaultThreadFactory(), faults::add);
    }

    private FakeInputDevice.Gate block(String call) {
        FakeInputDevice.Gate g = device.blockOn(call);
        gates.add(g);
        return g;
    }

    /** Waits until the worker has processed everything submitted before this call. */
    private void flush(long gen) throws Exception {
        int marker = SENTINEL + flushCount++;
        assertEquals(SubmitResult.ACCEPTED, exec.submit(gen, new InputCommand.Wheel(marker)));
        assertTrue(device.awaitCall("mouseWheel:" + marker));
    }

    private static void awaitDone(CompletableFuture<Void> f) throws Exception {
        f.get(T, TimeUnit.SECONDS);
    }

    @Test
    void defaultFactoryNamesWorkerRdInput() {
        assertEquals("rd-input", InputExecutor.defaultThreadFactory().newThread(() -> { }).getName());
    }

    @Test
    void beginThenImmediateSubmitIsNotLost() throws Exception {
        assertTrue(exec.beginSession(1));
        assertEquals(SubmitResult.ACCEPTED, exec.submit(1, new InputCommand.KeyPress(65)));
        assertTrue(device.awaitCall("keyPress:65"));
    }

    @Test
    void beginThenEndBeforeAnyClaimDoesNotResurrectGeneration() throws Exception {
        assertTrue(exec.beginSession(1));
        awaitDone(exec.endSession(1));
        assertEquals(InputExecutor.State.IDLE, exec.state());
        assertEquals(SubmitResult.STALE, exec.submit(1, new InputCommand.KeyPress(65)));
        assertFalse(exec.beginSession(1), "generations are never reused");
        assertTrue(exec.beginSession(2));
        assertEquals(List.of(), device.log());
    }

    @Test
    void endWhileCommandRunningReturnsImmediatelyAndDropsQueued() throws Exception {
        FakeInputDevice.Gate gate = block("keyPress:65");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        assertTrue(gate.awaitEntered());
        exec.submit(1, new InputCommand.KeyPress(66));
        exec.submit(1, new InputCommand.KeyPress(67));

        CompletableFuture<Void> end = exec.endSession(1);
        assertFalse(end.isDone());
        assertEquals(InputExecutor.State.ENDING, exec.state());

        gate.open();
        awaitDone(end);
        assertEquals(List.of("keyPress:65", "keyRelease:65"), device.log());
        assertEquals(InputExecutor.State.IDLE, exec.state());
    }

    @Test
    void fullDequeOverflowsButEndStillReleases() throws Exception {
        exec.shutdownAsync().get(T, TimeUnit.SECONDS);
        exec = newExecutor(3);
        FakeInputDevice.Gate gate = block("keyPress:65");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        assertTrue(gate.awaitEntered());
        for (int i = 0; i < 3; i++) {
            assertEquals(SubmitResult.ACCEPTED, exec.submit(1, new InputCommand.KeyPress(70 + i)));
        }
        assertEquals(SubmitResult.OVERFLOW, exec.submit(1, new InputCommand.KeyPress(80)));

        CompletableFuture<Void> end = exec.endSession(1);
        gate.open();
        awaitDone(end);
        assertEquals(List.of("keyPress:65", "keyRelease:65"), device.log());
    }

    @Test
    void repeatedEndReturnsSameFutureAndAddsNoWork() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        assertTrue(device.awaitCall("keyPress:65"));
        CompletableFuture<Void> first = exec.endSession(1);
        assertSame(first, exec.endSession(1));
        awaitDone(first);
        assertSame(first, exec.endSession(1));
        assertEquals(List.of("keyPress:65", "keyRelease:65"), device.log());
    }

    @Test
    void endForGenerationThatNeverBeganIsCompletedNoOp() {
        CompletableFuture<Void> f = exec.endSession(42);
        assertTrue(f.isDone());
        assertFalse(f.isCompletedExceptionally());
        assertEquals(InputExecutor.State.IDLE, exec.state());
        assertTrue(exec.beginSession(1), "no-op end must not consume generations");
    }

    @Test
    void beginRefusedWhileEndingAndAllowedAfterSuccessfulRelease() throws Exception {
        FakeInputDevice.Gate gate = block("keyRelease:65");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        flush(1);
        CompletableFuture<Void> end = exec.endSession(1);
        assertTrue(gate.awaitEntered());
        assertFalse(exec.beginSession(2));
        gate.open();
        awaitDone(end);
        assertTrue(exec.beginSession(2));
    }

    @Test
    void staleEndDoesNotAffectNewSession() throws Exception {
        assertTrue(exec.beginSession(1));
        awaitDone(exec.endSession(1));
        assertTrue(exec.beginSession(2));
        exec.submit(2, new InputCommand.KeyPress(65));
        assertTrue(device.awaitCall("keyPress:65"));

        CompletableFuture<Void> stale = exec.endSession(1);
        assertTrue(stale.isDone());
        assertEquals(InputExecutor.State.OPEN, exec.state());
        assertEquals(SubmitResult.ACCEPTED, exec.submit(2, new InputCommand.KeyRelease(65)));
        flush(2);
        assertEquals(List.of("keyPress:65", "keyRelease:65", "mouseWheel:" + SENTINEL), device.log());
    }

    @Test
    void releaseFailureFaultsAfterTryingEverythingAndRefusesBegin() throws Exception {
        device.failOn("keyRelease:65");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        exec.submit(1, new InputCommand.KeyPress(66));
        flush(1);

        CompletableFuture<Void> end = exec.endSession(1);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> end.get(T, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertTrue(device.log().contains("keyRelease:66"), "continues after a failed release");
        assertEquals(InputExecutor.State.FAULTED, exec.state());
        assertEquals(1, faults.size());
        assertNotNull(exec.lastFault());
        assertFalse(exec.beginSession(2));
    }

    @Test
    void deviceBlockedForeverKeepsEndingThenRecoversWhenUnblocked() throws Exception {
        FakeInputDevice.Gate gate = block("keyRelease:65");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        flush(1);

        CompletableFuture<Void> end = exec.endSession(1);
        assertTrue(gate.awaitEntered());
        assertThrows(TimeoutException.class, () -> end.get(200, TimeUnit.MILLISECONDS));
        assertEquals(InputExecutor.State.ENDING, exec.state());
        assertFalse(exec.beginSession(2));

        gate.open();
        awaitDone(end);
        assertEquals(InputExecutor.State.IDLE, exec.state());
        assertTrue(exec.beginSession(2));
        assertEquals(SubmitResult.ACCEPTED, exec.submit(2, new InputCommand.Wheel(1)));
    }

    @Test
    void buttonReleaseStillAttemptedAfterBoundsShrink() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.MousePress(500, 500, 1));
        flush(1);
        bounds.set(new Rectangle(0, 0, 100, 100));
        exec.submit(1, new InputCommand.MouseRelease(500, 500, 1));
        flush(1);
        assertEquals(List.of("mouseMove:500,500", "mousePress:1", "mouseWheel:" + SENTINEL,
                "mouseRelease:1", "mouseWheel:" + (SENTINEL + 1)), device.log());
    }

    @Test
    void mouseCoordinatesAreOffsetByBoundsOriginAndInvalidOnesSkipped() throws Exception {
        bounds.set(new Rectangle(100, 50, 200, 100));
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.MouseMove(10, 20));
        exec.submit(1, new InputCommand.MouseMove(200, 20)); // x == width: outside
        exec.submit(1, new InputCommand.MousePress(-1, 5, 1)); // outside: no press
        exec.submit(1, new InputCommand.MousePress(0, 0, 2));
        flush(1);
        assertEquals(List.of("mouseMove:110,70", "mouseMove:100,50", "mousePress:2",
                "mouseWheel:" + SENTINEL), device.log());
    }

    @Test
    void duplicateMousePressIsIgnored() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.MousePress(1, 1, 1));
        exec.submit(1, new InputCommand.MousePress(1, 1, 1));
        flush(1);
        assertEquals(1, device.log().stream().filter("mousePress:1"::equals).count());
    }

    @Test
    void duplicateKeyPressIsForwardedForRepeat() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        exec.submit(1, new InputCommand.KeyPress(65));
        flush(1);
        assertEquals(2, device.log().stream().filter("keyPress:65"::equals).count());
    }

    @Test
    void releaseOfNonHeldKeyAndButtonNotForwarded() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyRelease(65));
        exec.submit(1, new InputCommand.MouseRelease(1, 1, 1));
        flush(1);
        assertEquals(List.of("mouseWheel:" + SENTINEL), device.log());
    }

    @Test
    void callOrderAcrossTwoSessions() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        assertTrue(device.awaitCall("keyPress:65"));
        awaitDone(exec.endSession(1));
        assertTrue(exec.beginSession(2));
        exec.submit(2, new InputCommand.KeyPress(66));
        assertTrue(device.awaitCall("keyPress:66"));
        assertEquals(List.of("keyPress:65", "keyRelease:65", "keyPress:66"), device.log());
    }

    @Test
    void releaseAllMidSessionKeepsGenerationOpen() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        exec.submit(1, new InputCommand.MousePress(1, 1, 3));
        exec.submit(1, new InputCommand.ReleaseAll());
        flush(1);
        assertEquals(List.of("keyPress:65", "mouseMove:1,1", "mousePress:3", "keyRelease:65",
                "mouseRelease:3", "mouseWheel:" + SENTINEL), device.log());
        assertEquals(InputExecutor.State.OPEN, exec.state());
    }

    @Test
    void releaseAllFailureFaultsModule() throws Exception {
        device.failOn("keyRelease:65");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        exec.submit(1, new InputCommand.ReleaseAll());
        assertTrue(device.awaitCall("keyRelease:65"));
        awaitFault();
        assertEquals(InputExecutor.State.FAULTED, exec.state());
        assertEquals(SubmitResult.STALE, exec.submit(1, new InputCommand.Wheel(1)));
        // The session close still gets a (failed) end result and another release attempt.
        CompletableFuture<Void> end = exec.endSession(1);
        assertThrows(ExecutionException.class, () -> end.get(T, TimeUnit.SECONDS));
        assertFalse(exec.beginSession(2));
    }

    @Test
    void unsupportedKeyIsSkippedWithoutFault() throws Exception {
        device.illegalArgOn("keyPress:999");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(999));
        exec.submit(1, new InputCommand.KeyPress(65));
        flush(1);
        assertTrue(faults.isEmpty());
        assertEquals(InputExecutor.State.OPEN, exec.state());
        awaitDone(exec.endSession(1));
        assertEquals(List.of("keyPress:999", "keyPress:65", "mouseWheel:" + SENTINEL, "keyRelease:65"),
                device.log());
    }

    @Test
    void unexpectedExceptionFaultsModule() throws Exception {
        device.failOn("mouseWheel:5");
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.Wheel(5));
        assertTrue(device.awaitCall("mouseWheel:5"));
        awaitFault();
        assertEquals(InputExecutor.State.FAULTED, exec.state());
        assertFalse(exec.beginSession(2));
    }

    @Test
    void shutdownEndsCurrentGenerationThenStopsWorker() throws Exception {
        assertTrue(exec.beginSession(1));
        exec.submit(1, new InputCommand.KeyPress(65));
        assertTrue(device.awaitCall("keyPress:65"));
        awaitDone(exec.shutdownAsync());
        assertEquals(InputExecutor.State.STOPPED, exec.state());
        assertEquals(List.of("keyPress:65", "keyRelease:65"), device.log());
        assertFalse(exec.beginSession(2));
        assertEquals(SubmitResult.STALE, exec.submit(1, new InputCommand.Wheel(1)));
        assertNull(exec.lastFault());
    }

    private void awaitFault() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(T);
        while (faults.isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(faults.isEmpty(), "onFault was not called");
    }
}
