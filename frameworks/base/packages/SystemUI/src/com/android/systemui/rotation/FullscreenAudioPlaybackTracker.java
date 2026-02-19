package com.android.systemui.rotation;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;


import com.android.systemui.shared.rotation.PlaybackState;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FullscreenAudioPlaybackTracker {

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final AudioManager mAudioManager;
    private final PackageManager mPm;

    private volatile String mTopPackage = null;
    private volatile boolean mTopIsFullscreen = false;
    private boolean mLastLoggedState = false;


    // UIDs que estão com playback ativo
    private final Set<Integer> mActivePlaybackUids = new HashSet<>();

    private final AudioManager.AudioPlaybackCallback mAudioCb =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    recomputeActiveUids(configs);
                    recompute();
                }
            };

    private final TaskStackChangeListener mTaskListener = new TaskStackChangeListener() {
        @Override public void onTaskStackChanged() { updateTopTask(); }
        @Override public void onTaskMovedToFront(int taskId) { updateTopTask(); }
        @Override public void onActivityPinned(String packageName, int userId, int taskId, int stackId) {
            updateTopTask();
        }
        @Override public void onActivityUnpinned() { updateTopTask(); }
    };

    public FullscreenAudioPlaybackTracker(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mPm = context.getPackageManager();
    }

    public void start() {
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskListener);
        updateTopTask();

        mAudioManager.registerAudioPlaybackCallback(mAudioCb, mMain);

        // Primeira leitura
        try {
            List<AudioPlaybackConfiguration> configs = mAudioManager.getActivePlaybackConfigurations();
            recomputeActiveUids(configs);
            recompute();
        } catch (Throwable ignored) {
        }
    }

    public void stop() {
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskListener);
        try { TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskListener); } catch (Throwable ignored) {}
        try { mAudioManager.unregisterAudioPlaybackCallback(mAudioCb); } catch (Throwable ignored) {}
        synchronized (mActivePlaybackUids) { mActivePlaybackUids.clear(); }
        PlaybackState.setPlayingFullscreenVideo(false);
    }

    private void recomputeActiveUids(List<AudioPlaybackConfiguration> configs) {
        synchronized (mActivePlaybackUids) {
            mActivePlaybackUids.clear();
            if (configs == null) return;

            for (AudioPlaybackConfiguration c : configs) {
                if (c == null) continue;
                if (!c.isActive()) continue;

                // Heurística: só USAGE_MEDIA
                AudioAttributes aa = c.getAudioAttributes();
                if (aa != null && aa.getUsage() != AudioAttributes.USAGE_MEDIA) continue;

                mActivePlaybackUids.add(c.getClientUid());
            }
        }
    }

    private void updateTopTask() {
        mMain.post(() -> {
            try {
                List<ActivityManager.RunningTaskInfo> tasks =
                        ActivityTaskManager.getInstance().getTasks(1);
                if (tasks == null || tasks.isEmpty()) {
                    mTopPackage = null;
                    mTopIsFullscreen = false;
                    recompute();
                    return;
                }

                ActivityManager.RunningTaskInfo top = tasks.get(0);
                if (top.topActivity != null) {
                    mTopPackage = top.topActivity.getPackageName();
                } else {
                    mTopPackage = null;
                }

                int mode = top.configuration.windowConfiguration.getWindowingMode();

                boolean fullscreen = mode == WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
                boolean pinned = mode == WindowConfiguration.WINDOWING_MODE_PINNED;
                boolean multi = mode == WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
                boolean freeform = mode == WindowConfiguration.WINDOWING_MODE_FREEFORM;


                mTopIsFullscreen = fullscreen && !pinned && !multi && !freeform;

            } catch (Throwable t) {
                mTopPackage = null;
                mTopIsFullscreen = false;
            }

            recompute();
        });
    }

    private void recompute() {
        final String topPkg = mTopPackage;
        final boolean topFullscreen = mTopIsFullscreen;

        boolean playing = false;

        boolean newState = playing;
        if (newState != mLastLoggedState) {
            Log.i("FullscreenAudioPlaybackTracker",
                "playingFullscreenVideo=" + newState +
                " topPkg=" + topPkg +
                " topFullscreen=" + topFullscreen +
                " activeUids=" + mActivePlaybackUids.size());
            mLastLoggedState = newState;
        }

        if (topPkg != null && topFullscreen) {
            final int topUid = getUidForPackage(topPkg);
            if (topUid != -1) {
                synchronized (mActivePlaybackUids) {
                    playing = mActivePlaybackUids.contains(topUid);
                }
            }
        }

        PlaybackState.setPlayingFullscreenVideo(playing);
    }

    private int getUidForPackage(String pkg) {
        try {
            return mPm.getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }
}
