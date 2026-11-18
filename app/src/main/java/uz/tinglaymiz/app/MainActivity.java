package uz.tinglaymiz.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Yagona ekran: qidiruv, natijalar ro'yxati va ijro boshqaruvi. Ekran o'quvchi uchun moslangan. */
public class MainActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final AtomicInteger token = new AtomicInteger();

    private ListenableFuture<MediaController> future;
    private MediaController player;
    private final List<Runnable> whenReady = new ArrayList<>();

    private EditText input;
    private TextView status;
    private TextView nowTitle;
    private LinearLayout panel;
    private Button btnPlay;
    private Button btnSpeed;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<YtClient.Item> items = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    private YtClient.Resolved cur;
    private String curUrl;
    private int attempt;
    private boolean reResolved;
    private boolean announceStart;
    private long resumeMs;
    private final float[] speeds = {1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f};

    // Vaqt birligi: orqaga/oldinga tugmalari va vaqt slayderi shu qadam bilan siljiydi
    private static final long[] STEPS_MS = {
            1000L, 5000L, 10000L, 30000L, 60000L, 300000L, 600000L, 1800000L, 3600000L};
    private static final String[] STEP_NAMES = {
            "1 soniya", "5 soniya", "10 soniya", "30 soniya", "1 daqiqa",
            "5 daqiqa", "10 daqiqa", "30 daqiqa", "1 soat"};
    private static final int REQ_STORAGE = 2;
    /** Pleylist ro'yxatida ko'rsatiladigan videolarning eng ko'p soni. */
    private static final int PREVIEW_MAX = 200;

    private Button btnBack;
    private Button btnFwd;
    private SeekBar seekPos;
    private SeekBar seekStep;
    private TextView stepLabel;
    private LinearLayout dlPanel;
    private int stepIdx = 2;
    private boolean seekTracking;
    private long lastAdjustAt;
    private long pendingMs = -1;

    private String pendingUrl;
    private int pendingType;
    private int pendingFormat;
    private int pendingCap;

    private final DownloadService.Listener dlListener = new DownloadService.Listener() {
        @Override
        public void onEvent(String text, boolean active) {
            if (text != null && !text.isEmpty()) {
                say(text);
            }
            dlPanel.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    };

    private final Runnable seekTicker = new Runnable() {
        @Override
        public void run() {
            if (!screenReaderOn()) {
                updateSeekVisual(false);
            }
            ui.postDelayed(this, 1000L);
        }
    };

    private final Runnable slow = new Runnable() {
        @Override
        public void run() {
            say("Internet sekin yoki uzilgan. Kutilmoqda...");
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean playing) {
            updatePlayButton();
            if (playing) {
                ui.removeCallbacks(slow);
                if (announceStart) {
                    announceStart = false;
                    ErrorLog.i("Ijro boshlandi (urinish " + (attempt + 1) + ")");
                    say("Ijro etilmoqda: " + nowTitle.getText());
                }
            }
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            updatePlayButton();
            ui.removeCallbacks(slow);
            if (state == Player.STATE_BUFFERING) {
                ui.postDelayed(slow, 12000);
            } else if (state == Player.STATE_ENDED) {
                say("Video tugadi.");
            } else if (state == Player.STATE_READY) {
                syncSeekMax();
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            onPlayerFailure(error);
        }
    };

    // ---------------------------------------------------------------- hayot sikli

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildUi();
        stepIdx = getSharedPreferences("ilova", MODE_PRIVATE).getInt("step_idx", 2);
        applyStep(stepIdx, false);
        boolean permissionDialog = askNotificationPermissionOnce();

        SessionToken t = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        future = new MediaController.Builder(this, t).buildAsync();
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    player = future.get();
                    player.addListener(listener);
                    syncFromPlayer();
                    List<Runnable> pending = new ArrayList<>(whenReady);
                    whenReady.clear();
                    for (Runnable r : pending) {
                        r.run();
                    }
                } catch (Throwable e) {
                    ErrorLog.e("Pleyer xizmatiga ulanib bo'lmadi", e);
                    say("Pleyer xizmatiga ulanib bo'lmadi. Ilovani qayta oching va xato jurnalini yuboring.");
                }
            }
        }, new java.util.concurrent.Executor() {
            @Override
            public void execute(Runnable r) {
                ui.post(r);
            }
        });

        Intent launch = getIntent();
        handleIntent(launch);
        if (!permissionDialog) {
            maybeOfferLinkSetup(launch);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        DownloadService.listener = dlListener;
        dlPanel.setVisibility(DownloadService.isActive() ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(seekTicker);
        ui.post(seekTicker);
    }

    @Override
    protected void onPause() {
        if (DownloadService.listener == dlListener) {
            DownloadService.listener = null;
        }
        ui.removeCallbacks(seekTicker);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (player != null) {
            player.removeListener(listener);
        }
        if (future != null) {
            MediaController.releaseFuture(future);
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- interfeys

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            r.addView(v, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        return r;
    }

    private void buildUi() {
        int p = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        input = new EditText(this);
        input.setHint("YouTube'da qidirish yoki havolani qo'ying");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });
        root.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));

        root.addView(row(
                btn("Qidirish", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        doSearch();
                    }
                }),
                btn("Yuklab olish", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onDownloadClicked();
                    }
                })), new LinearLayout.LayoutParams(MATCH, WRAP));

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setText("Qidiruv so'zini yozing yoki YouTube havolasini qo'ying.");
        root.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Ijro paneli: video tanlangandan keyin ko'rinadi
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setVisibility(View.GONE);

        nowTitle = new TextView(this);
        nowTitle.setTextSize(18);
        nowTitle.setTypeface(null, Typeface.BOLD);
        panel.addView(nowTitle, new LinearLayout.LayoutParams(MATCH, WRAP));

        btnPlay = btn("Pauza", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        btnSpeed = btn("Tezlik 1 barobar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSpeed();
            }
        });
        btnBack = btn("10 soniya orqaga", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSeek(-1);
            }
        });
        btnFwd = btn("10 soniya oldinga", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepSeek(1);
            }
        });
        panel.addView(row(btnBack, btnPlay, btnFwd));
        panel.addView(row(
                btnSpeed,
                btn("Joriy vaqt", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sayTime();
                    }
                }),
                btn("To'xtatib yopish", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        stopAll();
                    }
                })));
        buildSeekUi();
        root.addView(panel, new LinearLayout.LayoutParams(MATCH, WRAP));
        buildDownloadPanel(root);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < items.size()) {
                    YtClient.Item it = items.get(pos);
                    startResolve(it.url, it.title);
                }
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < items.size()) {
                    itemMenu(items.get(pos));
                    return true;
                }
                return false;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        root.addView(row(
                btn("Fon rejimi sozlamasi", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        batteryOpt();
                    }
                }),
                btn("Xato jurnalini yuborish", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sendLog();
                    }
                })));

        root.addView(btn("YouTube havolalarini ochish sozlamasi", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLinkSetupDialog();
            }
        }), new LinearLayout.LayoutParams(MATCH, WRAP));

        setContentView(root);
    }

    private void say(String s) {
        status.setText(s);
    }

    private void hideKeyboard() {
        InputMethodManager m = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) {
            m.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- qidirish

    private void doSearch() {
        final String q = input.getText().toString().trim();
        hideKeyboard();
        if (q.isEmpty()) {
            say("Avval qidiruv so'zini yozing yoki YouTube havolasini qo'ying.");
            return;
        }
        String link = YtClient.extractYoutubeUrl(q);
        if (link != null) {
            openLink(link);
            return;
        }
        final int my = token.incrementAndGet();
        say("Qidirilmoqda: " + q);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<YtClient.Item> r = YtClient.search(q);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                showResults(r);
                            }
                        }
                    });
                } catch (final Throwable e) {
                    ErrorLog.e("Qidirishda xato: " + q, e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                say("Qidirib bo'lmadi. " + YtClient.explain(e)
                                        + " (" + e.getClass().getSimpleName() + ")");
                            }
                        }
                    });
                }
            }
        });
    }

    private void showResults(List<YtClient.Item> r) {
        items.clear();
        items.addAll(r);
        labels.clear();
        for (YtClient.Item it : r) {
            labels.add(it.title + ". Kanal: " + it.channel + ". "
                    + (it.live ? "Jonli efir" : YtClient.spoken(it.seconds)));
        }
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        if (r.isEmpty()) {
            say("Hech narsa topilmadi. Boshqa so'z bilan urinib ko'ring.");
        } else {
            say(r.size() + " ta natija topildi. Tanlash uchun ro'yxatdagi elementni ikki marta bosing. "
                    + "Yuklab olish uchun elementni ikki marta bosib turing.");
        }
    }

    // ---------------------------------------------------------------- ijroni boshlash

    private void withPlayer(Runnable r) {
        if (player != null) {
            r.run();
        } else {
            whenReady.add(r);
        }
    }

    private void startResolve(final String url, String hintTitle) {
        final int my = token.incrementAndGet();
        curUrl = url;
        attempt = 0;
        reResolved = false;
        resumeMs = 0;
        announceStart = true;
        say(hintTitle == null ? "Yuklanmoqda..." : "Yuklanmoqda: " + hintTitle);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final YtClient.Resolved r = YtClient.resolve(url);
                    ErrorLog.i("Topildi: " + r.title + " | nomzod oqimlar: " + r.cands.size());
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                beginPlayback(r);
                            }
                        }
                    });
                } catch (final Throwable e) {
                    ErrorLog.e("Videoni ochishda xato: " + url, e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                say("Videoni ochib bo'lmadi. " + YtClient.explain(e)
                                        + " (" + e.getClass().getSimpleName() + ")");
                            }
                        }
                    });
                }
            }
        });
    }

    private void beginPlayback(YtClient.Resolved r) {
        cur = r;
        attempt = 0;
        nowTitle.setText(r.title);
        panel.setVisibility(View.VISIBLE);
        startAttempt();
    }

    /** Urinishlar tartibi: har bir oqim uchun avval oddiy rejim, keyin zaxira rejim. */
    private void startAttempt() {
        final YtClient.Resolved r = cur;
        if (r == null || r.cands.isEmpty()) {
            return;
        }
        int idx = Math.min(attempt / 2, r.cands.size() - 1);
        YtSource.nativeLike = (attempt % 2 == 1);
        final YtClient.Cand c = r.cands.get(idx);
        ErrorLog.i("Urinish " + (attempt + 1) + ": " + c.label
                + (YtSource.nativeLike ? " [zaxira rejim]" : "") + " | " + YtClient.redact(c.url));

        MediaItem.Builder mb = new MediaItem.Builder()
                .setMediaId(curUrl == null ? c.url : curUrl)
                .setUri(c.url)
                .setMediaMetadata(new MediaMetadata.Builder()
                        .setTitle(r.title)
                        .setArtist(r.channel)
                        .build());
        if (c.hls) {
            mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        final MediaItem item = mb.build();
        final long start = resumeMs;
        withPlayer(new Runnable() {
            @Override
            public void run() {
                player.setMediaItem(item, start);
                player.prepare();
                player.play();
                updatePlayButton();
            }
        });
    }

    private void onPlayerFailure(PlaybackException e) {
        ErrorLog.e("Ijro xatosi " + e.getErrorCodeName() + " (urinish " + (attempt + 1) + ")", e);
        if (cur == null) {
            say("Ijro xatosi: " + e.getErrorCodeName() + ". Xato jurnalini yuborib bering.");
            return;
        }
        if (player != null) {
            resumeMs = Math.max(resumeMs, player.getCurrentPosition());
        }
        attempt++;
        if (attempt < cur.cands.size() * 2) {
            startAttempt();
            return;
        }
        if (!reResolved && curUrl != null) {
            reResolved = true;
            say("Havola eskirgan bo'lishi mumkin. Qayta olinmoqda...");
            reResolve();
            return;
        }
        say("Ijro etib bo'lmadi: " + e.getErrorCodeName()
                + ". Pastdagi Xato jurnalini yuborish tugmasi orqali jurnalni yuboring.");
    }

    private void reResolve() {
        final int my = token.incrementAndGet();
        final String url = curUrl;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final YtClient.Resolved r = YtClient.resolve(url);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                cur = r;
                                attempt = 0;
                                startAttempt();
                            }
                        }
                    });
                } catch (final Throwable ex) {
                    ErrorLog.e("Havolani qayta olishda xato", ex);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                say("Qayta olib bo'lmadi. " + YtClient.explain(ex));
                            }
                        }
                    });
                }
            }
        });
    }

    // ---------------------------------------------------------------- boshqaruv tugmalari

    private void syncFromPlayer() {
        if (player == null || player.getMediaItemCount() == 0) {
            return;
        }
        CharSequence t = player.getMediaMetadata().title;
        nowTitle.setText(t == null ? "" : t);
        panel.setVisibility(View.VISIBLE);
        updatePlayButton();
        btnSpeed.setText(speedLabel(player.getPlaybackParameters().speed));
        syncSeekMax();
    }

    private void updatePlayButton() {
        if (player == null) {
            return;
        }
        int s = player.getPlaybackState();
        boolean active = player.getPlayWhenReady() && s != Player.STATE_ENDED && s != Player.STATE_IDLE;
        btnPlay.setText(active ? "Pauza" : "Davom ettirish");
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        int s = player.getPlaybackState();
        if (player.getPlayWhenReady() && s != Player.STATE_ENDED && s != Player.STATE_IDLE) {
            player.pause();
            return;
        }
        if (s == Player.STATE_ENDED) {
            player.seekTo(0);
        }
        if (s == Player.STATE_IDLE) {
            if (cur != null) {
                resumeMs = player.getCurrentPosition();
                attempt = 0;
                reResolved = false;
                startAttempt();
                return;
            }
            player.prepare();
        }
        player.play();
    }

    private void cycleSpeed() {
        if (player == null) {
            return;
        }
        float now = player.getPlaybackParameters().speed;
        int i = 0;
        for (int k = 0; k < speeds.length; k++) {
            if (Math.abs(speeds[k] - now) < 0.01f) {
                i = k;
            }
        }
        float next = speeds[(i + 1) % speeds.length];
        player.setPlaybackParameters(new PlaybackParameters(next));
        btnSpeed.setText(speedLabel(next));
    }

    private String speedLabel(float f) {
        String s = Float.toString(f);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return "Tezlik " + s + " barobar";
    }

    private void sayTime() {
        if (player == null || player.getMediaItemCount() == 0) {
            say("Hozir hech narsa ijro etilmayapti.");
            return;
        }
        long pos = player.getCurrentPosition() / 1000;
        long dur = player.getDuration();
        String s = YtClient.spoken(pos);
        if (dur != C.TIME_UNSET && dur > 0) {
            s += ". Jami: " + YtClient.spoken(dur / 1000);
        }
        say(s);
    }

    private void stopAll() {
        token.incrementAndGet();
        cur = null;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        panel.setVisibility(View.GONE);
        finish();
    }

    // ---------------------------------------------------------------- tizim sozlamalari va jurnal

    private void batteryOpt() {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                say("Bu Android versiyasida batareya cheklovini o'chirish shart emas.");
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                say("Batareya cheklovi allaqachon o'chirilgan. Ilova ekran o'chiq paytda ham ishlaydi.");
                return;
            }
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable e) {
            ErrorLog.e("Batareya sozlamasini ochib bo'lmadi", e);
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable e2) {
                say("Sozlamani ochib bo'lmadi. Telefon sozlamalaridan ilovaning batareya cheklovini o'zingiz o'chiring.");
            }
        }
    }

    private void sendLog() {
        try {
            File f = ErrorLog.file();
            if (f == null || !f.exists()) {
                say("Jurnal hali bo'sh.");
                return;
            }
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.putExtra(Intent.EXTRA_SUBJECT, "YouTubeni endi tinglaymiz - xato jurnali");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Xato jurnalini yuborish"));
        } catch (Throwable e) {
            ErrorLog.e("Jurnalni yuborib bo'lmadi", e);
            say("Jurnalni yuborib bo'lmadi.");
        }
    }

    /** @return true, agar ruxsat so'rovi oynasi ko'rsatilgan bo'lsa */
    private boolean askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) {
            return false;
        }
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            SharedPreferences sp = getSharedPreferences("ilova", MODE_PRIVATE);
            if (sp.getBoolean("notif_asked", false)) {
                return false;
            }
            sp.edit().putBoolean("notif_asked", true).apply();
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            return true;
        } catch (Throwable e) {
            ErrorLog.e("Bildirishnoma ruxsatini so'rab bo'lmadi", e);
            return false;
        }
    }

    // ---------------------------------------------------------------- tashqaridan kelgan havola

    private void handleIntent(Intent in) {
        if (in == null) {
            return;
        }
        String action = in.getAction();
        String text = null;
        if (Intent.ACTION_SEND.equals(action)) {
            text = in.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            text = Links.normalize(in.getDataString());
        }
        if (text == null) {
            return;
        }
        setIntent(new Intent(Intent.ACTION_MAIN));
        String link = YtClient.extractYoutubeUrl(text);
        if (link == null) {
            say("Bu havola YouTube havolasiga o'xshamaydi.");
            return;
        }
        input.setText(link);
        openLink(link);
    }

    // ---------------------------------------------------------------- havola turiga qarab ochish

    /** YouTube havolasini turiga qarab ochadi: video ijro etiladi, pleylist va kanal ro'yxat bo'lib chiqadi. */
    private void openLink(String link) {
        Links.Info li = Links.parse(link);
        if (li.type == Links.PLAYLIST || li.type == Links.CHANNEL) {
            showCollection(li, link);
        } else if (li.type == Links.SEARCH) {
            input.setText(li.query);
            doSearch();
        } else {
            startResolve(link, null);
        }
    }

    private void showCollection(final Links.Info li, final String link) {
        final int my = token.incrementAndGet();
        say(li.type == Links.CHANNEL ? "Kanal videolari olinmoqda..." : "Pleylist olinmoqda...");
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final YtClient.Coll c = YtClient.collection(li, PREVIEW_MAX);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                showCollectionResult(li, c);
                            }
                        }
                    });
                } catch (final Throwable e) {
                    ErrorLog.e("Ro'yxatni olishda xato: " + link, e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (my == token.get()) {
                                say("Ro'yxatni olib bo'lmadi. " + YtClient.explain(e)
                                        + " (" + e.getClass().getSimpleName() + ")");
                            }
                        }
                    });
                }
            }
        });
    }

    private void showCollectionResult(Links.Info li, YtClient.Coll c) {
        showResults(c.items);
        if (c.items.isEmpty()) {
            say("Ro'yxatda video topilmadi.");
            return;
        }
        String name = li.type == Links.CHANNEL
                ? (c.owner.isEmpty() ? c.title : c.owner)
                : (c.title.isEmpty() ? c.owner : c.title);
        say((li.type == Links.CHANNEL ? "Kanal: " : "Pleylist: ") + name + ". "
                + c.items.size() + " ta video" + (c.more ? " va undan ko'proq" : "") + ". "
                + "Ijro etish uchun videoni tanlang. Hammasini saqlash uchun Yuklab olish tugmasini bosing.");
    }

    /** Ro'yxatdagi elementni uzoq bosganda: ijro etish yoki yuklab olish. */
    private void itemMenu(final YtClient.Item it) {
        new AlertDialog.Builder(this)
                .setTitle(it.title)
                .setItems(new CharSequence[] {"Ijro etish", "Yuklab olish"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            startResolve(it.url, it.title);
                        } else if (it.live) {
                            say("Jonli efirni yuklab olib bo'lmaydi.");
                        } else {
                            startDownloadFlow(it.url, null);
                        }
                    }
                })
                .setNegativeButton("Bekor qilish", null)
                .show();
    }

    // ---------------------------------------------------------------- yuklab olish

    private void onDownloadClicked() {
        hideKeyboard();
        String text = input.getText().toString().trim();
        String link = YtClient.extractYoutubeUrl(text);
        if (link != null) {
            startDownloadFlow(link, null);
            return;
        }
        if (curUrl != null && panel.getVisibility() == View.VISIBLE) {
            startDownloadFlow(curUrl, "Hozir ijro etilayotgan video");
            return;
        }
        say("Yuklab olish uchun YouTube havolasini (video, pleylist yoki kanal) yozing yoki qo'ying. "
                + "Qidiruv natijasidagi videoni yuklash uchun uni ikki marta bosib turing.");
    }

    /** @param label null bo'lmasa, havola shu nom bilan atalgan bitta video sifatida olinadi */
    private void startDownloadFlow(String link, String label) {
        Links.Info li = Links.parse(link);
        if (li.type == Links.VIDEO) {
            if (li.listId != null && label == null) {
                askScope(li);
            } else {
                askFormat(Links.watchUrl(li.videoId), Links.VIDEO, label != null ? label : "Video");
            }
        } else if (li.type == Links.PLAYLIST) {
            askFormat(Links.playlistUrl(li), Links.PLAYLIST, "Pleylist");
        } else if (li.type == Links.CHANNEL) {
            askFormat(Links.channelUrl(li), Links.CHANNEL, "Kanalning barcha videolari");
        } else if (li.type == Links.SEARCH) {
            say("Qidiruv sahifasini yuklab bo'lmaydi. Videoni qidirib, natijadan uni ikki marta bosib turing.");
        } else {
            say("Bu havolada yuklab olinadigan video, pleylist yoki kanal topilmadi.");
        }
    }

    private void askScope(final Links.Info li) {
        new AlertDialog.Builder(this)
                .setTitle("Bu havolada video ham, pleylist ham bor")
                .setItems(new CharSequence[] {"Faqat shu videoni yuklab olish", "Butun pleylistni yuklab olish"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    askFormat(Links.watchUrl(li.videoId), Links.VIDEO, "Video");
                                } else {
                                    askFormat(Links.playlistUrl(li), Links.PLAYLIST, "Pleylist");
                                }
                            }
                        })
                .setNegativeButton("Bekor qilish", null)
                .show();
    }

    private void askFormat(final String url, final int type, final String what) {
        SharedPreferences sp = getSharedPreferences("ilova", MODE_PRIVATE);
        final int lastF = sp.getInt("dl_format", -1);
        final int lastC = sp.getInt("dl_cap", -1);
        final boolean hasLast = lastF >= 0 && lastF < DlOpts.FORMATS.length && lastC >= 0;
        List<String> labels = new ArrayList<>();
        if (hasLast) {
            labels.add("Oxirgi tanlov: " + DlOpts.describe(lastF, lastC));
        }
        for (String s : DlOpts.FORMATS) {
            labels.add(s);
        }
        final int shift = hasLast ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(what + ". Formatni tanlang")
                .setItems(labels.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (hasLast && which == 0) {
                            beginDownload(url, type, lastF, lastC);
                        } else {
                            askQuality(url, type, which - shift);
                        }
                    }
                })
                .setNegativeButton("Bekor qilish", null)
                .show();
    }

    private void askQuality(final String url, final int type, final int format) {
        final int[] caps = DlOpts.caps(format);
        new AlertDialog.Builder(this)
                .setTitle(DlOpts.formatName(format) + ". Sifatni tanlang")
                .setItems(DlOpts.qualities(format), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which >= 0 && which < caps.length) {
                            beginDownload(url, type, format, caps[which]);
                        }
                    }
                })
                .setNegativeButton("Bekor qilish", null)
                .show();
    }

    private void beginDownload(String url, int type, int format, int cap) {
        getSharedPreferences("ilova", MODE_PRIVATE).edit()
                .putInt("dl_format", format).putInt("dl_cap", cap).apply();
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // Android 9 va undan eski versiyalarda Download papkasiga yozish uchun ruxsat kerak
            pendingUrl = url;
            pendingType = type;
            pendingFormat = format;
            pendingCap = cap;
            say("Yuklangan fayllarni saqlash uchun ruxsat so'ralmoqda.");
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        launchDownload(url, type, format, cap);
    }

    private void launchDownload(String url, int type, int format, int cap) {
        try {
            DownloadService.start(this, url, type, format, cap);
            dlPanel.setVisibility(View.VISIBLE);
            say("Yuklab olish boshlandi: " + DlOpts.describe(format, cap)
                    + ". Fayllar telefon xotirasidagi Download papkasida, YouTubeniEndiTinglaymiz bo'limiga saqlanadi. "
                    + "Holatni bilish uchun Yuklash holati tugmasini bosing.");
        } catch (Throwable e) {
            ErrorLog.e("Yuklashni boshlab bo'lmadi", e);
            say("Yuklashni boshlab bo'lmadi. Xato jurnalini yuborib bering.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_STORAGE) {
            return;
        }
        String url = pendingUrl;
        pendingUrl = null;
        if (url == null) {
            return;
        }
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            launchDownload(url, pendingType, pendingFormat, pendingCap);
        } else {
            say("Fayllarni saqlash uchun ruxsat berilmadi, shuning uchun yuklab olinmadi. "
                    + "Ruxsatni telefon sozlamalaridan bering.");
        }
    }

    private void buildDownloadPanel(LinearLayout root) {
        dlPanel = new LinearLayout(this);
        dlPanel.setOrientation(LinearLayout.VERTICAL);
        dlPanel.setVisibility(View.GONE);
        dlPanel.addView(row(
                btn("Yuklash holati", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        say(DownloadService.describe());
                    }
                }),
                btn("Yuklashni to'xtatish", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DownloadService.cancelAll();
                        say("Yuklash to'xtatilmoqda...");
                    }
                })));
        root.addView(dlPanel, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    // ---------------------------------------------------------------- YouTube havolalarini shu ilovada ochish

    /** Birinchi ishga tushishda bir marta: havolani bosganda ilova taklif qilinishi uchun sozlamani tushuntiradi. */
    private void maybeOfferLinkSetup(Intent launch) {
        SharedPreferences sp = getSharedPreferences("ilova", MODE_PRIVATE);
        if (sp.getBoolean("links_hint", false)) {
            return;
        }
        String a = launch == null ? null : launch.getAction();
        if (Intent.ACTION_VIEW.equals(a) || Intent.ACTION_SEND.equals(a)) {
            return;
        }
        sp.edit().putBoolean("links_hint", true).apply();
        showLinkSetupDialog();
    }

    private static final String YOUTUBE_PACKAGE = "com.google.android.youtube";

    private void showLinkSetupDialog() {
        String msg;
        if (Build.VERSION.SDK_INT >= 31) {
            msg = "Android 12 va undan yangi telefonlar YouTube havolasini darhol YouTube ilovasida ochadi. "
                    + "Havola bu ilovada ochilishi uchun ikkita sozlama kerak. "
                    + "Birinchisi: \"Ilova sozlamasi\" tugmasini bosing, ochilgan sahifada "
                    + "\"Qo'llab-quvvatlanadigan havolalarni ochish\" bandini yoqing va youtube.com hamda "
                    + "youtu.be havolalarini qo'shing. "
                    + "Ikkinchisi: \"YouTube sozlamasi\" tugmasini bosing va u yerda "
                    + "\"Qo'llab-quvvatlanadigan havolalarni ochish\" bandini o'chiring, aks holda tizim havolani "
                    + "doim YouTube'ga beradi. Sahifa telefon turiga qarab biroz farq qilishi mumkin. "
                    + "Bu oynani keyin ham pastdagi \"YouTube havolalarini ochish sozlamasi\" tugmasi bilan "
                    + "ochishingiz mumkin.";
        } else {
            msg = "YouTube havolasini bosganingizda telefon \"Qaysi ilova bilan ochilsin\" oynasini ko'rsatadi. "
                    + "Undan \"YouTubeni endi tinglaymiz\" ni tanlang. Agar YouTube ilovasi darhol ochilib "
                    + "ketsa, \"YouTube sozlamasi\" tugmasini bosing va \"Standart bo'yicha ochish\" bo'limida "
                    + "standartlarni tozalang. Bu oynani keyin ham pastdagi \"YouTube havolalarini ochish "
                    + "sozlamasi\" tugmasi bilan ochishingiz mumkin.";
        }
        new AlertDialog.Builder(this)
                .setTitle("YouTube havolalarini shu ilovada ochish")
                .setMessage(msg)
                .setPositiveButton("Ilova sozlamasi", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openLinkSettings(getPackageName());
                    }
                })
                .setNeutralButton("YouTube sozlamasi", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        openLinkSettings(YOUTUBE_PACKAGE);
                    }
                })
                .setNegativeButton("Keyinroq", null)
                .show();
    }

    /** Ilovaning "Standart bo'yicha ochish" sahifasini (Android 12+) yoki ilova ma'lumotlari sahifasini ochadi. */
    private void openLinkSettings(String packageName) {
        Uri pkg = Uri.parse("package:" + packageName);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(new Intent("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS", pkg));
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
            }
        } catch (Throwable e) {
            ErrorLog.e("Havolalarni ochish sozlamasini ochib bo'lmadi: " + packageName, e);
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
            } catch (Throwable e2) {
                say("Sozlamani ochib bo'lmadi. Telefon sozlamalaridan ilovaning \"Standart bo'yicha ochish\" "
                        + "bo'limini o'zingiz oching.");
            }
        }
    }

    // ---------------------------------------------------------------- vaqt slayderlari

    /**
     * Ikkita slayder: "Video vaqti" va "Vaqt birligi". Ekran o'quvchida slayder tanlanganda barmoqni
     * tepaga yoki pastga surib qiymati o'zgartiriladi. Video vaqti tanlangan birlikka siljiydi.
     */
    private void buildSeekUi() {
        TextView posLabel = new TextView(this);
        posLabel.setText("Video vaqti");
        posLabel.setTextSize(14);
        posLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(posLabel, new LinearLayout.LayoutParams(MATCH, WRAP));

        seekPos = new SeekBar(this);
        seekPos.setMax(0);
        seekPos.setContentDescription("Video vaqti");
        seekPos.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && !seekTracking) {
                    seekToMs(progress * 1000L);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seekTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seekTracking = false;
                seekToMs(sb.getProgress() * 1000L);
            }
        });
        // Ekran o'quvchi slayderni har doim haqiqiy vaqt bilan o'qiydi va yuqoriga/pastga surishni
        // tanlangan vaqt birligi bilan bajaradi (standart slayder jami vaqtning 5 foizini siljitadi).
        seekPos.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                long dur = knownDuration();
                long pos = currentPosition();
                String spoken = positionText(pos, dur);
                if (Build.VERSION.SDK_INT >= 30) {
                    info.setStateDescription(spoken);
                } else {
                    info.setContentDescription("Video vaqti: " + spoken);
                }
                info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                        0f, Math.max(1f, dur / 1000f), Math.max(0f, pos / 1000f)));
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    adjustPosition(1);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    adjustPosition(-1);
                    return true;
                }
                // Ba'zi ekran o'quvchilar slayderni "qiymatni o'rnatish" amali bilan o'zgartiradi:
                // yo'nalishini olib, tanlangan vaqt birligiga siljitamiz.
                if (Build.VERSION.SDK_INT >= 24 && args != null
                        && action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
                    float target = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -1f);
                    if (target >= 0f) {
                        long now = currentPosition() / 1000L;
                        if (target > now) {
                            adjustPosition(1);
                        } else if (target < now) {
                            adjustPosition(-1);
                        }
                        return true;
                    }
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        panel.addView(seekPos, new LinearLayout.LayoutParams(MATCH, WRAP));

        stepLabel = new TextView(this);
        stepLabel.setTextSize(14);
        stepLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(stepLabel, new LinearLayout.LayoutParams(MATCH, WRAP));

        seekStep = new SeekBar(this);
        seekStep.setMax(STEPS_MS.length - 1);
        seekStep.setProgress(stepIdx);
        seekStep.setContentDescription("Vaqt birligi");
        seekStep.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    applyStep(progress, true);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        panel.addView(seekStep, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    /** Vaqt birligini o'zgartiradi: tugmalar matni, slayder qiymati va saqlangan sozlama yangilanadi. */
    private void applyStep(int idx, boolean announce) {
        int i = Math.max(0, Math.min(STEPS_MS.length - 1, idx));
        stepIdx = i;
        getSharedPreferences("ilova", MODE_PRIVATE).edit().putInt("step_idx", i).apply();
        String name = STEP_NAMES[i];
        btnBack.setText(name + " orqaga");
        btnFwd.setText(name + " oldinga");
        stepLabel.setText("Vaqt birligi: " + name);
        if (seekStep.getProgress() != i) {
            seekStep.setProgress(i);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            seekStep.setStateDescription(name);
        } else {
            seekStep.setContentDescription("Vaqt birligi: " + name);
            if (announce) {
                seekStep.announceForAccessibility(name);
            }
        }
    }

    /** Orqaga/oldinga tugmalari: tanlangan vaqt birligi bilan siljiydi. */
    private void stepSeek(int dir) {
        if (player == null || player.getMediaItemCount() == 0) {
            return;
        }
        seekToMs(currentPosition() + dir * STEPS_MS[stepIdx]);
        if (STEPS_MS[stepIdx] >= 30000L) {
            say(YtClient.spoken(pendingMs / 1000L));
        }
        if (!screenReaderOn()) {
            updateSeekVisual(true);
        }
    }

    /** Slayderni tepaga (dir = 1) yoki pastga (dir = -1) surish: tanlangan vaqt birligi bilan siljiydi. */
    private void adjustPosition(int dir) {
        if (player == null || player.getMediaItemCount() == 0) {
            say("Hozir hech narsa ijro etilmayapti.");
            return;
        }
        seekToMs(currentPosition() + dir * STEPS_MS[stepIdx]);
        if (screenReaderOn()) {
            seekPos.announceForAccessibility(YtClient.spoken(pendingMs / 1000L));
        } else {
            updateSeekVisual(true);
        }
    }

    private void seekToMs(long ms) {
        if (player == null || player.getMediaItemCount() == 0) {
            return;
        }
        long dur = knownDuration();
        long target = Math.max(0L, ms);
        if (dur > 0 && target > dur) {
            target = dur;
        }
        pendingMs = target;
        lastAdjustAt = System.currentTimeMillis();
        player.seekTo(target);
    }

    /** Joriy vaqt. Siljitishdan keyingi bir necha soniyada pleyer hali yangilanmagan bo'lishi mumkin. */
    private long currentPosition() {
        if (pendingMs >= 0 && System.currentTimeMillis() - lastAdjustAt < 3000L) {
            return pendingMs;
        }
        return player == null ? 0L : Math.max(0L, player.getCurrentPosition());
    }

    /** Jami davomiylik (ms); jonli efir yoki noma'lum bo'lsa 0. */
    private long knownDuration() {
        if (player == null) {
            return 0L;
        }
        long d = player.getDuration();
        return d == C.TIME_UNSET || d < 0 ? 0L : d;
    }

    private String positionText(long posMs, long durMs) {
        String s = YtClient.spoken(posMs / 1000L);
        if (durMs > 0) {
            s += ". Jami: " + YtClient.spoken(durMs / 1000L);
        }
        return s;
    }

    private void syncSeekMax() {
        if (seekPos == null || player == null) {
            return;
        }
        int max = (int) Math.min(Integer.MAX_VALUE / 2L, knownDuration() / 1000L);
        if (seekPos.getMax() != max) {
            seekPos.setMax(max);
        }
    }

    /** Slayderning ko'rinishini yangilaydi (ekran o'quvchi yoqilmagan bo'lsa har soniyada). */
    private void updateSeekVisual(boolean force) {
        if (player == null || seekPos == null || panel.getVisibility() != View.VISIBLE) {
            return;
        }
        if (!force && (seekTracking || System.currentTimeMillis() - lastAdjustAt < 1500L)) {
            return;
        }
        int max = (int) Math.min(Integer.MAX_VALUE / 2L, knownDuration() / 1000L);
        if (seekPos.getMax() != max) {
            seekPos.setMax(max);
        }
        int p = (int) Math.max(0L, Math.min((long) max, currentPosition() / 1000L));
        if (seekPos.getProgress() != p) {
            seekPos.setProgress(p);
        }
    }

    /**
     * Ekran o'quvchi yoqilganda slayder har soniyada o'zgarib turmaydi: aks holda ekran o'quvchi
     * qiymatni tinimsiz o'qib, xalaqit beradi.
     */
    private boolean screenReaderOn() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
        } catch (Throwable e) {
            return false;
        }
    }
}
