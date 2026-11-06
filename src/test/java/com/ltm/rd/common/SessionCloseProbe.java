package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Controlled channel IO and close scheduling shared by the two shell contract suites. */
public final class SessionCloseProbe {
    private SessionCloseProbe() { }

    public static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "fixture checkpoint was not reached");
    }

    public static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "fixture condition was not reached");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static Object read(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot observe session fixture field " + name, failure);
        }
    }

    public static CommandWriter writer(Object session) { return (CommandWriter) read(session, "writer"); }
    public static Heartbeat heartbeat(Object session) { return (Heartbeat) read(session, "heartbeat"); }
    public static Channel commandChannel(Object viewer) { return (Channel) read(viewer, "commandChannel"); }
    public static Channel imageChannel(Object session) { return (Channel) read(session, "imageChannel"); }
    public static List<Packet> queued(CommandWriter writer) { return writer.queuedSnapshot(); }
    public static boolean lockHeld(SessionLifecycle<?> lifecycle) { return lifecycle.isLockHeldByCurrentThread(); }
    public static CompletableFuture<?> closingStage(CommandWriter writer) {
        return (CompletableFuture<?>) read(writer, "done");
    }

    public static final class CleanupQueue implements Executor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        public void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
        public CompletableFuture<Void> runOneUntilWaitingOrDone() {
            Runnable task = tasks.remove();
            CompletableFuture<Void> done = new CompletableFuture<>();
            Thread worker = new Thread(() -> {
                try { task.run(); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
            }, "test-close-phase-b");
            worker.setDaemon(true);
            worker.start();
            awaitCondition(() -> done.isDone() || worker.getState() == Thread.State.TIMED_WAITING);
            return done;
        }
    }

    /** No timer thread runs: tests explicitly fire the recorded lifecycle deadline. */
    public static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final ConcurrentLinkedQueue<Task> tasks = new ConcurrentLinkedQueue<>();
        public ManualScheduler() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            Task task = new Task(action, unit.toNanos(delay));
            tasks.add(task);
            return task;
        }
        public void fireAll() {
            Task task;
            while ((task = tasks.poll()) != null) task.run();
        }
        private static final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
            private final long delay;
            Task(Runnable action, long delay) { super(action, null); this.delay = delay; }
            @Override public long getDelay(TimeUnit unit) { return unit.convert(delay, TimeUnit.NANOSECONDS); }
            @Override public int compareTo(Delayed other) {
                return Long.compare(delay, other.getDelay(TimeUnit.NANOSECONDS));
            }
        }
    }

    public static final class Wire {
        public final List<String> events;
        public final IdleInput input;
        public final HoldingOutput output;
        public final RecordingSocket socket;
        public final Channel channel;
        public Wire() { this(new CopyOnWriteArrayList<>(), "command"); }
        public Wire(List<String> events, String lane) {
            this.events = events;
            input = new IdleInput(events, lane);
            output = new HoldingOutput(events, lane);
            socket = new RecordingSocket(input, output, events, lane);
            output.socket = socket;
            channel = new Channel(socket, new DataInputStream(input), new DataOutputStream(output));
        }
        public List<Packet> packets() throws IOException {
            List<Packet> packets = new ArrayList<>();
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(output.bytes()));
            for (var next = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class));
                    next.isPresent(); next = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class))) {
                packets.add(next.orElseThrow());
            }
            return packets;
        }
        public void release() { output.release.countDown(); }
    }

    public static final class IdleInput extends InputStream {
        private final CountDownLatch ended = new CountDownLatch(1);
        private final List<String> events;
        private final String lane;
        public final CountDownLatch entered = new CountDownLatch(1);
        public volatile Runnable beforeRead = () -> { };
        public volatile boolean timeout;
        public volatile boolean wrapperClosed;
        IdleInput(List<String> events, String lane) { this.events = events; this.lane = lane; }
        @Override public int read() throws IOException {
            beforeRead.run();
            entered.countDown();
            if (timeout) throw new java.net.SocketTimeoutException("controlled idle read");
            try { ended.await(); }
            catch (InterruptedException failure) { throw new InterruptedIOException("reader interrupted"); }
            return -1;
        }
        void rawClosed() { ended.countDown(); }
        @Override public void close() {
            wrapperClosed = true;
            events.add(lane + ":input-close");
            ended.countDown();
        }
    }

    public static final class HoldingOutput extends OutputStream {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final ByteArrayOutputStream flushed = new ByteArrayOutputStream();
        private final List<String> events;
        private final String lane;
        private RecordingSocket socket;
        public volatile boolean hold;
        public volatile boolean failClose;
        public volatile boolean wrapperClosed;
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
        public final AtomicInteger flushes = new AtomicInteger();
        HoldingOutput(List<String> events, String lane) { this.events = events; this.lane = lane; }
        @Override public synchronized void write(int value) throws IOException {
            if (socket.isClosed()) throw new IOException("raw channel closed");
            pending.write(value);
        }
        @Override public void flush() throws IOException {
            if (socket.isClosed()) throw new IOException("raw channel closed");
            if (hold) {
                entered.countDown();
                boolean interrupted = false;
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            synchronized (this) {
                if (socket.isClosed()) throw new IOException("raw channel closed");
                flushed.writeBytes(pending.toByteArray());
                pending.reset();
                flushes.incrementAndGet();
            }
        }
        public synchronized byte[] bytes() { return flushed.toByteArray(); }
        @Override public void close() throws IOException {
            wrapperClosed = true;
            events.add(lane + ":output-close");
            if (failClose) throw new IOException("controlled wrapper close failure");
        }
    }

    public static final class RecordingSocket extends Socket {
        private final IdleInput input;
        private final HoldingOutput output;
        private final List<String> events;
        private final String lane;
        private volatile boolean closed;
        public volatile boolean failTimeout;
        public volatile int timeout;
        public volatile Runnable onTimeout = () -> { };
        RecordingSocket(IdleInput input, HoldingOutput output, List<String> events, String lane) {
            this.input = input; this.output = output; this.events = events; this.lane = lane;
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int value) throws SocketException {
            onTimeout.run();
            if (failTimeout) throw new SocketException("controlled option failure");
            timeout = value;
            events.add(lane + ":timeout");
        }
        @Override public boolean isClosed() { return closed; }
        @Override public synchronized void close() {
            if (!closed) { closed = true; events.add(lane + ":raw-close"); input.rawClosed(); }
        }
    }

    public static final Duration GRACE = Timeouts.DEFAULTS.closeGrace();
}
