package com.ltm.rd.it;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Orders production input stop, an acknowledged native keyboard probe, and final EDT validation. */
final class TwoJvmInputCompletion {
    private TwoJvmInputCompletion() {}

    static List<String> finish(TwoJvmInputRecorder recorder, Callable<Void> inputStopped, Runnable nativeFence) throws Exception {
        var worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "two-jvm-native-fence");
            thread.setDaemon(true);
            return thread;
        });
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var completion = worker.submit(() -> {
            inputStopped.call();
            onEdt(() -> { recorder.prepareProbe(); return null; });
            nativeFence.run(); // Injects press/release only; the listener must acknowledge both.
            recorder.awaitProbe(Duration.ofSeconds(3));
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("NativeFenceFailed");
            return onEdt(recorder::finish);
        });
        try {
            // A bounded failure, never an inference of native completion from elapsed quiet time.
            return completion.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("NativeFenceTimeout", failure);
        } finally {
            cancelled.set(true);
            completion.cancel(true);
            worker.shutdownNow();
        }
    }

    static void requireSupportedDesktop() {
        Runtime.Version version = Runtime.version();
        if (GraphicsEnvironment.isHeadless() || !System.getProperty("os.name").startsWith("Windows")
                || version.feature() != 21 || version.update() != 13 || version.build().orElse(-1) != 9
                || !System.getProperty("java.vendor").equals("Eclipse Adoptium")
                || !Toolkit.getDefaultToolkit().getClass().getName().equals("sun.awt.windows.WToolkit")) {
            throw new IllegalStateException("NativeFenceUnsupported");
        }
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        return task.get(3, TimeUnit.SECONDS);
    }
}
