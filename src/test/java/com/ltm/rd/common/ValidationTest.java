package com.ltm.rd.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ValidationTest {
    @Test
    void pointerInsideScreen() {
        Validation.checkPointer(0, 0, 1920, 1080);
        Validation.checkPointer(1919, 1079, 1920, 1080);
    }

    @Test
    void pointerOutsideScreen() {
        assertThrows(ProtocolException.class, () -> Validation.checkPointer(1920, 0, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkPointer(0, 1080, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkPointer(-1, 0, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkPointer(0, -1, 1920, 1080));
    }

    @Test
    void frameHeaderExactRatio() {
        Validation.checkFrameHeader(1280, 720, 1920, 1080);
        Validation.checkFrameHeader(1920, 1080, 1920, 1080);
        Validation.checkFrameHeader(1, 1, 1920, 1080);
    }

    @Test
    void frameHeaderRatioTolerance() {
        // expected height round(1280*1080/1920) = 720
        Validation.checkFrameHeader(1280, 719, 1920, 1080);
        Validation.checkFrameHeader(1280, 721, 1920, 1080);
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1280, 718, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1280, 722, 1920, 1080));
    }

    @Test
    void frameHeaderRoundsHalfUp() {
        // screen 3000x2000: width 1000 -> 666.67 -> 667, so 666..668 pass and 665/669 fail
        Validation.checkFrameHeader(1000, 666, 3000, 2000);
        Validation.checkFrameHeader(1000, 668, 3000, 2000);
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1000, 665, 3000, 2000));
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1000, 669, 3000, 2000));
    }

    @Test
    void frameHeaderBoundedByScreen() {
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1921, 1080, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1920, 1081, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(0, 1, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1, 0, 1920, 1080));
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(801, 601, 800, 600));
    }

    @Test
    void frameHeaderTallScreen() {
        Validation.checkFrameHeader(1, 8192, 1, 8192);
        assertThrows(ProtocolException.class, () -> Validation.checkFrameHeader(1, 1, 1, 8192));
    }
}
