package uz.tinglaymiz.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Xato jurnali. Fayl ilovaning ichki papkasida saqlanadi,
 * "Xato jurnalini yuborish" tugmasi uni ulashish orqali yuboradi.
 */
final class ErrorLog {
    private static final String TAG = "YouTubeTinglash";
    private static final long MAX_BYTES = 400_000L;
    private static final Object LOCK = new Object();
    private static File file;

    private ErrorLog() {
    }

    static void install(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(), "xato-jurnali.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            write("QULASH", "Ilova kutilmaganda to'xtadi (oqim: " + thread.getName() + ")", throwable);
            if (prev != null) {
                prev.uncaughtException(thread, throwable);
            }
        });
        write("BOSHLANDI", "Ilova " + BuildConfig.VERSION_NAME
                + ", kutubxona " + BuildConfig.NP_VERSION
                + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL, null);
    }

    static File file() {
        return file;
    }

    static void i(String msg) {
        write("MALUMOT", msg, null);
    }

    static void e(String msg, Throwable t) {
        write("XATO", msg, t);
    }

    private static void write(String level, String msg, Throwable t) {
        String trace = t == null ? "" : Log.getStackTraceString(t);
        Log.println(t == null ? Log.INFO : Log.ERROR, TAG, level + ": " + msg + (t == null ? "" : "\n" + trace));
        File f = file;
        if (f == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                if (f.length() > MAX_BYTES) {
                    f.delete();
                }
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
                    w.write(time + " [" + level + "] " + msg + "\n");
                    if (t != null) {
                        w.write(trace + "\n");
                    }
                }
            } catch (Throwable ignored) {
                // Jurnalga yozib bo'lmasa ham ilova ishlashda davom etadi
            }
        }
    }
}
