package com.android.systemui.shared.rotation;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PlaybackState {
    private static final AtomicBoolean PLAYING_FULLSCREEN_VIDEO = new AtomicBoolean(false);

    public static boolean isPlayingFullscreenVideo() {
        return PLAYING_FULLSCREEN_VIDEO.get();
    }

    public static void setPlayingFullscreenVideo(boolean v) {
        PLAYING_FULLSCREEN_VIDEO.set(v);
    }

    private PlaybackState() {}
}
