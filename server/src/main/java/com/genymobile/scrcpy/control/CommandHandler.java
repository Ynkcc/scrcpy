package com.genymobile.scrcpy.control;

public interface CommandHandler {
    void handle(ControlMessage msg, CommandContext ctx) throws Exception;
}
