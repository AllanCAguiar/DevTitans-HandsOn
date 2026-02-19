package com.android.systemui.shared.rotation;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PlaybackState {
    private static final AtomicBoolean PLAYING_FULLSCREEN_VIDEO = new AtomicBoolean(false);

    // Para restaurar depois
    private static final AtomicInteger SAVED_LOCK_ROTATION = new AtomicInteger(-1);

    // Proposal recente (para "abri já deitado")
    private static final AtomicInteger LAST_PROPOSAL_ROTATION = new AtomicInteger(-1);
    private static final AtomicInteger LAST_PROPOSAL_WINDOW_ROTATION = new AtomicInteger(-1);
    private static final AtomicLong LAST_PROPOSAL_UPTIME = new AtomicLong(0L);

    private PlaybackState() {}

    public static boolean isPlayingFullscreenVideo() {
        return PLAYING_FULLSCREEN_VIDEO.get();
    }

    /** Chamado pelo tracker. */
    public static void setPlayingFullscreenVideo(boolean playing) {
        boolean old = PLAYING_FULLSCREEN_VIDEO.getAndSet(playing);
        if (old == playing) return;

        if (!playing) {
            // Limpa proposal recente ao parar
            LAST_PROPOSAL_ROTATION.set(-1);
            LAST_PROPOSAL_WINDOW_ROTATION.set(-1);
            LAST_PROPOSAL_UPTIME.set(0L);
        }
    }

    /** Registra proposal válida (mesmo se ainda não estiver PLAYING). */
    public static void recordRotationProposal(int proposedRotation, int windowRotation) {
        LAST_PROPOSAL_ROTATION.set(proposedRotation);
        LAST_PROPOSAL_WINDOW_ROTATION.set(windowRotation);
        LAST_PROPOSAL_UPTIME.set(SystemClock.uptimeMillis());
    }

    /** Consome a proposal se ela for recente o suficiente. Retorna -1 se não tiver. */
    public static int consumeRecentProposalRotation(long maxAgeMs) {
        long t = LAST_PROPOSAL_UPTIME.get();
        if (t == 0L) return -1;

        long age = SystemClock.uptimeMillis() - t;
        if (age > maxAgeMs) return -1;

        LAST_PROPOSAL_UPTIME.set(0L);
        return LAST_PROPOSAL_ROTATION.getAndSet(-1);
    }

    /** Retorna e consome a windowRotation associada à última proposal registrada. */
    public static int consumeRecentProposalWindowRotation() {
        return LAST_PROPOSAL_WINDOW_ROTATION.getAndSet(-1);
    }

    public static void saveLockRotationIfUnset(int lockRotation) {
        SAVED_LOCK_ROTATION.compareAndSet(-1, lockRotation);
    }

    public static int consumeSavedLockRotation() {
        return SAVED_LOCK_ROTATION.getAndSet(-1);
    }
}
