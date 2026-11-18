package uz.tinglaymiz.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Yuklab olish xizmati. Video, pleylist yoki kanaldagi videolarni ketma-ket yuklaydi.
 * "dataSync" turidagi oldingi reja xizmati sifatida ishlaydi, shuning uchun ekran o'chiq
 * paytda ham to'xtamaydi. Fayllar Download/YouTubeniEndiTinglaymiz papkasiga saqlanadi.
 */
public class DownloadService extends Service {

    /** Natijalar asosiy oqimda yetkaziladi. */
    interface Listener {
        void onEvent(String text, boolean active);
    }

    static final String ACTION_START = "uz.tinglaymiz.app.YUKLASH";
    static final String ACTION_CANCEL = "uz.tinglaymiz.app.YUKLASHNI_TOXTATISH";
    private static final String EX_URL = "url";
    private static final String EX_TYPE = "type";
    private static final String EX_FORMAT = "format";
    private static final String EX_CAP = "cap";

    private static final String CH_PROGRESS = "yuklash";
    private static final String CH_DONE = "yuklash_natija";
    private static final int FG_ID = 4101;
    private static final int DONE_ID = 4102;

    /** Bir marta so'rovda olinadigan videolarning eng ko'p soni. */
    static final int MAX_ITEMS = 2000;

    static volatile Listener listener;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Job> QUEUE = new ArrayDeque<>();
    /** LOCK bilan himoyalangan. */
    private static boolean working;
    private static volatile boolean cancel;

    private static volatile String lastText = "";
    private static volatile String curTitle = "";
    private static volatile int curIndex;
    private static volatile int curTotal;
    private static volatile int curPercent = -1;

    private static final Transfer.Flag FLAG = new Transfer.Flag() {
        @Override
        public boolean cancelled() {
            return cancel;
        }
    };

    private static final class Job {
        final String url;
        /** Links.VIDEO, Links.PLAYLIST yoki Links.CHANNEL. */
        final int type;
        final int format;
        final int cap;

        Job(String url, int type, int format, int cap) {
            this.url = url;
            this.type = type;
            this.format = format;
            this.cap = cap;
        }
    }

    private NotificationManager nm;
    private int lastStartId;
    private long lastNotify;

    // ---------------------------------------------------------------- tashqaridan boshqarish

    static void start(Context c, String url, int type, int format, int cap) {
        Intent i = new Intent(c, DownloadService.class);
        i.setAction(ACTION_START);
        i.putExtra(EX_URL, url);
        i.putExtra(EX_TYPE, type);
        i.putExtra(EX_FORMAT, format);
        i.putExtra(EX_CAP, cap);
        ContextCompat.startForegroundService(c, i);
    }

    /** Joriy yuklashni to'xtatadi va navbatdagilarni o'chiradi. */
    static void cancelAll() {
        synchronized (LOCK) {
            QUEUE.clear();
        }
        cancel = true;
    }

    static boolean isActive() {
        synchronized (LOCK) {
            return working;
        }
    }

    /** "Yuklash holati" tugmasi uchun matn. */
    static String describe() {
        if (!isActive()) {
            return lastText.isEmpty() ? "Hozir yuklab olish ishlamayapti." : lastText;
        }
        StringBuilder b = new StringBuilder("Yuklanmoqda");
        if (curTotal > 1) {
            b.append(' ').append(curIndex).append(" / ").append(curTotal);
        }
        b.append(": ").append(curTitle.isEmpty() ? "tayyorlanmoqda" : curTitle);
        if (curPercent >= 0) {
            b.append(". ").append(curPercent).append(" foiz");
        }
        return b.toString();
    }

