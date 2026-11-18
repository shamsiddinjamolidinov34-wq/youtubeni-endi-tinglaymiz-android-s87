package uz.tinglaymiz.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Fon xizmati: ovoz ekran o'chiq paytda ham, ilova yopiq paytda ham davom etadi.
 * Xizmat "mediaPlayback" turidagi oldingi reja xizmati sifatida ishlaydi,
 * bildirishnoma orqali qulflangan ekrandan boshqariladi.
 */
public class PlaybackService extends MediaSessionService {

    private MediaSession session;

    /**
     * Server 403/404 qaytarsa, xato darhol ko'rsatiladi (ilova zaxira usulga o'tadi).
     * Internet uzilganda esa pleyer taxminan 5 daqiqagacha jim kutib, o'zi davom etadi.
     */
    private static final class Policy extends DefaultLoadErrorHandlingPolicy {
        @Override
        public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo info) {
            if (info.exception instanceof HttpDataSource.InvalidResponseCodeException) {
                return C.TIME_UNSET;
            }
            if (info.errorCount > 60) {
                return C.TIME_UNSET;
            }
            return super.getRetryDelayMsFor(info);
        }

        @Override
        public int getMinimumLoadableRetryCount(int dataType) {
            return 60;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DataSource.Factory dataSource =
                new ResolvingDataSource.Factory(new DefaultDataSource.Factory(this, http), new YtSource());

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 180000, 2500, 5000)
                .build();

        ExoPlayer player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSource)
                        .setLoadErrorHandlingPolicy(new Policy()))
                .setLoadControl(loadControl)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build();

        // Faqat ovoz kerak: video yo'li o'chirib qo'yiladi
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build());

        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);

        session = new MediaSession.Builder(this, player).setSessionActivity(open).build();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        super.onDestroy();
    }
}
