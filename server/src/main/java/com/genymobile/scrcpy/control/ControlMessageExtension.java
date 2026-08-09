package com.genymobile.scrcpy.control;

import java.io.IOException;

public interface ControlMessageExtension {
    boolean handle(ControlMessage msg) throws IOException;
}
