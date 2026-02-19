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
import com.android.internal.view.RotationPolicy;

import com.android.systemui.shared.rotation.PlaybackState;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FullscreenAudioPlaybackTracker {

    private static final String TAG = "FullscreenAudioPlaybackTracker";
    private boolean mLastPlaying = false;
    private boolean mLastFullscreen = false;
    private String mVideoOwnerPkg = null;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final AudioManager mAudioManager;
    private final PackageManager mPm;

    private volatile String mTopPackage = null;
    private volatile boolean mTopIsFullscreen = false;

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
        Log.i(TAG, "START tracker pid=" + android.os.Process.myPid());
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
        try { TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskListener); }
        catch (Throwable ignored) {}

        try { mAudioManager.unregisterAudioPlaybackCallback(mAudioCb); }
        catch (Throwable ignored) {}

        synchronized (mActivePlaybackUids) { mActivePlaybackUids.clear(); }
        PlaybackState.setPlayingFullscreenVideo(false);
        mLastPlaying = false;
        mLastFullscreen = false;
        mTopPackage = null;
        mTopIsFullscreen = false;

    }



    private void recomputeActiveUids(List<AudioPlaybackConfiguration> configs) {
        synchronized (mActivePlaybackUids) {
            mActivePlaybackUids.clear();
            if (configs == null) return;

            for (AudioPlaybackConfiguration c : configs) {
                if (c == null) continue;
                if (!c.isActive()) continue;

                AudioAttributes aa = c.getAudioAttributes();
                Log.i(TAG, "AUDIO uid=" + c.getClientUid()
                        + " usage=" + aa.getUsage()
                        + " contentType=" + aa.getContentType());
                if (aa == null) continue;

                if (aa.getUsage() != AudioAttributes.USAGE_MEDIA) continue;

                // ✅ filtro anti-player-de-música
                if (aa.getContentType() == AudioAttributes.CONTENT_TYPE_MUSIC) continue;

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

        // Se saiu do fullscreen do app dono do vídeo → restaurar
        if (mVideoOwnerPkg != null
                && mVideoOwnerPkg.equals(topPkg)
                && mLastFullscreen
                && !topFullscreen) {

            int saved = PlaybackState.consumeSavedLockRotation();
            if (saved != -1) {
                try {
                    RotationPolicy.setRotationLockAtAngle(
                            mContext, true, saved,
                            "FullscreenAudioPlaybackTracker#restoreOnExitFullscreen");
                    Log.i(TAG, "restoreOnExitFullscreen rotation=" + saved);
                } catch (Throwable t) {
                    Log.w(TAG, "restoreOnExitFullscreen failed", t);
                }
            }

            mVideoOwnerPkg = null;
            mLastPlaying = false;
            PlaybackState.setPlayingFullscreenVideo(false);
        }



        // Detecta saída de fullscreen (gatilho de restore)
        final boolean wasFullscreen = mLastFullscreen;
        mLastFullscreen = topFullscreen;

        // Ainda está em fullscreen? então calcula "playing"
        Log.i(TAG, "TOP topPkg=" + topPkg + " topFullscreen=" + topFullscreen
                + " activeUids=" + mActivePlaybackUids.size());

        boolean playing = false;
        if (topPkg != null && topFullscreen) {
            final int topUid = getUidForPackage(topPkg);
            if (topUid != -1) {
                synchronized (mActivePlaybackUids) {
                    playing = mActivePlaybackUids.contains(topUid);
                }
            }
        }

        final boolean newState = playing;
        final boolean oldState = mLastPlaying;

        // Log só quando muda
        if (newState != oldState) {
            Log.i(TAG, "playingFullscreenVideo=" + newState
                    + " topPkg=" + topPkg
                    + " topFullscreen=" + topFullscreen
                    + " activeUids=" + mActivePlaybackUids.size());
        }

        // Atualiza estado global (arma/desarma)
        PlaybackState.setPlayingFullscreenVideo(newState);

        // Começou a tocar vídeo fullscreen -> tenta auto-aceitar proposal recente
        if (!oldState && newState) {
            mVideoOwnerPkg = topPkg;
            if (RotationPolicy.isRotationLocked(mContext)) {
                int proposal = PlaybackState.consumeRecentProposalRotation(1500);
                if (proposal != -1) {
                    int orig = PlaybackState.consumeRecentProposalWindowRotation();
                    if (orig != -1) {
                        PlaybackState.saveLockRotationIfUnset(orig);
                    }
                    try {
                        RotationPolicy.setRotationLockAtAngle(
                                mContext,
                                /* enabled= */ true,
                                /* rotation= */ proposal,
                                "FullscreenAudioPlaybackTracker#autoAcceptOnStart");
                        Log.i(TAG, "autoAcceptOnStart rotation=" + proposal);
                    } catch (Throwable t) {
                        Log.w(TAG, "autoAcceptOnStart failed", t);
                    }
                }
            }
        }

        // IMPORTANTE: NÃO RESTAURA MAIS AQUI quando newState vira false
        // (pause/seek/buffer não derruba a rotação)

        mLastPlaying = newState;
    }


    private int getUidForPackage(String pkg) {
        try {
            return mPm.getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }
}
