package com.ltm.rd.common;

import static java.util.Map.entry;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The v2 receive filters; payload validation and one-time checks belong to the session. */
public final class PacketRules {
    public enum Shell { LISTENER, CONNECTOR }
    public enum Lane { COMMAND, IMAGE }
    public enum Stage { HANDSHAKE, PENDING, ACTIVE }

    private record Key(Shell shell, Lane lane, Stage stage) { }

    private static final Map<Key, Set<PacketType>> TABLE = Map.ofEntries(
            entry(new Key(Shell.LISTENER, Lane.COMMAND, Stage.HANDSHAKE), Set.of(PacketType.AUTH)),
            entry(new Key(Shell.LISTENER, Lane.COMMAND, Stage.PENDING),
                    Set.of(PacketType.PING, PacketType.PONG, PacketType.DISCONNECT)),
            entry(new Key(Shell.LISTENER, Lane.COMMAND, Stage.ACTIVE),
                    Set.of(PacketType.PING, PacketType.PONG, PacketType.DISCONNECT,
                            PacketType.MOUSE_MOVE, PacketType.MOUSE_PRESS, PacketType.MOUSE_RELEASE, PacketType.WHEEL,
                            PacketType.KEY_PRESS, PacketType.KEY_RELEASE, PacketType.RELEASE_ALL)),
            entry(new Key(Shell.LISTENER, Lane.IMAGE, Stage.HANDSHAKE), Set.of(PacketType.ATTACH)),
            entry(new Key(Shell.LISTENER, Lane.IMAGE, Stage.PENDING), Set.of()),
            entry(new Key(Shell.LISTENER, Lane.IMAGE, Stage.ACTIVE), Set.of()),
            entry(new Key(Shell.CONNECTOR, Lane.COMMAND, Stage.HANDSHAKE), Set.of(PacketType.AUTH_RESULT)),
            entry(new Key(Shell.CONNECTOR, Lane.COMMAND, Stage.PENDING),
                    Set.of(PacketType.SCREEN_INFO, PacketType.SESSION_ACTIVE, PacketType.PING, PacketType.PONG,
                            PacketType.DISCONNECT)),
            entry(new Key(Shell.CONNECTOR, Lane.COMMAND, Stage.ACTIVE),
                    Set.of(PacketType.PING, PacketType.PONG, PacketType.DISCONNECT)),
            entry(new Key(Shell.CONNECTOR, Lane.IMAGE, Stage.HANDSHAKE), Set.of()),
            entry(new Key(Shell.CONNECTOR, Lane.IMAGE, Stage.PENDING), Set.of()),
            entry(new Key(Shell.CONNECTOR, Lane.IMAGE, Stage.ACTIVE), Set.of(PacketType.FRAME)));

    private static final Map<Shell, Map<Lane, Set<PacketType>>> READABLE = readerUnions();

    private PacketRules() { }

    /** Immutable filter for one receive stage. */
    public static Set<PacketType> allowedIn(Shell shell, Lane lane, Stage stage) {
        return TABLE.get(new Key(shell, lane, stage));
    }

    /** Immutable union of pending and active types; the session still checks the current stage. */
    public static Set<PacketType> readableAfterHandshake(Shell shell, Lane lane) {
        return READABLE.get(shell).get(lane);
    }

    public static boolean allows(Shell shell, Lane lane, Stage stage, PacketType type) {
        return allowedIn(shell, lane, stage).contains(type);
    }

    private static Map<Shell, Map<Lane, Set<PacketType>>> readerUnions() {
        Map<Shell, Map<Lane, Set<PacketType>>> shells = new EnumMap<>(Shell.class);
        for (Shell shell : Shell.values()) {
            Map<Lane, Set<PacketType>> lanes = new EnumMap<>(Lane.class);
            for (Lane lane : Lane.values()) {
                Set<PacketType> union = EnumSet.noneOf(PacketType.class);
                union.addAll(allowedIn(shell, lane, Stage.PENDING));
                union.addAll(allowedIn(shell, lane, Stage.ACTIVE));
                lanes.put(lane, Set.copyOf(union));
            }
            shells.put(shell, Map.copyOf(lanes));
        }
        return Map.copyOf(shells);
    }
}
