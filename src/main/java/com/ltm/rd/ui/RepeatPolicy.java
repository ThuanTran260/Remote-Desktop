package com.ltm.rd.ui;

/**
 * What to do with the key-repeat events of a held key: forward every one, or only the first press
 * of each hold (the controlled side then repeats by itself, if the device does).
 */
public enum RepeatPolicy { SEND_ALL, FIRST_PRESS_ONLY }
