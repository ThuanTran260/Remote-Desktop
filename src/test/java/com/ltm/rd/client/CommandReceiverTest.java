package com.ltm.rd.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.CommandWriter;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketIO;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Protocol;
import com.ltm.rd.common.SessionLifecycle;
import java.awt.Rectangle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class CommandReceiverTest {
    private static final int WAIT_S = 10;
    private static final Rectangle BOUNDS = new Rectangle(0, 0, 1920, 1080);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "test-sched");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService cleanup = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "test-cleanup");
        t.setDaemon(true);
        return t;
    });
    private final List<FakeInputDevice.Gate> gates = new ArrayList<>();
    private final List<CommandWriter> writers = new ArrayList<>();

    private FakeInputDevice device;
    private InputExecutor executor;

    @BeforeEach
    void setUp() {
        device = new FakeInputDevice();
        executor = newExecutor(64);
    }

    @AfterEach
    void tearDown() throws Exception {
        gates.forEach(FakeInputDevice.Gate::open);
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        writers.forEach(CommandWriter::stop);
        scheduler.shutdownNow();
        cleanup.shutdownNow();
    }

    private InputExecutor newExecutor(int capacity) {
        return new InputExecutor(device, () -> BOUNDS, capacity, InputExecutor.defaultThreadFactory(), t -> {});
    }

    private SessionLifecycle<ClientPhase> lifecycle(ClientPhase phase) {
        SessionLifecycle<ClientPhase> lc = new SessionLifecycle<>(ClientPhase.CONNECTING, ClientPhase.CLOSING,
                System::nanoTime, Duration.ofMillis(100), scheduler, cleanup);
        lc.atomically(tx -> tx.advance(ClientPhase.CONNECTING, phase));
        return lc;
    }

    private CommandWriter unstartedWriter() {
        CommandWriter w = new CommandWriter(new DataOutputStream(new ByteArrayOutputStream()), t -> {});
        writers.add(w);
        return w;
    }

    private static void handle(SessionLifecycle<ClientPhase> lc, CommandReceiver receiver, Packet p) {
        lc.atomically(tx -> {
            receiver.handle(tx, p);
            return null;
        });
    }

    private CommandReceiver receiver(long generation, CommandWriter writer) {
        return new CommandReceiver(executor, generation, BOUNDS, writer);
    }

    @Test
    void pointerOutsideScreenIsProtocolError() {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ACTIVE);
        CommandReceiver r = receiver(1, unstartedWriter());
        handle(lc, r, Packet.mouseMove(1919, 1079)); // last valid pixel
        assertFalse(lc.isClosed());
        handle(lc, r, Packet.mouseMove(1920, 5));
        CloseInfo info = lc.closeInfo().orElseThrow();
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
    }

    @Test
    void controlBeforeActiveIsProtocolError() throws Exception {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ATTACHING);
        CommandReceiver r = receiver(1, unstartedWriter());
        handle(lc, r, Packet.keyPress(65));
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, lc.closeInfo().orElseThrow().reason());
        assertFalse(device.awaitCall("keyPress:65"), "nothing may reach the device before ACTIVE");
    }

    @Test
    void keyPressMapsToInputCommand() throws Exception {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ACTIVE);
        CommandReceiver r = receiver(1, unstartedWriter());
        handle(lc, r, Packet.keyPress(65));
        assertTrue(device.awaitCall("keyPress:65"));
        handle(lc, r, Packet.mouseMove(10, 20));
        assertTrue(device.awaitCall("mouseMove:10,20"));
        handle(lc, r, Packet.wheel(-3));
        assertTrue(device.awaitCall("mouseWheel:-3"));
        handle(lc, r, Packet.keyRelease(65));
        assertTrue(device.awaitCall("keyRelease:65"));
        assertFalse(lc.isClosed());
    }

    @Test
    void releaseAllIsCountedWhenAccepted() throws Exception {
        assertTrue(executor.beginSession(1));
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ACTIVE);
        CommandReceiver r = receiver(1, unstartedWriter());
        assertEquals(0, r.releaseAllReceived());
        handle(lc, r, Packet.keyPress(17));
        handle(lc, r, Packet.releaseAll());
        assertEquals(1, r.releaseAllReceived());
        assertTrue(device.awaitCall("keyRelease:17"), "RELEASE_ALL reaches the executor");

        SessionLifecycle<ClientPhase> early = lifecycle(ClientPhase.ATTACHING);
        CommandReceiver r2 = receiver(1, unstartedWriter());
        handle(early, r2, Packet.releaseAll());
        assertEquals(0, r2.releaseAllReceived(), "a rejected RELEASE_ALL is not counted");
    }

    @Test
    void overflowClosesSession() throws Exception {
        executor.shutdownAsync().get(WAIT_S, TimeUnit.SECONDS);
        executor = newExecutor(1);
        assertTrue(executor.beginSession(1));
        FakeInputDevice.Gate gate = device.blockOn("keyPress:1");
        gates.add(gate);
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ACTIVE);
        CommandReceiver r = receiver(1, unstartedWriter());
        handle(lc, r, Packet.keyPress(1));
        assertTrue(gate.awaitEntered(), "the worker is now blocked inside the device");
        handle(lc, r, Packet.keyPress(2)); // fills the one-slot deque
        assertFalse(lc.isClosed());
        handle(lc, r, Packet.keyPress(3));
        assertEquals(Packet.DisconnectReason.OVERFLOW, lc.closeInfo().orElseThrow().reason());
    }

    @Test
    void staleGenerationClosesSession() {
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ACTIVE);
        CommandReceiver r = receiver(7, unstartedWriter()); // the executor never began generation 7
        handle(lc, r, Packet.keyPress(65));
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, lc.closeInfo().orElseThrow().reason());
    }

    @Test
    void pingAnsweredWithPong() throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream sink = new OutputStream() {
            @Override
            public void write(int b) {
                bytes.write(b);
            }

            @Override
            public void flush() {
                flushed.countDown();
            }
        };
        CommandWriter w = new CommandWriter(new DataOutputStream(sink), t -> {});
        writers.add(w);
        w.start();
        SessionLifecycle<ClientPhase> lc = lifecycle(ClientPhase.ATTACHING);
        CommandReceiver r = receiver(1, w);
        handle(lc, r, Packet.ping(42));
        assertTrue(flushed.await(WAIT_S, TimeUnit.SECONDS));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        Packet reply = PacketIO.read(in, Protocol.CMD_MAX_LEN, EnumSet.allOf(PacketType.class)).orElseThrow();
        assertEquals(PacketType.PONG, reply.type());
        assertEquals(42, reply.seq());
        assertFalse(lc.isClosed());
    }
}
