package uz.tinglaymiz.app;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.ResolvingDataSource;

import org.schabi.newpipe.extractor.localization.Localization;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * googlevideo.com so'rovlarini YouTube kutilgan ko'rinishga keltiradi.
 *
 * Oddiy rejim: GET + Range sarlavhasi, mijozga mos User-Agent.
 * "nativeLike" rejimi (zaxira): NewPipe kabi POST, range va rn parametrlari bilan.
 * Oddiy rejim xato bersa, ilova avtomatik zaxira rejimga o'tadi.
 */
final class YtSource implements ResolvingDataSource.Resolver {

    /** Pleyer uchun umumiy rejim bayrog'i. */
    static volatile boolean nativeLike = false;

    private static final AtomicInteger RN = new AtomicInteger(0);
    private static Method visionCheck;
    private static Method visionUa;

    static {
        try {
            Class<?> h = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper");
            try {
                visionCheck = h.getMethod("isVisionOsStreamingUrl", String.class);
            } catch (Throwable ignored) {
                // kutubxona versiyasida bu usul bo'lmasligi mumkin
            }
            try {
                visionUa = h.getMethod("getVisionOsUserAgent", Localization.class);
            } catch (Throwable ignored) {
                // kutubxona versiyasida bu usul bo'lmasligi mumkin
            }
        } catch (Throwable ignored) {
            // kutubxona sinfi topilmasa, oddiy User-Agent ishlatiladi
        }
    }

    /** -1: umumiy bayroq (pleyer), 0: oddiy rejim, 1: zaxira rejim (yuklab olish uchun). */
    private final int mode;

    YtSource() {
        this(-1);
    }

    YtSource(int mode) {
        this.mode = mode;
    }

    private boolean useNativeLike() {
        return mode < 0 ? nativeLike : mode == 1;
    }

    @Override
    public DataSpec resolveDataSpec(DataSpec spec) throws IOException {
        Uri uri = spec.uri;
        String host = uri.getHost();
        if (host == null || !(host.equals("googlevideo.com") || host.endsWith(".googlevideo.com"))) {
            return spec;
        }
        String url = uri.toString();

        Map<String, String> headers = new HashMap<>(spec.httpRequestHeaders);
        headers.put("User-Agent", userAgentFor(url));
        if (url.contains("c=WEB") || url.contains("c=MWEB")) {
            headers.put("Origin", "https://www.youtube.com");
            headers.put("Referer", "https://www.youtube.com/");
            headers.put("Sec-Fetch-Dest", "empty");
            headers.put("Sec-Fetch-Mode", "cors");
            headers.put("Sec-Fetch-Site", "cross-site");
        }

        DataSpec.Builder b = spec.buildUpon().setHttpRequestHeaders(headers);

        String path = uri.getPath();
        long clen = parseLong(uri.getQueryParameter("clen"));
        if (useNativeLike() && path != null && path.contains("videoplayback") && clen > 0) {
            long start = spec.position;
            long end = spec.length != C.LENGTH_UNSET
                    ? Math.min(start + spec.length - 1, clen - 1)
                    : clen - 1;
            String sep = url.contains("?") ? "&" : "?";
            b.setUri(Uri.parse(url + sep + "range=" + start + "-" + end + "&rn=" + RN.incrementAndGet()))
                    .setPosition(0)
                    .setLength(C.LENGTH_UNSET)
                    .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                    .setHttpBody(new byte[] {'x', 0});
        }
        return b.build();
    }

    private static String userAgentFor(String url) {
        try {
            boolean vision = visionCheck != null
                    ? (Boolean) visionCheck.invoke(null, url)
                    : url.contains("c=VISIONOS");
            if (vision && visionUa != null) {
                Object ua = visionUa.invoke(null, new Object[] {null});
                if (ua != null) {
                    return ua.toString();
                }
            }
        } catch (Throwable ignored) {
            // oddiy User-Agent ishlatiladi
        }
        return YtClient.Dl.UA;
    }

    private static long parseLong(String s) {
        if (s == null) {
            return -1;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
