package com.android.systemui.rotation.video;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.Display;

import com.android.internal.view.RotationPolicy;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;

import android.app.ActivityManager;
import android.content.ComponentName;

import java.util.List;

/**
 * Follows device orientation (portrait/landscape) while:
 * - user has rotation locked
 * - the focused app has (or recently had) MOVIE playback
 *
 * Restores original locked rotation when app/task changes or controller stops.
 */
public final class VideoRotationController {
    private static final String TAG = "VideoRotationCtl";
    private static final int INVALID_TASK_ID = -1;

    // Keeps behavior stable during pause/seek/small interruptions.
    private static final long VIDEO_GRACE_MS = 2000; // 12s

    // Debounce orientation changes.
    private static final long ORIENTATION_DEBOUNCE_MS = 120;

    private final Context mContext;
    private final Handler mMainHandler;
    private final AudioManager mAudioManager;
    private final PackageManager mPm;

    // Cache package -> uid lookups
    private final ArrayMap<String, Integer> mUidCache = new ArrayMap<>();

    // UID -> last time we saw MOVIE playback (active)
    private final android.util.SparseLongArray mLastMovieSeenUptime = new android.util.SparseLongArray();

    private boolean mStarted;

    // Current focused task/app
    private int mTopTaskId = INVALID_TASK_ID;
    private int mTopUid = -1;
    private String mTopPackage = null;

    // "Armed" means we are allowed to follow sensor and apply lock-at-angle.
    private boolean mArmed;

    // Restore data
    private int mRestoreRotation = Surface.ROTATION_0;
    private int mArmedTaskId = INVALID_TASK_ID;

    // Orientation tracking
    private OrientationEventListener mOrientationListener;
    private int mDesiredRotation = Surface.ROTATION_0;
    private int mLastAppliedRotation = -1;
    private long mLastOrientationDecisionUptime;

    private final AudioManager.AudioPlaybackCallback mPlaybackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    final long now = SystemClock.uptimeMillis();

                    for (AudioPlaybackConfiguration c : configs) {
                        if (!c.isActive()) continue;

                        final AudioAttributes aa = c.getAudioAttributes();
                        final int uid = c.getClientUid();

                        // LOG COMPLETO
                        Log.d(TAG, "APC uid=" + uid
                                + " active=" + c.isActive()
                                + " ptype=" + c.getPlayerType()
                                + " usage=" + (aa != null ? aa.getUsage() : -1)
                                + " content=" + (aa != null ? aa.getContentType() : -1)
                                + " flags=0x" + Integer.toHexString(aa != null ? aa.getFlags() : 0)
                                + " piid=" + c.getPlayerInterfaceId());

                        if (aa == null) continue;
                        if (uid <= 0) continue;

                        // Só mídia
                        if (aa.getUsage() != AudioAttributes.USAGE_MEDIA) continue;

                        // Sinais fortes de vídeo:
                        final boolean isMovie = (aa.getContentType() == AudioAttributes.CONTENT_TYPE_MOVIE);
                        final boolean hasAvSync = ( (aa.getFlags() & AudioAttributes.FLAG_HW_AV_SYNC) != 0 );

                        // >>> IMPORTANTE: NÃO trate "UNKNOWN" como vídeo sem AV_SYNC
                        if (!isMovie && !hasAvSync) {
                            // não marca como vídeo
                            continue;
                        }

                        // agora sim marca "vídeo recente"
                        mLastMovieSeenUptime.put(uid, now);
                        Log.d(TAG, "VIDEO playback detected for uid=" + uid
                                + " movie=" + isMovie + " av_sync=" + hasAvSync);
                    }

