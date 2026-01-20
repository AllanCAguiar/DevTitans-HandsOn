package com.android.server.wm;

import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
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

    // Listener para mudanças na sessão de mídia
    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionListener = 
            new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override
        public void onActiveSessionsChanged(List<MediaController> controllers) {
            recalculateVideoState(controllers);
        }
    };

    public VideoStateMonitor(Context context, WindowManagerService wms) {
        mContext = context;
        mWms = wms;
        // Inicialização dos serviços deve ser feita no onSystemReady ou similar
        // para evitar null pointers durante o boot
    }

    public void onSystemReady() {
        mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        mAudioManager = mContext.getSystemService(AudioManager.class);
        
        if (mMediaSessionManager != null) {
            // Monitorar sessões ativas (pode requerer permissão MEDIA_CONTENT_CONTROL)
            mMediaSessionManager.addOnActiveSessionsChangedListener(
                mSessionListener, null, new Handler());
        }
    }

    /**
     * O núcleo da heurística.
     */
    private void recalculateVideoState(List<MediaController> controllers) {
        boolean mediaPlaying = false;

        // 1. Verifica MediaSession
        if (controllers != null) {
            for (MediaController controller : controllers) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    mediaPlaying = true;
                    break;
                }
            }
        }

        // 2. Verifica se o áudio está ativo (Redundância de segurança)
        boolean audioActive = mAudioManager.isMusicActive();

        // 3. Verifica FLAG_KEEP_SCREEN_ON (Crucial para distinguir Vídeo de Áudio puro)
        // Isso requer acesso ao WindowState focado no WMS.
        boolean keepScreenOn = mWms.mRoot.getDisplayContent(0).mCurrentFocus != null &&
                               mWms.mRoot.getDisplayContent(0).mCurrentFocus.mAttrs.flags 
                               & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0;

        // Decisão final
        boolean newState = mediaPlaying && audioActive && keepScreenOn;

        if (mIsVideoPlaying != newState) {
            mIsVideoPlaying = newState;
            // Notifica o DisplayRotation para reavaliar a orientação imediatamente
            mWms.updateRotation(false, false);
        }
    }

    public boolean isVideoPlaying() {
        return mIsVideoPlaying;
    }
}