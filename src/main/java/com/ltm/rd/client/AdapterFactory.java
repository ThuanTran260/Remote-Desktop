package com.ltm.rd.client;

/** Creates the platform adapters of the controlled machine (Robot in production, fakes in tests). */
public interface AdapterFactory {
    /** A fresh screen source for each connect, so a changed display configuration is picked up. */
    ScreenSource newScreenSource() throws AdapterException;

    /** The single input device, created once for the whole application. */
    InputDevice inputDevice() throws AdapterException;

    /** Re-reads the display before each connect; only called while the input executor is idle. */
    void refreshInputDevice() throws AdapterException;
}
