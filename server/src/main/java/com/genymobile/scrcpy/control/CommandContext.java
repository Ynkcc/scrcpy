package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.VideoController;

public class CommandContext {

    private final DeviceMessageSender sender;
    private final Controller controller;
    private final VideoController videoController;

    public CommandContext(DeviceMessageSender sender, Controller controller, VideoController videoController) {
        this.sender = sender;
        this.controller = controller;
        this.videoController = videoController;
    }

    public DeviceMessageSender getSender() {
        return sender;
    }

    public Controller getController() {
        return controller;
    }

    public VideoController getVideoController() {
        return videoController;
    }
}
