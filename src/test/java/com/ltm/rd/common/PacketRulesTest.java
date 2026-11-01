package com.ltm.rd.common;

import static com.ltm.rd.common.PacketRules.Lane.*;
import static com.ltm.rd.common.PacketRules.Shell.*;
import static com.ltm.rd.common.PacketRules.Stage.*;
import static com.ltm.rd.common.PacketType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PacketRulesTest {
    private record Row(PacketRules.Shell shell, PacketRules.Lane lane, PacketRules.Stage stage,
            Set<PacketType> expected) { }

    // Independent, literal v1 specification: do not derive expected sets from production rules.
    private static final List<Row> ROWS = List.of(
            new Row(LISTENER, COMMAND, HANDSHAKE, Set.of(AUTH)),
            new Row(LISTENER, COMMAND, PENDING, Set.of(SCREEN_INFO, PING, PONG, DISCONNECT)),
            new Row(LISTENER, COMMAND, ACTIVE, Set.of(PING, PONG, DISCONNECT)),
            new Row(LISTENER, IMAGE, HANDSHAKE, Set.of(ATTACH)),
            new Row(LISTENER, IMAGE, PENDING, Set.of()),
            new Row(LISTENER, IMAGE, ACTIVE, Set.of(FRAME)),
            new Row(CONNECTOR, COMMAND, HANDSHAKE, Set.of(AUTH_RESULT)),
            new Row(CONNECTOR, COMMAND, PENDING, Set.of(SESSION_ACTIVE, PING, PONG, DISCONNECT,
                    MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE, WHEEL, KEY_PRESS, KEY_RELEASE, RELEASE_ALL)),
            new Row(CONNECTOR, COMMAND, ACTIVE, Set.of(PING, PONG, DISCONNECT,
                    MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE, WHEEL, KEY_PRESS, KEY_RELEASE, RELEASE_ALL)),
            new Row(CONNECTOR, IMAGE, HANDSHAKE, Set.of()),
            new Row(CONNECTOR, IMAGE, PENDING, Set.of()),
            new Row(CONNECTOR, IMAGE, ACTIVE, Set.of()));

    static Stream<Arguments> everyCombination() {
        return Arrays.stream(PacketRules.Shell.values()).flatMap(shell ->
                Arrays.stream(PacketRules.Lane.values()).flatMap(lane ->
                        Arrays.stream(PacketRules.Stage.values()).flatMap(stage ->
                                Arrays.stream(PacketType.values()).map(type ->
                                        Arguments.of(shell, lane, stage, type)))));
    }

    @ParameterizedTest
    @MethodSource("everyCombination")
    void everyTypeMatchesTheLiteralV1Table(PacketRules.Shell shell, PacketRules.Lane lane,
            PacketRules.Stage stage, PacketType type) {
        Set<PacketType> expected = ROWS.stream()
                .filter(row -> row.shell == shell && row.lane == lane && row.stage == stage)
                .findFirst().orElseThrow().expected;
        assertEquals(expected, PacketRules.allowedIn(shell, lane, stage));
        assertEquals(expected.contains(type), PacketRules.allows(shell, lane, stage, type));
    }

    @Test
    void everyAllowedSetIsUnmodifiable() {
        for (Row row : ROWS) {
            Set<PacketType> actual = PacketRules.allowedIn(row.shell, row.lane, row.stage);
            assertThrows(UnsupportedOperationException.class, () -> actual.add(AUTH));
            if (!actual.isEmpty()) {
                PacketType existing = actual.iterator().next();
                assertThrows(UnsupportedOperationException.class, () -> actual.remove(existing));
            }
            assertEquals(row.expected, PacketRules.allowedIn(row.shell, row.lane, row.stage));
        }
    }

    @Test
    void readableAfterHandshakeEqualsTheOldReaderSets() {
        assertReader(LISTENER, COMMAND, Set.of(SCREEN_INFO, PING, PONG, DISCONNECT));
        assertReader(LISTENER, IMAGE, Set.of(FRAME));
        assertReader(CONNECTOR, COMMAND, Set.of(SESSION_ACTIVE, PING, PONG, DISCONNECT,
                MOUSE_MOVE, MOUSE_PRESS, MOUSE_RELEASE, WHEEL, KEY_PRESS, KEY_RELEASE, RELEASE_ALL));
        assertReader(CONNECTOR, IMAGE, Set.of());
    }

    private static void assertReader(PacketRules.Shell shell, PacketRules.Lane lane, Set<PacketType> expected) {
        Set<PacketType> actual = PacketRules.readableAfterHandshake(shell, lane);
        assertEquals(expected, actual);
        assertThrows(UnsupportedOperationException.class, () -> actual.add(AUTH));
        if (!actual.isEmpty()) {
            PacketType existing = actual.iterator().next();
            assertThrows(UnsupportedOperationException.class, () -> actual.remove(existing));
        }
        assertEquals(expected, PacketRules.readableAfterHandshake(shell, lane));
    }
}
