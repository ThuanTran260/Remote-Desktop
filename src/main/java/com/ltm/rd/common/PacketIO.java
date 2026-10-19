package com.ltm.rd.common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/** Reads and writes {@code [type:1][length:int32][payload]} packets (big-endian). */
public final class PacketIO {
    private PacketIO() {}

    /**
     * Reads one packet following the checks of spec 2.2.
     *
     * <p>Clean EOF (stream ends before the first byte of a packet) returns {@link Optional#empty()};
     * the caller must still treat it as the peer closing and close the session. EOF anywhere
     * after the first byte throws {@link EOFException} (connection lost). Protocol violations
     * throw {@link ProtocolException}; the stream must not be read again afterwards.
     */
    public static Optional<Packet> read(DataInputStream in, int channelMaxLen, Set<PacketType> allowed)
            throws IOException {
        int typeByte = in.read();
        if (typeByte < 0) return Optional.empty();

        PacketType type = PacketType.fromCode(typeByte)
                .orElseThrow(() -> new ProtocolException("unknown packet type 0x"
                        + Integer.toHexString(typeByte)));
        if (!allowed.contains(type)) throw new ProtocolException("packet type " + type + " not allowed here");

        int length = in.readInt();
        if (length < 0 || length > channelMaxLen || length > type.maxLength() || length < type.minLength()) {
            throw new ProtocolException(type + " bad length " + length);
        }

        byte[] payload = new byte[length];
        in.readFully(payload);
        return Optional.of(new Packet(type, payload));
    }

    /** Writes one packet without flushing; the caller flushes. */
    public static void write(DataOutputStream out, Packet packet) throws IOException {
        byte[] payload = packet.rawPayload();
        out.writeByte(packet.type().code());
        out.writeInt(payload.length);
        out.write(payload);
    }
}
