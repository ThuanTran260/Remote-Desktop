package com.ltm.rd.host;

/** Creates a fresh {@link RobotPort}; one call, one Robot. */
public interface RobotPortFactory {
    RobotPort create() throws AdapterException;
}
