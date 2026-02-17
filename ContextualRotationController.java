package com.android.server.wm;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;

/**
 * Controlador de Rotação Contextual.
 * Detecta se há vídeo ou áudio de mídia tocando para permitir rotação
 * mesmo com a tela bloqueada.
 */
public class ContextualRotationController {
    private static final String TAG = "ContextualRotation";
    private final Context mContext;
    private volatile boolean mIsMediaActive = false;

    public ContextualRotationController(Context context) {
        mContext = context;
        Handler handler = new Handler(Looper.getMainLooper());

        // 1. Monitorar Sessões de Mídia (YouTube, Netflix, Spotify)
        MediaSessionManager mediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
        if (mediaSessionManager != null) {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                controllers -> checkMediaState(controllers, null), 
                null, handler);
        }

        // 2. Monitorar Áudio "Cru" (Galeria, Players Locais)
        AudioManager audioManager = mContext.getSystemService(AudioManager.class);
        if (audioManager != null) {
            audioManager.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    checkMediaState(null, configs);
                }
            }, handler);
        }
    }

    // Método central que decide se força a rotação
    public boolean shouldForceRotation() {
        return mIsMediaActive;
    }

    // Lógica unificada de verificação
    private void checkMediaState(List<MediaController> sessions, List<AudioPlaybackConfiguration> audioConfigs) {
        boolean active = false;

        // Checa Sessões (YouTube, etc)
        if (sessions == null) {
            MediaSessionManager msm = mContext.getSystemService(MediaSessionManager.class);
            if (msm != null) sessions = msm.getActiveSessions(null);
        }
        if (sessions != null) {
            for (MediaController controller : sessions) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    active = true;
                    break;
                }
            }
        }

        // Se não achou na sessão, checa se tem áudio saindo (Galeria)
        if (!active) {
            if (audioConfigs == null) {
                AudioManager am = mContext.getSystemService(AudioManager.class);
                if (am != null) audioConfigs = am.getActivePlaybackConfigurations();
            }
            if (audioConfigs != null) {
                for (AudioPlaybackConfiguration config : audioConfigs) {
                    if (config.isActive()) {
                        int usage = config.getAudioAttributes().getUsage();
                        // Filtra apenas Mídia ou Jogos (Ignora notificações/toque)
                        if (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME) {
                            active = true;
                            break;
                        }
                    }
                }
            }
        }

        if (mIsMediaActive != active) {
            Log.d(TAG, "Estado de Mídia Contextual alterado para: " + active);
            mIsMediaActive = active;
        }
    }
}