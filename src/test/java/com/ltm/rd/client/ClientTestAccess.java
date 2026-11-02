package com.ltm.rd.client;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionPort;
import com.ltm.rd.viewer.ControlSide;

/** Test-only bridge to the same connector paths used by the real handshake. */
public final class ClientTestAccess {
    private ClientTestAccess() { }

    public static SessionPort port(ClientSession session) { return session.port(); }
    public static ControlSide controlSide(ClientSession session) { return session.controlSide(); }
    public static boolean attachCommandChannel(ClientSession session, Channel channel) {
        return session.attachCommandChannel(channel);
    }
    public static void dispatch(ClientSession session, Packet packet) { session.dispatch(packet); }
    public static void attachImageChannel(ClientSession session, Channel channel) { session.attachImageChannel(channel); }

    public static java.net.Socket channelSocket(ClientSession session, boolean image) {
        return session.lifecycle().atomically(tx -> {
            try {
                var field = ClientSession.class.getDeclaredField(image ? "imageChannel" : "commandChannel");
                field.setAccessible(true);
                return ((Channel) field.get(session)).socket();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
}
