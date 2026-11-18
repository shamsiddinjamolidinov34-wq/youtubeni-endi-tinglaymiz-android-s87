package uz.tinglaymiz.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Ovozsiz tasvir (H.264) va ovoz (AAC) oqimlarini bitta MP4 faylga birlashtiradi.
 * Qayta kodlash yo'q, shuning uchun tez ishlaydi va sifat o'zgarmaydi.
 */
final class Mux {

    private Mux() {
    }

    /** Birlashtirish Android 7.0 (API 24) dan boshlab ishonchli: B-kadrli videolar shundan qo'llab-quvvatlanadi. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    static void merge(File video, File audio, File out, Transfer.Flag flag) throws IOException {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer mux = null;
        boolean started = false;
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vTrack = findTrack(ve, "video/");
            int aTrack = findTrack(ae, "audio/");
            if (vTrack < 0 || aTrack < 0) {
                throw new IOException("Birlashtirish uchun tasvir yoki ovoz yo'li topilmadi");
            }
            ve.selectTrack(vTrack);
            ae.selectTrack(aTrack);

            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vOut = mux.addTrack(ve.getTrackFormat(vTrack));
            int aOut = mux.addTrack(ae.getTrackFormat(aTrack));
            mux.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean vDone = false;
            boolean aDone = false;
            int count = 0;
            while (!vDone || !aDone) {
                count++;
                if ((count & 255) == 0 && flag.cancelled()) {
                    throw new Transfer.Cancelled();
                }
                long vt = vDone ? Long.MAX_VALUE : ve.getSampleTime();
                long at = aDone ? Long.MAX_VALUE : ae.getSampleTime();
                if (!vDone && vt < 0) {
                    vDone = true;
                    continue;
                }
                if (!aDone && at < 0) {
                    aDone = true;
                    continue;
                }
                // Tasvir va ovoz namunalari vaqt tartibida navbatma-navbat yoziladi
                boolean useVideo = vt <= at;
                MediaExtractor ex = useVideo ? ve : ae;
                if (Build.VERSION.SDK_INT >= 28) {
                    long size = ex.getSampleSize();
                    if (size > buf.capacity()) {
                        buf = ByteBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 16L, size + size / 2));
                    }
                }
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) {
                    if (useVideo) {
                        vDone = true;
                    } else {
                        aDone = true;
                    }
                    continue;
                }
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, n, ex.getSampleTime(), flags);
                mux.writeSampleData(useVideo ? vOut : aOut, buf, info);
                ex.advance();
            }
            mux.stop();
            started = false;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Birlashtirishda xato: " + e, e);
        } finally {
            if (mux != null) {
                if (started) {
                    try {
                        mux.stop();
                    } catch (Throwable ignored) {
                        // fayl baribir o'chiriladi
                    }
                }
                try {
                    mux.release();
                } catch (Throwable ignored) {
                    // e'tiborsiz
                }
            }
            try {
                ve.release();
            } catch (Throwable ignored) {
                // e'tiborsiz
            }
            try {
                ae.release();
            } catch (Throwable ignored) {
                // e'tiborsiz
            }
        }
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
