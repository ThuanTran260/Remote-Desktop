package com.ltm.rd.client;

import java.awt.Rectangle;

/** The primary display: logical bounds plus the scale to physical pixels. */
public interface DisplayPort {
    Rectangle bounds();

    double scaleX();

    double scaleY();
}
