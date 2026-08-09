package com.genymobile.scrcpy.daemon.control;

import com.genymobile.scrcpy.control.ControlMessage;

public interface CommandHandler {
    void handle(ControlMessage msg, CommandContext ctx) throws Exception;
}