    private static void emit(final String text) {
        if (text != null) {
            lastText = text;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Listener l = listener;
                if (l != null) {
                    l.onEvent(text, isActive());
                }
            }
        });
    }

    // ---------------------------------------------------------------- xizmat hayot sikli

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        String action = intent == null ? null : intent.getAction();

        if (ACTION_CANCEL.equals(action)) {
            cancelAll();
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopIfIdle();
            return START_NOT_STICKY;
        }

        // startForegroundService() chaqirilgan, shuning uchun darhol oldingi rejimga o'tish shart
        enterForeground("Tayyorlanmoqda...");

        String url = intent.getStringExtra(EX_URL);
        int type = intent.getIntExtra(EX_TYPE, Links.VIDEO);
        int format = intent.getIntExtra(EX_FORMAT, Choose.M4A);
        int cap = intent.getIntExtra(EX_CAP, 0);
        if (url == null || url.isEmpty()) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        synchronized (LOCK) {
            QUEUE.add(new Job(url, type, format, cap));
            if (!working) {
                working = true;
                startWorker();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // Xizmat tizim tomonidan to'xtatilsa, yuklash ham to'xtaydi
        if (isActive()) {
            cancelAll();
        }
        super.onDestroy();
    }

    private void stopIfIdle() {
        synchronized (LOCK) {
            if (working) {
                return;
            }
        }
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // oldingi rejimda bo'lmasa ham xizmat to'xtatiladi
        }
        stopSelf(lastStartId);
    }

    private void startWorker() {
        final Context app = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                workLoop(app);
            }
        }, "yuklash");
        t.start();
    }

    private void workLoop(Context app) {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tinglaymiz:yuklash");
                wl.setReferenceCounted(false);
                wl.acquire(6L * 60 * 60 * 1000);
            }
        } catch (Throwable e) {
            ErrorLog.e("Uyg'oq turish qulfini olib bo'lmadi", e);
        }

        try {
            while (true) {
                Job j;
                synchronized (LOCK) {
                    j = QUEUE.poll();
                    if (j == null) {
                        working = false;
                        break;
                    }
                }
                try {
                    runJob(app, j);
                } catch (Throwable e) {
                    ErrorLog.e("Yuklashda kutilmagan xato", e);
                    emit("Yuklab olishda kutilmagan xato chiqdi: " + e.getClass().getSimpleName());
                }
            }
        } catch (Throwable e) {
            ErrorLog.e("Yuklash ishchisi to'xtadi", e);
            synchronized (LOCK) {
                working = false;
                QUEUE.clear();
            }
        } finally {
            if (wl != null) {
                try {
                    if (wl.isHeld()) {
                        wl.release();
                    }
                } catch (Throwable ignored) {
                    // qulf allaqachon bo'shagan
                }
            }
        }

        emit(null);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                stopIfIdle();
            }
        });
    }

    // ---------------------------------------------------------------- bitta topshiriq

    private void runJob(Context app, Job j) {
        cancel = false;
        curTitle = "";
        curIndex = 0;
        curTotal = 0;
        curPercent = -1;
        ErrorLog.i("Yuklab olish: " + j.url + " | " + DlOpts.describe(j.format, j.cap));

        Links.Info li = Links.parse(j.url);
        int ok = 0;
        int bad = 0;
        boolean stopped = false;
        String where = "";
        List<String> failed = new ArrayList<>();

        try {
            File tmp = tmpDir(app);
            cleanTmp(tmp);

            List<YtClient.Item> items = new ArrayList<>();
            String folder = "";
            if (j.type == Links.VIDEO) {
                YtClient.Item one = new YtClient.Item();
                one.url = j.url;
                items.add(one);
            } else if (j.type == Links.PLAYLIST || j.type == Links.CHANNEL) {
                step("Ro'yxat olinmoqda...");
                YtClient.Coll c = collectWithRetry(li);
                items.addAll(c.items);
                String name;
                if (j.type == Links.CHANNEL) {
                    name = c.owner.isEmpty() ? c.title : c.owner;
                } else {
                    name = c.title.isEmpty() ? c.owner : c.title;
                }
                folder = Names.safe(name, 80);
                if (c.more) {
                    emit("Ro'yxat juda katta: dastlabki " + MAX_ITEMS + " ta video yuklanadi.");
                }
            } else {
                throw new YtClient.Msg("Bu havoladan yuklab olib bo'lmaydi.");
            }

            if (items.isEmpty()) {
                emit("Ro'yxatda yuklab olinadigan video topilmadi.");
                notifyDone("Yuklab olish", "Ro'yxatda yuklab olinadigan video topilmadi.");
                return;
            }

            int total = items.size();
            curTotal = total;
            if (total > 1) {
                emit(total + " ta video yuklab olinadi: " + DlOpts.describe(j.format, j.cap) + ".");
            }

            for (int i = 0; i < total; i++) {
                if (cancel) {
                    stopped = true;
                    break;
                }
                YtClient.Item it = items.get(i);
                curIndex = i + 1;
                curTitle = it.title.isEmpty() ? "video" : it.title;
                curPercent = -1;
                step((total > 1 ? "Yuklanmoqda " + (i + 1) + " / " + total + ": " : "Yuklanmoqda: ") + curTitle);
                try {
                    where = downloadOne(app, tmp, j, it, i + 1, total, folder);
                    ok++;
                } catch (Transfer.Cancelled e) {
                    stopped = true;
                    break;
                } catch (Throwable e) {
                    bad++;
                    failed.add(curTitle);
                    ErrorLog.e("Yuklab bo'lmadi: " + it.url, e);
                    emit("Yuklab bo'lmadi: " + curTitle + ". " + YtClient.explain(e));
                }
            }
        } catch (Transfer.Cancelled e) {
            stopped = true;
        } catch (Throwable e) {
            ErrorLog.e("Yuklash ishida xato: " + j.url, e);
            String why = YtClient.explain(e);
            emit("Yuklab olib bo'lmadi. " + why);
            notifyDone("Yuklab olib bo'lmadi", why);
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (stopped) {
            sb.append("Yuklash to'xtatildi. ");
        }
        sb.append(ok).append(" ta fayl yuklandi");
        if (bad > 0) {
            sb.append(", ").append(bad).append(" tasi yuklanmadi");
        }
        sb.append('.');
        if (ok > 0 && !where.isEmpty()) {
            sb.append(" Fayllar telefon xotirasidagi ").append(where).append(" papkasida.");
        }
        if (!failed.isEmpty()) {
            sb.append(" Yuklanmaganlar: ");
            for (int i = 0; i < failed.size() && i < 3; i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(failed.get(i));
            }
            if (failed.size() > 3) {
                sb.append(" va yana ").append(failed.size() - 3).append(" ta");
            }
            sb.append('.');
        }
        String summary = sb.toString();
        ErrorLog.i("Yuklash tugadi: " + summary);
        emit(summary);
        notifyDone(stopped ? "Yuklash to'xtatildi" : "Yuklab olish tugadi", summary);
    }

    /** Bitta videoni yuklab, Download papkasiga saqlaydi. @return saqlangan papka (ko'rsatish uchun) */
    private String downloadOne(Context app, File tmp, Job j, YtClient.Item it, int idx, int total,
                               String folder) throws Exception {
        YtClient.Plan plan = planWithRetry(it.url, j.format, j.cap);
        String title = plan.title.isEmpty() ? it.title : plan.title;
        if (title.isEmpty()) {
            title = "video";
        }
        curTitle = title;
        Choose.Pick pick = plan.pick;
        ErrorLog.i("Tanlandi: " + title + " | " + pick.label + (pick.merge ? " (birlashtiriladi)" : "")
                + (pick.note.isEmpty() ? "" : " | " + pick.note));
        if (!pick.note.isEmpty()) {
            emit(title + ". " + pick.note + ".");
        }

        long stamp = System.nanoTime();
        File fMain = new File(tmp, stamp + "-asosiy." + pick.ext);
        File fAudio = new File(tmp, stamp + "-ovoz.m4a");
        File fMerged = new File(tmp, stamp + "-birlashgan.mp4");
        try {
            File ready = transfer(it.url, plan, j, fMain, fAudio, fMerged);
            String name = (total > 1 ? Names.index(idx, total) + ". " : "")
                    + Names.safe(title, 150) + "." + pick.ext;
            String where = Saver.save(app, ready, folder, name, pick.mime);
            ErrorLog.i("Saqlandi: " + name + " -> " + where);
            return where;
        } finally {
            fMain.delete();
            fAudio.delete();
            fMerged.delete();
        }
    }

    /** Oqim(lar)ni yuklab, tayyor faylni qaytaradi. Havola eskirgan bo'lsa bir marta yangidan oladi. */
    private File transfer(String url, YtClient.Plan first, Job j, File fMain, File fAudio, File fMerged)
            throws Exception {
        YtClient.Plan plan = first;
        for (int round = 0; ; round++) {
            try {
                return transferOnce(plan.pick, fMain, fAudio, fMerged);
            } catch (Transfer.Cancelled e) {
                throw e;
            } catch (Transfer.Disk e) {
                throw e;
            } catch (YtClient.Msg e) {
                throw e;
            } catch (IOException e) {
                if (round >= 1) {
                    throw e;
                }
                ErrorLog.e("Oqimni yuklashda xato, havola yangidan olinadi", e);
                fMain.delete();
                fAudio.delete();
                fMerged.delete();
                plan = planWithRetry(url, j.format, j.cap);
            }
        }
    }

    private File transferOnce(Choose.Pick p, File fMain, File fAudio, File fMerged) throws IOException {
        long sizeMain = Math.max(0L, p.main.size);
        long sizeAudio = p.merge && p.extraAudio != null ? Math.max(0L, p.extraAudio.size) : 0L;
        long grand = sizeMain + sizeAudio;
        ensureSpace(fMain.getParentFile(), (long) (grand * 2.3));

        if (p.merge && p.extraAudio != null) {
            fetchStream(p.main.url, fMain, 0L, grand);
            fetchStream(p.extraAudio.url, fAudio, sizeMain, grand);
            step("Tasvir va ovoz birlashtirilmoqda: " + curTitle);
            try {
                Mux.merge(fMain, fAudio, fMerged, FLAG);
                fMain.delete();
                fAudio.delete();
                return fMerged;
            } catch (Transfer.Cancelled e) {
                throw e;
            } catch (IOException e) {
                if (p.fallback == null) {
                    throw e;
                }
                ErrorLog.e("Birlashtirib bo'lmadi, tayyor oqim ishlatiladi", e);
                fMain.delete();
                fAudio.delete();
                fMerged.delete();
                emit("Tasvir va ovozni birlashtirib bo'lmadi. Tayyor oqim yuklanmoqda: " + p.fallback.label + ".");
                return transferOnce(p.fallback, fMain, fAudio, fMerged);
            }
        }
        fetchStream(p.main.url, fMain, 0L, grand);
        return fMain;
    }

    /** Avval oddiy rejim, xato bo'lsa zaxira rejim bilan yuklaydi (qolgan joyidan davom etadi). */
    private void fetchStream(String url, File dest, final long base, final long grand) throws IOException {
        IOException last = null;
        for (int mode = 0; mode < 2; mode++) {
            try {
                Transfer.fetch(url, dest, mode, new Transfer.Sink() {
                    @Override
                    public void onBytes(long done, long total) {
                        onProgress(base + done, grand);
                    }
                }, FLAG);
                return;
            } catch (Transfer.Cancelled e) {
                throw e;
            } catch (Transfer.Disk e) {
                throw e;
            } catch (IOException e) {
                last = e;
                ErrorLog.e("Oqimni yuklash xatosi (" + (mode == 0 ? "oddiy" : "zaxira") + " rejim): "
                        + YtClient.redact(url), e);
            }
        }
        throw last;
    }

    // ---------------------------------------------------------------- tarmoq so'rovlari (qayta urinish bilan)

    private YtClient.Plan planWithRetry(String url, int format, int cap) throws Exception {
        for (int i = 0; ; i++) {
            if (cancel) {
                throw new Transfer.Cancelled();
            }
            try {
                return YtClient.plan(url, format, cap);
            } catch (Exception e) {
                if (i >= 2 || !YtClient.isRateLimited(e)) {
                    throw e;
                }
                emit("YouTube so'rovlarni vaqtincha cheklab qo'ydi. Biroz kutilmoqda...");
                Transfer.pause(20000L, FLAG);
            }
        }
    }

    private YtClient.Coll collectWithRetry(Links.Info li) throws Exception {
        for (int i = 0; ; i++) {
            if (cancel) {
                throw new Transfer.Cancelled();
            }
            try {
                return YtClient.collection(li, MAX_ITEMS);
            } catch (Exception e) {
                if (i >= 2 || !YtClient.isRateLimited(e)) {
                    throw e;
                }
                emit("YouTube so'rovlarni vaqtincha cheklab qo'ydi. Biroz kutilmoqda...");
                Transfer.pause(20000L, FLAG);
            }
        }
    }

    // ---------------------------------------------------------------- xotira

    private static File tmpDir(Context c) {
        File d = new File(c.getFilesDir(), "yuklash-vaqtincha");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }

    private static void cleanTmp(File d) {
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private static void ensureSpace(File dir, long need) throws IOException {
        if (need <= 0 || dir == null) {
            return;
        }
        long free;
        try {
            free = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return; // xotira holatini bilib bo'lmadi: urinib ko'riladi
        }
        if (free < need) {
            throw new YtClient.Msg("Telefon xotirasida joy yetarli emas: kamida "
                    + (need / (1024L * 1024L)) + " MB kerak.");
        }
    }

    // ---------------------------------------------------------------- bildirishnomalar

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            NotificationChannel progress = new NotificationChannel(
                    CH_PROGRESS, "Yuklab olish jarayoni", NotificationManager.IMPORTANCE_LOW);
            NotificationChannel done = new NotificationChannel(
                    CH_DONE, "Yuklab olish natijasi", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(progress);
            nm.createNotificationChannel(done);
        } catch (Throwable e) {
            ErrorLog.e("Bildirishnoma kanalini yaratib bo'lmadi", e);
        }
    }

    private static int piFlags() {
        return Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, piFlags());
    }

    private PendingIntent cancelIntent() {
        Intent i = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        return PendingIntent.getService(this, 1, i, piFlags());
    }

    private Notification buildProgress(String title, String text, int pct) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, "To'xtatish", cancelIntent());
        if (pct >= 0) {
            b.setProgress(100, pct, false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void enterForeground(String text) {
        try {
            ServiceCompat.startForeground(this, FG_ID, buildProgress("Yuklab olish", text, -1),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Throwable e) {
            ErrorLog.e("Oldingi reja rejimini yoqib bo'lmadi", e);
        }
    }

    /** Yangi bosqich: holat matnini yangilaydi va foydalanuvchiga xabar beradi. */
    private void step(String text) {
        emit(text);
        curPercent = -1;
        lastNotify = SystemClock.elapsedRealtime();
        try {
            nm.notify(FG_ID, buildProgress("Yuklab olish", text, -1));
        } catch (Throwable ignored) {
            // bildirishnoma ko'rsatilmasa ham yuklash davom etadi
        }
    }

    private void onProgress(long done, long grand) {
        int pct = grand > 0 ? (int) Math.min(100L, done * 100L / grand) : -1;
        curPercent = pct;
        long now = SystemClock.elapsedRealtime();
        if (pct < 100 && now - lastNotify < 1000L) {
            return;
        }
        lastNotify = now;
        String head = curTotal > 1 ? curIndex + " / " + curTotal + ": " : "";
        try {
            nm.notify(FG_ID, buildProgress("Yuklab olish", head + curTitle, pct));
        } catch (Throwable ignored) {
            // bildirishnoma ko'rsatilmasa ham yuklash davom etadi
        }
    }

    private void notifyDone(String title, String text) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_DONE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openApp());
            nm.notify(DONE_ID, b.build());
        } catch (Throwable e) {
            ErrorLog.e("Natija bildirishnomasini ko'rsatib bo'lmadi", e);
        }
    }
}