                    // Re-evaluate quickly on playback changes.
                    mMainHandler.post(VideoRotationController.this::evaluateState);
                }
            };

    private static final long POLL_MS = 2000;

    private final Runnable mPlaybackPoll = new Runnable() {
        @Override public void run() {
            if (!mStarted) return;

            // trabalha só quando for útil
            if (mTopUid > 0 && mTopTaskId != INVALID_TASK_ID) {
                refreshFromActivePlaybackConfigs();
            }
            evaluateState();

            // <-- o mais importante: sempre reagenda enquanto started
            mMainHandler.postDelayed(this, POLL_MS);
        }
    };

    private void refreshFromActivePlaybackConfigs() {
        final long now = SystemClock.uptimeMillis();
        List<AudioPlaybackConfiguration> configs = mAudioManager.getActivePlaybackConfigurations();
        for (AudioPlaybackConfiguration c : configs) {
            if (!c.isActive()) continue;
            AudioAttributes aa = c.getAudioAttributes();
            if (aa == null) continue;
            if (aa.getUsage() != AudioAttributes.USAGE_MEDIA) continue;
            if (aa.getContentType() == AudioAttributes.CONTENT_TYPE_MUSIC) continue;

            int uid = c.getClientUid();
            if (uid > 0) mLastMovieSeenUptime.put(uid, now);
        }
    }

    private final TaskStackChangeListener mTaskListener = new TaskStackChangeListener() {
        @Override
        public void onTaskMovedToFront(int taskId) {
            mMainHandler.post(VideoRotationController.this::onTopTaskPossiblyChanged);
        }

        @Override
        public void onTaskStackChanged() {
            mMainHandler.post(VideoRotationController.this::onTopTaskPossiblyChanged);
        }

        @Override
        public void onTaskRemoved(int taskId) {
            // If our armed task disappears, restore immediately.
            if (mArmed && taskId == mArmedTaskId) {
                mMainHandler.post(VideoRotationController.this::restoreAndDisarm);
            }
            mMainHandler.post(VideoRotationController.this::onTopTaskPossiblyChanged);
        }
    };

    public VideoRotationController(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());
        mAudioManager = context.getSystemService(AudioManager.class);
        mPm = context.getPackageManager();
    }

    public void start() {
        Log.d(TAG, "start()");
        if (mStarted) return;
        mStarted = true;

        // Task listener
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskListener);

        // Payback Poll
        mMainHandler.removeCallbacks(mPlaybackPoll);
        mMainHandler.post(mPlaybackPoll);

        // Audio playback callback
        if (mAudioManager != null) {
            mAudioManager.registerAudioPlaybackCallback(mPlaybackCallback, mMainHandler);
        }

        // Sensor orientation listener
        mOrientationListener = new OrientationEventListener(mContext) {
            @Override
            public void onOrientationChanged(int degrees) {
                
                if (degrees == ORIENTATION_UNKNOWN) return;
                final long now = SystemClock.uptimeMillis();
                if (now - mLastOrientationDecisionUptime < ORIENTATION_DEBOUNCE_MS) {
                    return;
                }
                mLastOrientationDecisionUptime = now;
                int rot = degreesToRotationWithHysteresis(degrees, mDesiredRotation);
                Log.d(TAG, "degrees=" + degrees + " prev=" + mDesiredRotation + " -> rot=" + rot);
                if (rot != mDesiredRotation) {
                    mDesiredRotation = rot;
                    evaluateState();
                } else if (mArmed) {
                    // garante reaplicar se já está armado e algo mexeu na rotação
                    evaluateState();
                }
            }
        };
        if (mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
        } else {
            Log.w(TAG, "Orientation sensor not available; controller will be ineffective.");
        }

        // Initial fetch
        onTopTaskPossiblyChanged();
    }

    public void stop() {
        Log.d(TAG, "stop()");
        if (!mStarted) return;
        mStarted = false;
        mMainHandler.removeCallbacks(mPlaybackPoll);
        restoreAndDisarm();

        try {
            TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskListener);
        } catch (Throwable t) {
            // ignore
        }

        if (mAudioManager != null) {
            try {
                mAudioManager.unregisterAudioPlaybackCallback(mPlaybackCallback);
            } catch (Throwable t) {
                // ignore
            }
        }

        if (mOrientationListener != null) {
            mOrientationListener.disable();
            mOrientationListener = null;
        }

        // limpa estado interno
        mTopUid = -1;
        mTopTaskId = INVALID_TASK_ID;
        mTopPackage = null;

        mArmed = false;
        mArmedTaskId = INVALID_TASK_ID;
        mRestoreRotation = -1;
        mLastAppliedRotation = -1;

        mLastOrientationDecisionUptime = 0L;

        // MUITO importante pro “só alguns segundos”
        mLastMovieSeenUptime.clear();
    }

    private void onTopTaskPossiblyChanged() {
        ActivityManager.RunningTaskInfo task = null;
        try {
            task = ActivityManagerWrapper.getInstance().getRunningTask();
        } catch (Throwable t) {
            Log.w(TAG, "getRunningTask failed", t);
        }

        final int newTaskId = (task != null) ? task.taskId : INVALID_TASK_ID;
        final String newPkg = extractPackage(task);

        if (newTaskId != mTopTaskId || (newPkg != null && !newPkg.equals(mTopPackage))) {
            // If we were armed for a different task, restore immediately.
            if (mArmed && newTaskId != mArmedTaskId) {
                restoreAndDisarm();
            }
            mTopTaskId = newTaskId;
            mTopPackage = newPkg;
            mTopUid = resolveUid(newPkg);

            Log.d(TAG, "Top task changed: taskId=" + mTopTaskId
                    + " pkg=" + mTopPackage
                    + " uid=" + mTopUid);
        }

        evaluateState();
    }

    private void evaluateState() {

        if (!mStarted) return;

        // Only act when user rotation is locked.
        if (!RotationPolicy.isRotationLocked(mContext)) {
            if (mArmed) restoreAndDisarm();
            return;
        }

        if (mTopUid <= 0 || mTopTaskId == INVALID_TASK_ID) {
            if (mArmed) restoreAndDisarm();
            return;
        }

        final boolean videoForTop = isMoviePlaybackActiveOrRecent(mTopUid);
        Log.d(TAG, "Top uid=" + mTopUid + " pkg=" + mTopPackage);

        Log.d(TAG, "evaluateState locked=" 
                + RotationPolicy.isRotationLocked(mContext)
                + " video=" + videoForTop
                + " desired=" + mDesiredRotation
                + " current=" + getDisplayRotation());

        if (!videoForTop) {
            if (mArmed) restoreAndDisarm();
            return;
        }

        // Arm if needed
        if (!mArmed) {
            mArmed = true;
            mArmedTaskId = mTopTaskId;
            mRestoreRotation = getDisplayRotation();
            // Reset applied rotation tracking to avoid skipping first apply.
            Log.d(TAG, "ARMING for task=" + mTopTaskId + " restoreRotation=" + mRestoreRotation);
            mLastAppliedRotation = -1;
        }

        // Follow the device orientation.
        applyDesiredRotationIfNeeded();
    }

    private void applyDesiredRotationIfNeeded() {
        Log.d(TAG, "Forcing rotation to " + mDesiredRotation);
        if (!mArmed) return;

        // Avoid fighting apps that already changed rotation.
        final int current = getDisplayRotation();
        if (current == mDesiredRotation) {
            // No need to force.
            mLastAppliedRotation = current;
            return;
        }

        // Only force if the change is meaningful (prevents spam).
        if (mLastAppliedRotation == mDesiredRotation) {
            return;
        }

        RotationPolicy.setRotationLockAtAngle(
                mContext,
                /* enabled */ true,
                /* rotation */ mDesiredRotation,
                /* caller */ "VideoRotationController#followSensor");

        mLastAppliedRotation = mDesiredRotation;
    }

    private void restoreAndDisarm() {
        if (!mArmed) return;

        Log.d(TAG, "RESTORING rotation to " + mRestoreRotation);

        // Se por algum motivo estiver inválido, cai para a rotação atual
        int rot = mRestoreRotation;
        if (rot < 0 || rot > 3) {
            rot = getDisplayRotation();
            Log.w(TAG, "mRestoreRotation inválido; usando current=" + rot);
        }

        RotationPolicy.setRotationLockAtAngle(
                mContext,
                /* enabled */ true,
                /* rotation */ rot,
                /* caller */ "VideoRotationController#restore");

        mArmed = false;
        mArmedTaskId = INVALID_TASK_ID;

        // reset de estado
        mRestoreRotation = -1;
        mLastAppliedRotation = -1;
    }

    private boolean isMoviePlaybackActiveOrRecent(int uid) {
        final long now = SystemClock.uptimeMillis();
        final long last = mLastMovieSeenUptime.get(uid, 0L);
        return (last > 0L) && (now - last <= VIDEO_GRACE_MS);
    }

    private int getDisplayRotation() {
        Display d = mContext.getDisplay();
        if (d == null) return Surface.ROTATION_0;
        return d.getRotation();
    }

    private static String extractPackage(ActivityManager.RunningTaskInfo task) {
        if (task == null) return null;
        ComponentName cn = task.topActivity;
        if (cn == null) cn = task.baseActivity;
        return (cn != null) ? cn.getPackageName() : null;
    }

    private int resolveUid(String pkg) {
        if (pkg == null) return -1;
        Integer cached = mUidCache.get(pkg);
        if (cached != null) return cached;

        try {
            ApplicationInfo ai = mPm.getApplicationInfo(pkg, 0);
            int uid = ai.uid;
            mUidCache.put(pkg, uid);
            return uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        } catch (Throwable t) {
            Log.w(TAG, "resolveUid failed for " + pkg, t);
            return -1;
        }
    }

    /**
     * Converts sensor degrees into Surface rotation with simple hysteresis.
     */
    private static int degreesToRotationWithHysteresis(int degrees, int prevRotation) {
        int d = ((degrees % 360) + 360) % 360;

        final int PORTRAIT_0_CENTER = 0;
        final int LANDSCAPE_90_CENTER = 90;
        final int PORTRAIT_180_CENTER = 180;
        final int LANDSCAPE_270_CENTER = 270;

        final int W = 30;

        if (isWithin(d, PORTRAIT_0_CENTER, W)) return Surface.ROTATION_0;

        // >>> SWAP AQUI <<<
        if (isWithin(d, LANDSCAPE_90_CENTER, W)) return Surface.ROTATION_270;

        if (isWithin(d, PORTRAIT_180_CENTER, W)) return Surface.ROTATION_180;

        // >>> SWAP AQUI <<<
        if (isWithin(d, LANDSCAPE_270_CENTER, W)) return Surface.ROTATION_90;

        return prevRotation;
    }

    private static boolean isWithin(int d, int center, int w) {
        int diff = Math.abs(d - center);
        diff = Math.min(diff, 360 - diff);
        return diff <= w;
    }
}