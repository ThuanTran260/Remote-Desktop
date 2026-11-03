package com.ltm.rd.viewer;

import com.ltm.rd.common.Channel;
import com.ltm.rd.common.Packet;
import com.ltm.rd.common.SessionPort;
import com.ltm.rd.viewer.ControlSide;

/** Test-only bridge to the same connector paths used by the real handshake. */
public final class ViewerTestAccess {
    private ViewerTestAccess() { }

    public static SessionPort port(ViewerSession session) { return session.port(); }
    public static ControlSide controlSide(ViewerSession session) { return session.controlSide(); }
    public static boolean attachCommandChannel(ViewerSession session, Channel channel) {
        return session.attachCommandChannel(channel);
    }
    public static void dispatch(ViewerSession session, Packet packet) { session.dispatch(packet); }
    public static void attachImageChannel(ViewerSession session, Channel channel) { session.attachImageChannel(channel); }

    public static java.net.Socket channelSocket(ViewerSession session, boolean image) {
        return session.lifecycle().atomically(tx -> {
            try {
                var field = ViewerSession.class.getDeclaredField(image ? "imageChannel" : "commandChannel");
                field.setAccessible(true);
                return ((Channel) field.get(session)).socket();
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
}
