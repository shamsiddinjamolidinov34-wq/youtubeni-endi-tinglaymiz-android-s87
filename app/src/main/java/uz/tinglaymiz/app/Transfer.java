package uz.tinglaymiz.app;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Bitta oqimni faylga yuklaydi. Fayl qismlarga bo'lib olinadi (YouTube katta so'rovlarni
 * sekinlashtiradi), aloqa uzilsa qolgan joyidan davom etadi.
 */
final class Transfer {

    interface Flag {
        boolean cancelled();
    }

    interface Sink {
        /** total noma'lum bo'lsa 0 yoki manfiy. */
        void onBytes(long done, long total);
    }

    /** Foydalanuvchi yuklashni to'xtatdi. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("To'xtatildi");
        }
    }

    /** Telefon xotirasiga yozib bo'lmadi (joy yo'q va hokazo). Qayta urinishdan foyda yo'q. */
    static final class Disk extends IOException {
        Disk(IOException cause) {
            super("Faylga yozib bo'lmadi: " + cause.getMessage(), cause);
        }
    }

    private static final int CHUNK = 8 * 1024 * 1024;
    private static final int MAX_FAILS = 30;

    private Transfer() {
    }

    /**
     * @param mode 0 - oddiy rejim, 1 - zaxira rejim (NewPipe kabi POST va range parametri)
     */
    static void fetch(String url, File dest, int mode, Sink sink, Flag flag) throws IOException {
        long total = parseLong(Links.urlParam(url, "clen"));
        DataSource ds = new ResolvingDataSource.Factory(
                new DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(20000),
                new YtSource(mode)).createDataSource();

        long pos = dest.exists() ? dest.length() : 0L;
        byte[] buf = new byte[64 * 1024];
        int fails = 0;

        try (OutputStream os = new FileOutputStream(dest, true)) {
            while (total <= 0 || pos < total) {
                if (flag.cancelled()) {
                    throw new Cancelled();
                }
                long start = pos;
                long len = total > 0 ? Math.min((long) CHUNK, total - pos) : C.LENGTH_UNSET;
                try {
                    ds.open(new DataSpec.Builder()
                            .setUri(Uri.parse(url))
                            .setPosition(pos)
                            .setLength(len)
                            .build());
                    int n;
                    while ((n = ds.read(buf, 0, buf.length)) != C.RESULT_END_OF_INPUT) {
                        if (flag.cancelled()) {
                            throw new Cancelled();
                        }
                        try {
                            os.write(buf, 0, n);
                        } catch (IOException we) {
                            throw new Disk(we);
                        }
                        pos += n;
                        sink.onBytes(pos, total);
                    }
                } catch (Cancelled e) {
                    throw e;
                } catch (Disk e) {
                    throw e;
                } catch (HttpDataSource.InvalidResponseCodeException e) {
                    // 403, 404 va shunga o'xshash: chaqiruvchi rejimni almashtiradi yoki havolani yangilaydi
                    throw e;
                } catch (IOException e) {
                    if (pos > start) {
                        fails = 0;
                    }
                    fails++;
                    if (fails > MAX_FAILS) {
                        throw e;
                    }
                    pause(Math.min(fails, 5) * 2000L, flag);
                    continue;
                } finally {
                    try {
                        ds.close();
                    } catch (IOException ignored) {
                        // yopishdagi xato muhim emas
                    }
                }

                if (total <= 0) {
                    break; // uzunlik noma'lum edi: oxirigacha o'qildi
                }
                if (pos == start) {
                    // Bo'sh javob: cheksiz aylanib qolmaslik uchun
                    fails++;
                    if (fails > MAX_FAILS) {
                        throw new IOException("Server ma'lumot bermadi");
                    }
                    pause(Math.min(fails, 5) * 2000L, flag);
                } else {
                    fails = 0;
                }
            }
        }
    }

    /** To'xtatish so'ralsa darhol Cancelled tashlaydi. */
    static void pause(long ms, Flag flag) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (flag.cancelled()) {
                throw new Cancelled();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
        }
    }

    private static long parseLong(String s) {
        if (s == null) {
            return -1;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
