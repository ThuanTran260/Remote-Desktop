package com.ltm.rd.client;

/** Creates a fresh {@link RobotPort}; one call, one Robot. */
public interface RobotPortFactory {
    RobotPort create() throws AdapterException;
}
