package com.example.minseo3.tts;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import android.app.Notification;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.example.minseo3.BookListActivity;
import com.example.minseo3.R;

/**
 * TTS 재생 노티 빌더. {@link Notification.MediaStyle} + 채널 생성 + 컨텐츠 인텐트.
 *
 * <p>V1 default: play/pause 액션 1개. 잠금화면 ⏮ ⏭ 는 OQ 8 결정 후 추가 예정.
 * MediaSession 의 PlaybackState 액션 (SKIP_NEXT/PREV) 은 별도로 세팅되어 있어 헤드셋
 * 미디어 키 라우팅은 그대로 동작.
 */
final class TtsNotificationBuilder {

    static final String CHANNEL_ID = "tts_playback";
    static final int NOTIF_ID = 1729;

    private TtsNotificationBuilder() {}

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "리더 음성 재생", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        ch.setShowBadge(false);
        ch.setDescription("책 본문을 음성으로 재생합니다.");
        nm.createNotificationChannel(ch);
    }

    /** 첫 startForeground 데드라인용 placeholder. 컨텐츠는 곧바로 build() 로 덮음. */
    static Notification buildPlaceholder(Context ctx) {
        ensureChannel(ctx);
        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tts_notification)
                .setContentTitle("리더")
                .setContentText("재생 준비 중…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    static Notification build(
            Context ctx,
            MediaSessionCompat session,
            int state,
            @Nullable String displayTitle,
            int currentPage,
            int pageCount) {

        ensureChannel(ctx);

        boolean playing = (state == TtsPlaybackService.STATE_PLAYING);
        String title = (displayTitle != null && !displayTitle.isEmpty()) ? displayTitle : "리더";
        String text = pageCount > 0
                ? (currentPage + 1) + " / " + pageCount + "쪽"
                : "";

        Intent contentIntent = new Intent(ctx, BookListActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(ctx, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // V1 default: play/pause 단일 액션. ⏮ ⏭ 는 OQ 8 결정 후 추가.
        PendingIntent toggleAction = MediaButtonReceiver.buildMediaButtonPendingIntent(ctx,
                playing ? PlaybackStateCompat.ACTION_PAUSE : PlaybackStateCompat.ACTION_PLAY);
        int toggleIcon = playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        String toggleLabel = playing ? "일시정지" : "재생";

        PendingIntent stopAction = MediaButtonReceiver.buildMediaButtonPendingIntent(ctx,
                PlaybackStateCompat.ACTION_STOP);

        MediaStyle style = new MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0)
                .setShowCancelButton(true)
                .setCancelButtonIntent(stopAction);

        return new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tts_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPi)
                .setDeleteIntent(stopAction)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(toggleIcon, toggleLabel, toggleAction)
                .setStyle(style)
                .build();
    }
}
