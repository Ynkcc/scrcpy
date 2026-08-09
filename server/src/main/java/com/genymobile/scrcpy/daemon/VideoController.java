package com.genymobile.scrcpy.daemon;

public interface VideoController {
    boolean startVideoStream(int displayId);
    boolean stopVideoStream();
    boolean isVideoStarted();
}
