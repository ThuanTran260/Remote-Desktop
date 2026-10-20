package com.ltm.rd.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RobotScreenSourceTest {
    private final FakeRobotPort port = new FakeRobotPort();

    private static DisplayPort display(Rectangle bounds, double sx, double sy) {
        return new DisplayPort() {
            @Override
            public Rectangle bounds() {
                return bounds;
            }

            @Override
            public double scaleX() {
                return sx;
            }

            @Override
            public double scaleY() {
                return sy;
            }
        };
    }

    @Test
    void geometryReadsBoundsAndScale() {
        RobotScreenSource source = new RobotScreenSource(port, display(new Rectangle(0, 0, 1536, 864), 1.25, 1.25));
        assertEquals(new ScreenGeometry(new Rectangle(0, 0, 1536, 864), 1.25, 1.25), source.geometry());
    }

    @Test
    void captureForwardsGivenBounds() throws Exception {
        RobotScreenSource source = new RobotScreenSource(port, display(new Rectangle(0, 0, 1536, 864), 1.25, 1.25));
        BufferedImage image = source.capture(new Rectangle(0, 0, 1536, 864));
        assertEquals(List.of("capture:0,0,1536,864"), port.log());
        assertEquals(1536, image.getWidth());
    }

    @Test
    void captureRuntimeExceptionBecomesIOException() {
        port.failOn("capture:0,0,10,10");
        RobotScreenSource source = new RobotScreenSource(port, display(new Rectangle(0, 0, 10, 10), 1, 1));
        assertThrows(IOException.class, () -> source.capture(new Rectangle(0, 0, 10, 10)));
    }

    @Test
    void geometryReturnsDefensiveCopy() {
        RobotScreenSource source = new RobotScreenSource(port, display(new Rectangle(0, 0, 100, 50), 1, 1));
        source.geometry().bounds().setBounds(7, 7, 7, 7);
        assertEquals(new Rectangle(0, 0, 100, 50), source.geometry().bounds());
    }
}
