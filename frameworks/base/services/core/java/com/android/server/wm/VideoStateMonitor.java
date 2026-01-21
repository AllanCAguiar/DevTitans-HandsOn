package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;
import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.view.WindowManager;
import java.util.List;

/**
 * Monitora o sistema para inferir se um vídeo está sendo reproduzido.
 */
public class VideoStateMonitor {

    private final Context mContext;
    private final WindowManagerService mWms;
    private MediaSessionManager mMediaSessionManager;
    private AudioManager mAudioManager;
    private boolean mIsVideoPlaying = false;

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionListener = 
            controllers -> recalculateVideoState(controllers);

    public VideoStateMonitor(Context context, WindowManagerService wms) {
        mContext = context;
        mWms = wms;
    }

    public void onSystemReady() {
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mAudioManager = mContext.getSystemService(AudioManager.class);
        
        if (mMediaSessionManager != null) {
            mMediaSessionManager.addOnActiveSessionsChangedListener(
                mSessionListener, null, mWms.mH);
        }
    }

    private void recalculateVideoState(List<MediaController> controllers) {
        boolean mediaPlaying = false;

        // 1. Verifica se há mídia em estado PLAYING
        if (controllers != null) {
            for (MediaController controller : controllers) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    mediaPlaying = true;
                    break;
                }
            }
        }

        // 2. Verifica se o áudio está ativo
        boolean audioActive = mAudioManager != null && mAudioManager.isMusicActive();

        // 3. Verifica FLAG_KEEP_SCREEN_ON (Diferencia vídeo de música)
        boolean keepScreenOn = false;
        synchronized (mWms.mGlobalLock) {
            DisplayContent dc = mWms.mRoot.getDisplayContent(DEFAULT_DISPLAY);
            if (dc != null && dc.mCurrentFocus != null) {
                keepScreenOn = (dc.mCurrentFocus.mAttrs.flags 
                        & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
            }
        }

        boolean newState = mediaPlaying && audioActive && keepScreenOn;

        if (mIsVideoPlaying != newState) {
            mIsVideoPlaying = newState;
            // Solicita a atualização da rotação via Handler para sair do contexto de callback de mídia
            mWms.mH.post(() -> mWms.updateRotation(false, false));
        }
    }

    public boolean isVideoPlaying() {
        return mIsVideoPlaying;
    }
}