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
import android.util.SparseArray;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ContextualRotationController {
    private static final String TAG = "ContextualRotation";
    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mUpdateCallback;

    // Armazena os UIDs que estão tocando áudio (considerando o debounce)
    private final Set<Integer> mActiveAudioUids = new HashSet<>();
    
    // Armazena os agendamentos de remoção (para o debounce)
    private final SparseArray<Runnable> mDebounceRunnables = new SparseArray<>();

    private boolean mListenersRegistered = false;
    private static final long DEBOUNCE_DELAY_MS = 2000; // 2 segundos de tolerância para buffer

    public ContextualRotationController(Context context, Runnable updateCallback) {
        mContext = context;
        mUpdateCallback = updateCallback;
        mHandler = new Handler(Looper.getMainLooper());
        mHandler.postDelayed(this::registerListeners, 5000);
    }

    // --- A NOVA LÓGICA: Pergunta Direta ---
    // O DisplayRotation chama isso passando o UID da janela focada
    public boolean isUidPlaying(int uid) {
        synchronized (mActiveAudioUids) {
            return mActiveAudioUids.contains(uid);
        }
    }

    private void registerListeners() {
        if (mListenersRegistered) return;
        try {
            MediaSessionManager msm = mContext.getSystemService(MediaSessionManager.class);
            AudioManager am = mContext.getSystemService(AudioManager.class);

            if (msm != null) {
                msm.addOnActiveSessionsChangedListener(
                    controllers -> updateAudioState(controllers, null), null, mHandler);
            }
            if (am != null) {
                am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                    @Override
                    public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                        updateAudioState(null, configs);
                    }
                }, mHandler);
            }
            if (msm != null && am != null) {
                mListenersRegistered = true;
                updateAudioState(null, null);
            } else {
                mHandler.postDelayed(this::registerListeners, 5000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao registrar listeners", e);
        }
    }

    private void updateAudioState(List<MediaController> sessions, List<AudioPlaybackConfiguration> audioConfigs) {
        Set<Integer> currentPlayingUids = new HashSet<>();

        try {
            // 1. Coleta UIDs via MediaSession (YouTube, Netflix, Spotify)
            if (sessions == null) {
                MediaSessionManager msm = mContext.getSystemService(MediaSessionManager.class);
                if (msm != null) sessions = msm.getActiveSessions(null);
            }
            if (sessions != null) {
                for (MediaController c : sessions) {
                    if (c.getPlaybackState() != null && c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) {
                        // Log.v(TAG, "Session Playing: " + c.getPackageName() + " uid=" + c.getSessionActivity().getCreatorUid()); // Cuidado com nulls aqui se for usar
                        // O UID da sessão nem sempre é fácil de pegar, vamos confiar mais no AudioRaw abaixo
                    }
                }
            }

            // 2. Coleta UIDs via AudioPlaybackConfiguration (Mais preciso para o hardware)
            if (audioConfigs == null) {
                AudioManager am = mContext.getSystemService(AudioManager.class);
                if (am != null) audioConfigs = am.getActivePlaybackConfigurations();
            }
            if (audioConfigs != null) {
                for (AudioPlaybackConfiguration c : audioConfigs) {
                    if (c.isActive()) {
                        int usage = c.getAudioAttributes().getUsage();
                        if (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME) {
                            currentPlayingUids.add(c.getClientUid());
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Erro check midia", e);
        }

        processUidUpdates(currentPlayingUids);
    }

    private void processUidUpdates(Set<Integer> realTimeUids) {
        synchronized (mActiveAudioUids) {
            // 1. Novos UIDs tocando (Entram imediatamente)
            for (Integer uid : realTimeUids) {
                if (!mActiveAudioUids.contains(uid)) {
                    mActiveAudioUids.add(uid);
                    // Cancela qualquer remoção pendente para este UID
                    Runnable pending = mDebounceRunnables.get(uid);
                    if (pending != null) {
                        mHandler.removeCallbacks(pending);
                        mDebounceRunnables.remove(uid);
                    }
                    Log.d(TAG, "UID Playing START: " + uid);
                    notifyChange();
                } else {
                    // Já estava tocando, apenas garante que não será removido
                    Runnable pending = mDebounceRunnables.get(uid);
                    if (pending != null) {
                        mHandler.removeCallbacks(pending);
                        mDebounceRunnables.remove(uid);
                    }
                }
            }

            // 2. UIDs que pararam (Saem com Debounce)
            // Cria uma cópia para iterar
            Set<Integer> toRemoveCandidate = new HashSet<>(mActiveAudioUids);
            toRemoveCandidate.removeAll(realTimeUids); // Deixa apenas quem parou

            for (Integer uid : toRemoveCandidate) {
                // Se já tem um agendamento de remoção, deixa rolar. Se não, agenda.
                if (mDebounceRunnables.get(uid) == null) {
                    Runnable removeTask = () -> {
                        synchronized (mActiveAudioUids) {
                            if (mActiveAudioUids.contains(uid)) {
                                mActiveAudioUids.remove(uid);
                                mDebounceRunnables.remove(uid);
                                Log.d(TAG, "UID Playing END (Debounced): " + uid);
                                notifyChange();
                            }
                        }
                    };
                    mDebounceRunnables.put(uid, removeTask);
                    mHandler.postDelayed(removeTask, DEBOUNCE_DELAY_MS);
                }
            }
        }
    }

    private void notifyChange() {
        if (mUpdateCallback != null) {
            mHandler.post(mUpdateCallback);
        }
    }
}