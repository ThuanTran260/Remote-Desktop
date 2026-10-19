package com.ltm.rd.client;

/** Input commands; coordinates are relative to the screen bounds origin. */
public sealed interface InputCommand {
    record MouseMove(int x, int y) implements InputCommand {}

    record MousePress(int x, int y, int button) implements InputCommand {}

    record MouseRelease(int x, int y, int button) implements InputCommand {}

    record Wheel(int notches) implements InputCommand {}

    record KeyPress(int code) implements InputCommand {}

    record KeyRelease(int code) implements InputCommand {}

    record ReleaseAll() implements InputCommand {}
}
