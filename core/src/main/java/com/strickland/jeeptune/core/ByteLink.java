package com.strickland.jeeptune.core;

import java.io.IOException;

/** The raw byte pipe to the dongle (a Bluetooth socket on the tablet, a fake in tests). */
public interface ByteLink {
    void write(byte[] b) throws IOException;
    void close();
}
