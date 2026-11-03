package com.ltm.rd.it;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Deterministic event-queue checks, never an OS/Robot delivery test. */
@Timeout(10)
class TwoJvmInputRecorderTest {
    @Test
    void delayedExtraPairAfterExpected100RemainsObservable() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> { recordExpected(recorder); assertEquals(expected(), recorder.snapshot()); });
        CountDownLatch tailPosted = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            record(recorder, "P", KeyEvent.VK_A);
            record(recorder, "R", KeyEvent.VK_A);
            tailPosted.countDown();
        });
        assertTrue(tailPosted.await(2, TimeUnit.SECONDS));
        onEdt(() -> {
            List<String> observed = recorder.snapshot();
            assertEquals(102, observed.size(), "the recorder must retain events beyond the expected prefix");
            assertEquals(List.of("P65", "R65"), observed.subList(100, 102));
            assertTrue(recorder.heldEmpty());
            recorder.prepareProbe();
            recordProbe(recorder);
            assertEquals("UnexpectedNativeSequence", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
    }

    @Test
    void delayedLeftoverPressAfterExpected100RemainsObservable() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> { recordExpected(recorder); assertEquals(expected(), recorder.snapshot()); });
        SwingUtilities.invokeLater(() -> record(recorder, "P", KeyEvent.VK_B));
        onEdt(() -> {
            List<String> observed = recorder.snapshot();
            assertEquals(101, observed.size(), "a leftover held press must not disappear at 100");
            assertEquals("P66", observed.get(100));
            assertFalse(recorder.heldEmpty());
            assertEquals("HeldNativeInput", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
    }

    @Test
    void completionWaitsForInputStopAndNativeFenceBeforeValidatingTail() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> recordExpected(recorder));
        CountDownLatch inputStopEntered = new CountDownLatch(1);
        CountDownLatch inputStopped = new CountDownLatch(1);
        CountDownLatch nativeFenceEntered = new CountDownLatch(1);
        CountDownLatch nativeDrained = new CountDownLatch(1);
        var owner = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = owner.submit(() -> TwoJvmInputCompletion.finish(recorder, () -> {
                inputStopEntered.countDown();
                assertTrue(inputStopped.await(2, TimeUnit.SECONDS));
                return null;
            }, () -> {
                nativeFenceEntered.countDown();
                try { assertTrue(nativeDrained.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                postProbe(recorder);
            }));
            assertTrue(inputStopEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1, nativeFenceEntered.getCount(), "native synchronization cannot precede input stop");
            inputStopped.countDown();
            assertTrue(nativeFenceEntered.await(2, TimeUnit.SECONDS));
            onEdt(() -> {
                assertEquals(expected(), recorder.snapshot());
                record(recorder, "P", KeyEvent.VK_A);
                record(recorder, "R", KeyEvent.VK_A);
            });
            assertFalse(result.isDone(), "a blocked native fence cannot close recording");
            nativeDrained.countDown();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertEquals(TwoJvmDiagnostics.Reason.UnexpectedNativeSequence, TwoJvmDiagnostics.reason(failure));
        } finally {
            inputStopped.countDown();
            nativeDrained.countDown();
            owner.shutdownNow();
            assertTrue(owner.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedExpected100AndTwoProbeHasEmptyHeldState() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> recordExpected(recorder));
        List<String> all = new ArrayList<>(expected());
        all.addAll(List.of("P61451", "R61451"));
        assertEquals(all, TwoJvmInputCompletion.finish(recorder, () -> null, () -> postProbe(recorder)));
        onEdt(() -> assertTrue(recorder.heldEmpty()));
    }

    @Test
    void failedNativeFenceCannotReturnAnExpectedPrefix() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> recordExpected(recorder));
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> TwoJvmInputCompletion.finish(recorder, () -> null,
                        () -> { throw new IllegalStateException("NativeFenceFailed"); }));
        assertEquals(TwoJvmDiagnostics.Reason.NativeFenceFailed, TwoJvmDiagnostics.reason(failure));
        onEdt(() -> {
            record(recorder, "P", KeyEvent.VK_B);
            assertEquals(101, recorder.snapshot().size(), "failure must leave recording available for cleanup/diagnosis");
        });
    }

    @Test
    void blockedNativeFenceHasBoundedFailureWithoutClosingRecorder() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> recordExpected(recorder));
        CountDownLatch nativeEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        long started = System.nanoTime();
        try {
            var failure = assertThrows(IllegalStateException.class,
                    () -> TwoJvmInputCompletion.finish(recorder, () -> null, () -> {
                        nativeEntered.countDown();
                        try { assertTrue(release.await(6, TimeUnit.SECONDS)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }));
            assertEquals("NativeFenceTimeout", failure.getMessage());
            assertEquals(0, nativeEntered.getCount());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
            onEdt(() -> {
                record(recorder, "P", KeyEvent.VK_A);
                assertEquals(101, recorder.snapshot().size());
            });
        } finally { release.countDown(); }
    }

    @Test
    void expected100CannotFinalizeWithoutObservedProbe() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            assertEquals("NativeProbeMissing", assertThrows(IllegalStateException.class, recorder::finish,
                    "a complete application prefix is not a native delivery boundary").getMessage());
        });
    }

    @Test
    void exact100ApplicationEventsAndExplicitTwoProbeSuffixAreAccepted() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recorder.prepareProbe();
            recordProbe(recorder);
            List<String> all = new ArrayList<>(expected());
            all.addAll(List.of("P61451", "R61451"));
            assertEquals(all, recorder.finish());
            assertTrue(recorder.heldEmpty());
        });
    }

    @Test
    void recordingRetainsLatePostCompletionEventsForCleanupAudit() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recorder.prepareProbe();
            recordProbe(recorder);
            recorder.finish();
            record(recorder, "P", KeyEvent.VK_B);
            assertEquals(103, recorder.snapshot().size(), "completion cannot silently discard later input");
            assertEquals("LateNativeInput", assertThrows(IllegalStateException.class, recorder::audit).getMessage());
        });
    }

    @Test
    void missingProbeReleaseCannotAcknowledgeCompletion() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recorder.prepareProbe();
            record(recorder, "P", KeyEvent.VK_F24);
            assertEquals("HeldNativeInput", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
        assertThrows(java.util.concurrent.TimeoutException.class, () -> recorder.awaitProbe(java.time.Duration.ofMillis(50)));
    }

    @Test
    void wrongProbeCannotAcknowledgeCompletion() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recorder.prepareProbe();
            record(recorder, "P", KeyEvent.VK_F23);
            record(recorder, "R", KeyEvent.VK_F23);
            assertEquals("ModifiersOrUnexpectedKey", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
        assertThrows(java.util.concurrent.TimeoutException.class, () -> recorder.awaitProbe(java.time.Duration.ofMillis(50)));
    }

    @Test
    void duplicateProbePressIsRejected() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recorder.prepareProbe();
            record(recorder, "P", KeyEvent.VK_F24);
            record(recorder, "P", KeyEvent.VK_F24);
            record(recorder, "R", KeyEvent.VK_F24);
            assertEquals("InvalidProbeSequence", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
    }

    @Test
    void reorderedProbeReleaseIsRejected() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recorder.prepareProbe();
            record(recorder, "R", KeyEvent.VK_F24);
            record(recorder, "P", KeyEvent.VK_F24);
            assertEquals("InvalidProbeSequence", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
    }

    @Test
    void earlyProbeCannotBecomeValidWhenLaterPrepared() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recordProbe(recorder);
            recorder.prepareProbe();
            assertEquals("InvalidProbeSequence", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
    }

    @Test
    void applicationTailBetweenProbeAndValidationIsRejected() throws Exception {
        TwoJvmInputRecorder recorder = new TwoJvmInputRecorder();
        onEdt(() -> {
            recordExpected(recorder);
            recorder.prepareProbe();
            recordProbe(recorder);
            record(recorder, "P", KeyEvent.VK_A);
            record(recorder, "R", KeyEvent.VK_A);
            assertEquals("LateNativeInput", assertThrows(IllegalStateException.class, recorder::finish).getMessage());
        });
    }

    @Test
    void headlessNativeProbeIsRejectedBeforeRobotConstruction() {
        assertTrue(java.awt.GraphicsEnvironment.isHeadless());
        assertEquals("NativeFenceUnsupported", assertThrows(IllegalStateException.class,
                TwoJvmInputCompletion::requireSupportedDesktop).getMessage());
    }

    private static void postProbe(TwoJvmInputRecorder recorder) {
        SwingUtilities.invokeLater(() -> recordProbe(recorder));
    }

    private static void recordProbe(TwoJvmInputRecorder recorder) {
        record(recorder, "P", KeyEvent.VK_F24);
        record(recorder, "R", KeyEvent.VK_F24);
    }

    static void onEdt(Runnable action) throws Exception {
        FutureTask<Void> work = new FutureTask<>(action, null);
        SwingUtilities.invokeLater(work);
        try { work.get(2, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private static void recordExpected(TwoJvmInputRecorder recorder) {
        recorder.arm();
        for (int pair = 0; pair < 50; pair++) {
            int key = pair % 2 == 0 ? KeyEvent.VK_A : KeyEvent.VK_B;
            record(recorder, "P", key);
            record(recorder, "R", key);
        }
    }

    private static void record(TwoJvmInputRecorder recorder, String kind, int key) {
        recorder.record(kind, new KeyEvent(new JPanel(), kind.equals("P") ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
                0, 0, key, KeyEvent.CHAR_UNDEFINED));
    }

    private static List<String> expected() {
        List<String> expected = new ArrayList<>();
        for (int pair = 0; pair < 25; pair++) expected.addAll(List.of("P65", "R65", "P66", "R66"));
        return expected;
    }
}
