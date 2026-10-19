package com.ltm.rd.server;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.client.FrameEncoder;
import com.ltm.rd.common.CloseInfo;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.PacketType;
import com.ltm.rd.common.Timeouts;
import com.ltm.rd.it.RawPeer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Review Focus 5 over real sockets: a raw image peer sends frames the receiver must reject or accept. */
@Timeout(120)
class FrameReceiverIntegrationTest {
    private static final int WAIT_S = 10;

    private static final Timeouts TIMEOUTS = new Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(7), Duration.ofSeconds(2), Duration.ofSeconds(10),
            Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofSeconds(20),
            Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofMillis(300), Duration.ofSeconds(3),
            Duration.ZERO, Duration.ofSeconds(30), Duration.ofMillis(100));

    private final List<ServerCore> cores = new ArrayList<>();
    private final List<RawPeer> peers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        peers.forEach(RawPeer::close);
        cores.forEach(ServerCore::close);
    }

    private record Active(ServerCore core, ServerSession session, RawPeer cmd, RawPeer img) {}

    /** AUTH, SCREEN_INFO 1920x1080, ATTACH, then waits for SESSION_ACTIVE. */
    private Active activate() throws IOException {
        ServerCore core = new ServerCore(new ServerConfig(InetAddress.getLoopbackAddress(), 0, 0, TIMEOUTS,
                System::nanoTime, new SecureRandom()));
        cores.add(core);
        core.start();
        RawPeer cmd = RawPeer.connect(core.cmdPort());
        peers.add(cmd);
        cmd.send(Packet.auth(core.sessions().currentCode()));
        byte[] token = cmd.read().authResultPayload().token();
        cmd.send(Packet.screenInfo(1920, 1080));
        RawPeer img = RawPeer.connect(core.imgPort());
        peers.add(img);
        img.send(Packet.attach(token));
        cmd.readUntil(PacketType.SESSION_ACTIVE);
        return new Active(core, core.sessions().current().orElseThrow(), cmd, img);
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        return new FrameEncoder(8192, 0.6f).encode(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)).jpeg();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            LockSupport.parkNanos(1_000_000);
        }
    }

    private static CloseInfo closedWith(Active a) throws Exception {
        a.session().lifecycle().terminated().get(5, TimeUnit.SECONDS);
        return a.session().lifecycle().closeInfo().orElseThrow();
    }

    @Test
    void headerSaying1280x720OverA640x360JpegIsProtocolError() throws Exception {
        Active a = activate();
        a.img().send(Packet.frame(1280, 720, jpeg(640, 360)));
        CloseInfo info = closedWith(a);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("invalid frame", info.message());
        assertEquals(0, a.session().framesReceived(), "the mislabelled frame was never published");
    }

    @Test
    void garbageJpegBytesAreProtocolError() throws Exception {
        Active a = activate();
        byte[] garbage = new byte[500];
        new SecureRandom().nextBytes(garbage);
        a.img().send(Packet.frame(1280, 720, garbage));
        CloseInfo info = closedWith(a);
        assertEquals(Packet.DisconnectReason.PROTOCOL_ERROR, info.reason());
        assertEquals("invalid frame", info.message());
    }

    @Test
    void imageChannelEofWhileActiveClosesPromptlyWithItsCause() throws Exception {
        Active a = activate();
        CountDownLatch decided = new CountDownLatch(1);
        long[] decidedAt = new long[1];
        a.session().lifecycle().addCloseHook(() -> {
            decidedAt[0] = System.nanoTime();
            decided.countDown();
        });
        long start = System.nanoTime();
        a.img().close();
        assertTrue(decided.await(WAIT_S, TimeUnit.SECONDS));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(decidedAt[0] - start) < 500, "EOF must not wait for a timeout");
        CloseInfo info = closedWith(a);
        assertEquals("peer closed image channel", info.message());
        assertEquals(Packet.DisconnectReason.USER, info.reason());
    }

    @Test
    void wellFormedFrameIsAcceptedAndEnablesControl() throws Exception {
        Active a = activate();
        a.img().send(Packet.frame(1280, 720, jpeg(1280, 720)));
        awaitTrue(() -> a.session().framesReceived() == 1, "the frame to be published");
        assertTrue(a.session().controlEnabled());
        assertFalse(a.session().lifecycle().isClosed());
    }
}
