package com.ltm.rd.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.ltm.rd.ui.CoordinateMapper.RemotePoint;
import java.awt.Rectangle;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoordinateMapperTest {

    @Test
    void imageRectLandscapeFill() {
        assertEquals(new Rectangle(0, 0, 960, 540), CoordinateMapper.imageRect(960, 540, 1920, 1080).orElseThrow());
    }

    @Test
    void imageRectLetterboxHorizontal() {
        assertEquals(new Rectangle(144, 0, 711, 400), CoordinateMapper.imageRect(1000, 400, 1600, 900).orElseThrow());
    }

    @Test
    void imageRectRoundsHalfUp() {
        // dh = 562.5, oy = 118.75
        assertEquals(new Rectangle(0, 119, 1000, 563), CoordinateMapper.imageRect(1000, 800, 1920, 1080).orElseThrow());
    }

    @Test
    void imageRectPortraitRemote() {
        Rectangle r = CoordinateMapper.imageRect(800, 1000, 1080, 1920).orElseThrow();
        double scale = Math.min(800 / 1080.0, 1000 / 1920.0);
        double dw = 1080 * scale;
        double dh = 1920 * scale;
        assertEquals(new Rectangle((int) Math.round((800 - dw) / 2), (int) Math.round((1000 - dh) / 2),
                (int) Math.round(dw), (int) Math.round(dh)), r);
        assertTrue(new Rectangle(0, 0, 800, 1000).contains(r), "never larger than the panel: " + r);
    }

    @Test
    void emptyWhenPanelOrRemoteIsZero() {
        assertTrue(CoordinateMapper.imageRect(0, 540, 1920, 1080).isEmpty());
        assertTrue(CoordinateMapper.imageRect(960, 0, 1920, 1080).isEmpty());
        assertTrue(CoordinateMapper.imageRect(960, 540, 0, 1080).isEmpty());
        assertTrue(CoordinateMapper.imageRect(960, 540, 1920, 0).isEmpty());
        assertTrue(CoordinateMapper.map(1, 1, 0, 540, 1920, 1080).isEmpty());
        assertTrue(CoordinateMapper.map(1, 1, 960, 0, 1920, 1080).isEmpty());
        assertTrue(CoordinateMapper.map(1, 1, 960, 540, 0, 1080).isEmpty());
        assertTrue(CoordinateMapper.mapClamped(1, 1, 0, 540, 1920, 1080).isEmpty());
        assertTrue(CoordinateMapper.mapClamped(1, 1, 960, 0, 1920, 1080).isEmpty());
        assertTrue(CoordinateMapper.mapClamped(1, 1, 960, 540, 0, 1080).isEmpty());
        assertTrue(CoordinateMapper.imageRect(5, 5, 10_000, 1).isEmpty(), "a rectangle without area is empty");
    }

    @Test
    void mapCornersAndCenter() {
        assertEquals(new RemotePoint(0, 0), CoordinateMapper.map(0, 0, 960, 540, 1920, 1080).orElseThrow());
        assertEquals(new RemotePoint(960, 540), CoordinateMapper.map(480, 270, 960, 540, 1920, 1080).orElseThrow());
        assertEquals(new RemotePoint(1918, 1078), CoordinateMapper.map(959, 539, 960, 540, 1920, 1080).orElseThrow());
    }

    @Test
    void mapOutsideRectIsEmpty() {
        assertTrue(CoordinateMapper.map(960, 540, 960, 540, 1920, 1080).isEmpty());
        assertTrue(CoordinateMapper.map(143, 10, 1000, 400, 1600, 900).isEmpty(), "left letterbox bar");
        assertTrue(CoordinateMapper.map(855, 10, 1000, 400, 1600, 900).isEmpty(), "right letterbox bar");
        assertTrue(CoordinateMapper.map(-1, 0, 960, 540, 1920, 1080).isEmpty());
    }

    @Test
    void mapClampedKeepsEdges() {
        assertEquals(new RemotePoint(0, 22), CoordinateMapper.mapClamped(143, 10, 1000, 400, 1600, 900).orElseThrow());
        assertEquals(new RemotePoint(1599, 22), CoordinateMapper.mapClamped(900, 10, 1000, 400, 1600, 900).orElseThrow());
        assertEquals(new RemotePoint(1597, 897), CoordinateMapper.mapClamped(854, 399, 1000, 400, 1600, 900).orElseThrow());
        assertEquals(new RemotePoint(0, 0), CoordinateMapper.mapClamped(-500, -500, 960, 540, 1920, 1080).orElseThrow());
        assertEquals(new RemotePoint(1919, 1079), CoordinateMapper.mapClamped(5000, 5000, 960, 540, 1920, 1080).orElseThrow());
    }

    @Test
    void resizeChangesMappingConsistently() {
        Optional<RemotePoint> small = CoordinateMapper.map(240, 135, 960, 540, 1920, 1080);
        Optional<RemotePoint> large = CoordinateMapper.map(480, 270, 1920, 1080, 1920, 1080);
        assertEquals(small, large);
        assertEquals(new RemotePoint(480, 270), small.orElseThrow());
    }

    @Test
    void everyInsidePixelMapsInsideTheRemote() {
        // the bounds check and the clamp agree with the logical size on an awkward letterbox
        int pw = 1000;
        int ph = 400;
        for (int x = 0; x < pw; x += 7) {
            for (int y = 0; y < ph; y += 3) {
                CoordinateMapper.map(x, y, pw, ph, 1600, 900).ifPresent(p -> {
                    assertTrue(p.x() >= 0 && p.x() < 1600 && p.y() >= 0 && p.y() < 900, p.toString());
                });
                RemotePoint c = CoordinateMapper.mapClamped(x, y, pw, ph, 1600, 900).orElseThrow();
                assertTrue(c.x() >= 0 && c.x() <= 1599 && c.y() >= 0 && c.y() <= 899, c.toString());
            }
        }
    }
}
