package com.ltm.rd.client;

import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/** {@link RobotPort} backed by a real AWT Robot; never created in headless tests. */
public final class AwtRobotPort implements RobotPort {
    private final Robot robot;

    public AwtRobotPort() throws AWTException {
        robot = new Robot();
        robot.setAutoDelay(0);
        robot.setAutoWaitForIdle(false);
    }

    @Override
    public void mouseMove(int x, int y) {
        robot.mouseMove(x, y);
    }

    @Override
    public void mousePress(int buttonMask) {
        robot.mousePress(buttonMask);
    }

    @Override
    public void mouseRelease(int buttonMask) {
        robot.mouseRelease(buttonMask);
    }

    @Override
    public void mouseWheel(int notches) {
        robot.mouseWheel(notches);
    }

    @Override
    public void keyPress(int virtualKey) {
        robot.keyPress(virtualKey);
    }

    @Override
    public void keyRelease(int virtualKey) {
        robot.keyRelease(virtualKey);
    }

    @Override
    public BufferedImage createScreenCapture(Rectangle bounds) {
        return robot.createScreenCapture(bounds);
    }
}
