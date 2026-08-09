package com.genymobile.scrcpy;

public interface VideoController {
    boolean startVideoStream(int displayId);
    boolean stopVideoStream();
    boolean isVideoStarted();
}
