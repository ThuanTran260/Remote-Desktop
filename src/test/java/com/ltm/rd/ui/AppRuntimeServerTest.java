package com.ltm.rd.ui;

import static com.ltm.rd.ui.RuntimeTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.LaunchOptions;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.it.RawPeer;
import java.awt.image.BufferedImage;
import java.net.BindException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The Server role of the runtime against a real ServerCore on ephemeral loopback ports. */
@Timeout(120)
class AppRuntimeServerTest {
    private final List<AppRuntime> runtimes = new ArrayList<>();
    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (AutoCloseable r : resources) r.close();
        for (AppRuntime rt : runtimes) get(rt.shutdownAsync());
    }

    private AppRuntime server(int cmdPort) {
        AppRuntime rt = AppRuntime.forServer(serverConfig(System::nanoTime, cmdPort, 0));
        runtimes.add(rt);
        return rt;
    }

    private AppRuntime client(Snapshot listening, FakeAdapterFactory adapters) throws Exception {
        AppRuntime rt = AppRuntime.forClient(clientConfig(listening.cmdPort(), listening.imgPort(), System::nanoTime),
                adapters);
        runtimes.add(rt);
        return rt;
    }

    private AppRuntime startedServer() throws Exception {
        AppRuntime rt = server(0);
        get(rt.startServer());
        return rt;
    }

    private AppRuntime connectedClient(AppRuntime server, FakeAdapterFactory adapters) throws Exception {
        Snapshot listening = server.snapshot();
        AppRuntime client = client(listening, adapters);
        assertEquals(ConnectOutcome.STARTED, client.connect("127.0.0.1", listening.code()));
        await(server, s -> s.phase() == UiPhase.ACTIVE);
        return client;
    }

    @Test
    void startServerReachesListening() throws Exception {
        AppRuntime rt = server(0);
        assertEquals(UiPhase.IDLE, rt.snapshot().phase());
        assertEquals(LaunchOptions.Role.SERVER, rt.role());
        get(rt.startServer());
        Snapshot s = rt.snapshot();
        assertEquals(UiPhase.LISTENING, s.phase());
        assertTrue(s.cmdPort() > 0);
        assertTrue(s.imgPort() > 0);
        assertTrue(s.code().matches("\\d{6}"), "a six digit code");
        assertEquals(0, s.generation());
        assertFalse(s.controlEnabled());
        assertNull(s.frame());
        assertEquals("", s.message());
    }

    @Test
    void startServerPortInUseFailsThenRetrySucceeds() throws Exception {
        ServerSocket occupied = new ServerSocket(0, 1, LO);
        resources.add(occupied);
        int port = occupied.getLocalPort();
        AppRuntime rt = server(port);

        ExecutionException e = assertThrows(ExecutionException.class, () -> get(rt.startServer()));
        assertInstanceOf(BindException.class, e.getCause());
        Snapshot failed = rt.snapshot();
        assertEquals(UiPhase.FAILED, failed.phase());
        assertEquals("Cổng đã được sử dụng. Đổi bằng --cmd-port/--img-port.", failed.message());
        assertNull(failed.code());

        occupied.close();
        get(rt.startServer()); // a new ServerCore: the failed one cannot be reused
        Snapshot listening = rt.snapshot();
        assertEquals(UiPhase.LISTENING, listening.phase());
        assertEquals(port, listening.cmdPort());
        assertEquals("", listening.message());
    }

    @Test
    void startServerWhileListeningFails() throws Exception {
        AppRuntime rt = startedServer();
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(rt.startServer()));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertEquals(UiPhase.LISTENING, rt.snapshot().phase());
    }

    @Test
    void regenerateCodeOnlyWhenListening() throws Exception {
        AppRuntime rt = server(0);
        assertFalse(rt.regenerateCode(), "not listening yet");
        get(rt.startServer());
        String first = rt.snapshot().code();
        assertTrue(rt.regenerateCode());
        assertNotEquals(first, rt.snapshot().code());

        // RESERVED: a peer that authenticated but has not attached yet
        Snapshot listening = rt.snapshot();
        try (RawPeer peer = RawPeer.connect(listening.cmdPort())) {
            peer.send(Packet.auth(listening.code()));
            peer.readUntil(PacketType.AUTH_RESULT);
            Snapshot reserved = await(rt, s -> s.phase() == UiPhase.RESERVED);
            assertFalse(rt.regenerateCode());
            assertEquals(listening.code(), rt.snapshot().code());
            assertEquals(reserved.code(), listening.code());
        }
        await(rt, s -> s.phase() == UiPhase.LISTENING);

        // ACTIVE
        connectedClient(rt, new FakeAdapterFactory());
        String active = rt.snapshot().code();
        assertFalse(rt.regenerateCode());
        assertEquals(active, rt.snapshot().code());
    }

    @Test
    void snapshotToStringNeverContainsCodeOrPeer() throws Exception {
        Snapshot synthetic = new Snapshot(LaunchOptions.Role.SERVER, UiPhase.ACTIVE, 3, 7, "msg", "10.20.30.40", 5001,
                5000, "482913", 1920, 1080, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), 9, true,
                TrafficStats.ZERO);
        String text = synthetic.toString();
        assertFalse(text.contains("482913"));
        assertFalse(text.contains("10.20.30.40"));
        assertFalse(text.contains("BufferedImage"));
        assertTrue(text.contains("SERVER") && text.contains("ACTIVE"));

        AppRuntime rt = startedServer();
        connectedClient(rt, new FakeAdapterFactory());
        Snapshot real = rt.snapshot();
        assertEquals("127.0.0.1", real.peer());
        assertNotNull(real.code());
        assertFalse(real.toString().contains(real.code()));
        assertFalse(real.toString().contains(real.peer()));
    }

    @Test
    void disconnectReturnsToListeningWithNewCode() throws Exception {
        AppRuntime rt = startedServer();
        connectedClient(rt, new FakeAdapterFactory());
        Snapshot active = await(rt, s -> s.phase() == UiPhase.ACTIVE);
        assertEquals(1, active.generation());
        assertEquals(1920, active.screenWidth());
        assertEquals(1080, active.screenHeight());

        assertTrue(rt.disconnect());
        Snapshot back = await(rt, s -> s.phase() == UiPhase.LISTENING);
        assertNotEquals(active.code(), back.code());
        assertEquals(0, back.generation());
        assertEquals("Đã ngắt kết nối.", back.message());
        assertFalse(rt.disconnect(), "nothing left to close");
    }

    @Test
    void sendInputOnWrongRoleReturnsWrongRole() throws Exception {
        AppRuntime server = startedServer();
        FakeAdapterFactory adapters = new FakeAdapterFactory();
        AppRuntime client = client(server.snapshot(), adapters);
        assertEquals(SendResult.WRONG_ROLE, client.sendInput(1, 0, Packet.keyPress(65)));
        assertFalse(client.releaseControlInput(1));
        assertFalse(client.regenerateCode());
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(client.startServer()));
        assertInstanceOf(IllegalStateException.class, e.getCause());

        assertEquals(ConnectOutcome.WRONG_ROLE, server.connect("127.0.0.1", "123456"));
        assertFalse(server.cancel());
        assertEquals(SendResult.NO_SESSION, server.sendInput(1, 0, Packet.keyPress(65)));
        assertTrue(adapters.device.log().isEmpty());
    }
}
