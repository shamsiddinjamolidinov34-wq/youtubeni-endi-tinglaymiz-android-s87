package uz.tinglaymiz.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Tayyor faylni telefonning umumiy Download papkasiga ko'chiradi:
 * Download/YouTubeniEndiTinglaymiz/[pleylist nomi]/fayl.
 * Android 10 va undan yuqorida MediaStore ishlatiladi (ruxsat kerak emas),
 * eski versiyalarda oddiy fayl yo'li va saqlash ruxsati.
 */
final class Saver {

    static final String ROOT = "YouTubeniEndiTinglaymiz";

    private Saver() {
    }

    /** @return fayl joylashgan papka (foydalanuvchiga ko'rsatish uchun), masalan Download/YouTubeniEndiTinglaymiz */
    static String save(Context ctx, File src, String folder, String name, String mime) throws IOException {
        String sub = folder == null || folder.isEmpty() ? ROOT : ROOT + "/" + folder;
        String shown = "Download/" + sub;

        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + sub);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new IOException("Download papkasida fayl yaratib bo'lmadi");
            }
            try {
                try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("Faylni yozish uchun ochib bo'lmadi");
                    }
                    copy(in, out);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
            } catch (IOException | RuntimeException e) {
                try {
                    cr.delete(uri, null, null);
                } catch (Throwable ignored) {
                    // yarim fayl qolib ketishi mumkin, lekin asosiy xatoni yashirmaymiz
                }
                throw e;
            }
            return shown;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Papkani yaratib bo'lmadi: " + dir.getAbsolutePath());
        }
        File dst = unique(dir, name);
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            copy(in, out);
        } catch (IOException e) {
            dst.delete();
            throw e;
        }
        try {
            MediaScannerConnection.scanFile(ctx, new String[] {dst.getAbsolutePath()}, new String[] {mime}, null);
        } catch (Throwable ignored) {
            // fayl baribir saqlangan, faqat galereyada ko'rinishi kechikishi mumkin
        }
        return shown;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) {
                return f;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }
}
