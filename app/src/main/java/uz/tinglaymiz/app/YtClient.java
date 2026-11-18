package uz.tinglaymiz.app;

import android.net.Uri;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** YouTube bilan ishlash: qidirish va ijro etiladigan audio oqimlarni topish. */
final class YtClient {

    static final class Item {
        String title = "";
        String channel = "";
        String url = "";
        long seconds;
        boolean live;
    }

    static final class Cand {
        String url;
        boolean hls;
        String label;
    }

    static final class Resolved {
        String title = "";
        String channel = "";
        boolean live;
        final List<Cand> cands = new ArrayList<>();
    }

    /** Pleylist yoki kanal: nomi va videolar ro'yxati. */
    static final class Coll {
        String title = "";
        String owner = "";
        final List<Item> items = new ArrayList<>();
        /** Chegaraga yetgani uchun ro'yxatning hammasi olinmadi. */
        boolean more;
    }

    /** Bitta video uchun yuklab olish rejasi: qaysi oqim(lar) yuklanadi. */
    static final class Plan {
        String title = "";
        String channel = "";
        Choose.Pick pick;
    }

    /** Foydalanuvchiga to'g'ridan-to'g'ri ko'rsatiladigan xabarli xato. */
    static final class Msg extends IOException {
        Msg(String message) {
            super(message);
        }
    }

    private static boolean ready;

    private YtClient() {
    }

    private static synchronized void init() {
        if (!ready) {
            NewPipe.init(new Dl(), Localization.DEFAULT, new ContentCountry("UZ"));
            ready = true;
        }
    }

    static List<Item> search(String query) throws Exception {
        init();
        StreamingService yt = ServiceList.YouTube;
        SearchInfo info = SearchInfo.getInfo(yt, yt.getSearchQHFactory().fromQuery(
                query, Collections.singletonList(YoutubeSearchQueryHandlerFactory.VIDEOS), ""));
        List<Item> out = new ArrayList<>();
        for (InfoItem it : info.getRelatedItems()) {
            if (!(it instanceof StreamInfoItem)) {
                continue;
            }
            Item r = toItem((StreamInfoItem) it);
            if (!r.url.isEmpty()) {
                out.add(r);
            }
        }
        return out;
    }

    static Resolved resolve(String url) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        Resolved r = new Resolved();
        r.title = nz(info.getName());
        r.channel = nz(info.getUploaderName());
        r.live = String.valueOf(info.getStreamType()).contains("LIVE");
        String hls = nz(info.getHlsUrl());

        if (r.live && !hls.isEmpty()) {
            add(r, hls, true, "jonli efir (HLS)");
        }

        List<AudioStream> audio = new ArrayList<>();
        for (AudioStream a : info.getAudioStreams()) {
            if (a.isUrl()
                    && a.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP
                    && !nz(a.getContent()).isEmpty()) {
                audio.add(a);
            }
        }
        Collections.sort(audio, new Comparator<AudioStream>() {
            @Override
            public int compare(AudioStream x, AudioStream y) {
                int c = Integer.compare(trackRank(x), trackRank(y));
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(isM4a(y) ? 1 : 0, isM4a(x) ? 1 : 0);
                if (c != 0) {
                    return c;
                }
                return Integer.compare(y.getAverageBitrate(), x.getAverageBitrate());
            }
        });
        for (AudioStream a : audio) {
            add(r, a.getContent(), false, "audio " + a.getAverageBitrate() + " kbit/s " + a.getFormat());
        }

        // Faqat audio oqim topilmasa: eng past sifatli video+audio oqim (video ijro etilmaydi)
        if (r.cands.size() < 4) {
            VideoStream best = null;
            for (VideoStream v : info.getVideoStreams()) {
                if (v.isVideoOnly() || !v.isUrl() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) {
                    continue;
                }
                if (best == null || v.getHeight() < best.getHeight()) {
                    best = v;
                }
            }
            if (best != null) {
                add(r, best.getContent(), false, "video+audio " + best.getResolution());
            }
        }
        if (!r.live && !hls.isEmpty()) {
            add(r, hls, true, "HLS");
        }
        if (r.cands.isEmpty()) {
            throw new IOException("Ijro uchun oqim topilmadi (audio oqimlar yo'q)");
        }
        return r;
    }

    private static void add(Resolved r, String url, boolean hls, String label) {
        if (url == null || url.isEmpty() || r.cands.size() >= 5) {
            return;
        }
        for (Cand c : r.cands) {
            if (c.url.equals(url)) {
                return;
            }
        }
        Cand c = new Cand();
        c.url = url;
        c.hls = hls;
        c.label = label;
        r.cands.add(c);
    }

    private static boolean isM4a(AudioStream a) {
        return String.valueOf(a.getFormat()).contains("M4A");
    }

    /** Asl (ORIGINAL) audio yo'l birinchi turadi, dublyaj va tavsif yo'llari keyin. */
    private static int trackRank(AudioStream a) {
        try {
            Object t = a.getClass().getMethod("getAudioTrackType").invoke(a);
            if (t == null) {
                return 0;
            }
            return "ORIGINAL".equals(t.toString()) ? 0 : 1;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- pleylist, kanal, yuklab olish

    private static Item toItem(StreamInfoItem s) {
        Item r = new Item();
        r.title = nz(s.getName());
        r.channel = nz(s.getUploaderName());
        r.url = nz(s.getUrl());
        r.seconds = s.getDuration();
        r.live = String.valueOf(s.getStreamType()).contains("LIVE");
        return r;
    }

    private static void addItems(Coll c, List<StreamInfoItem> list, int max) {
        if (list == null) {
            return;
        }
        for (StreamInfoItem s : list) {
            if (c.items.size() >= max) {
                return;
            }
            Item r = toItem(s);
            if (!r.url.isEmpty()) {
                c.items.add(r);
            }
        }
    }

    /**
     * Pleylist yoki kanaldagi videolar ro'yxati. Kanal uchun uning avtomatik "yuklamalar"
     * pleylisti ishlatiladi (kanal identifikatori UC... dan UU... hosil bo'ladi).
     */
    static Coll collection(Links.Info li, int max) throws Exception {
        init();
        StreamingService yt = ServiceList.YouTube;
        String listUrl;
        String channelName = "";
        if (li.type == Links.CHANNEL) {
            String id = li.channelId;
            if (id == null) {
                ChannelInfo ci = ChannelInfo.getInfo(yt, Links.channelUrl(li));
                id = ci.getId();
                channelName = nz(ci.getName());
            }
            String uploads = Links.uploadsListId(id);
            if (uploads == null) {
                throw new Msg("Kanal topilmadi yoki uning videolari ochiq emas.");
            }
            listUrl = "https://www.youtube.com/playlist?list=" + uploads;
        } else {
            listUrl = Links.playlistUrl(li);
            if (listUrl == null) {
                throw new Msg("Pleylist havolasi noto'g'ri.");
            }
        }

        PlaylistInfo pi = PlaylistInfo.getInfo(yt, listUrl);
        Coll c = new Coll();
        c.title = nz(pi.getName());
        c.owner = channelName.isEmpty() ? nz(pi.getUploaderName()) : channelName;
        addItems(c, pi.getRelatedItems(), max);
        Page next = pi.getNextPage();
        while (Page.isValid(next) && c.items.size() < max) {
            ListExtractor.InfoItemsPage<StreamInfoItem> pg = PlaylistInfo.getMoreItems(yt, listUrl, next);
            addItems(c, pg.getItems(), max);
            next = pg.getNextPage();
        }
        c.more = Page.isValid(next);
        return c;
    }

    /** Bitta video uchun format va sifatga mos oqimni tanlaydi. */
    static Plan plan(String url, int format, int cap) throws Exception {
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        String type = String.valueOf(info.getStreamType());
        if (type.equals("LIVE_STREAM") || type.equals("AUDIO_LIVE_STREAM")) {
            throw new Msg("Jonli efirni yuklab olib bo'lmaydi.");
        }
        Plan p = new Plan();
        p.title = nz(info.getName());
        p.channel = nz(info.getUploaderName());

        List<Choose.Opt> audios = new ArrayList<>();
        for (AudioStream a : info.getAudioStreams()) {
            if (!a.isUrl()
                    || a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP
                    || nz(a.getContent()).isEmpty()) {
                continue;
            }
            Choose.Opt o = new Choose.Opt();
            o.url = a.getContent();
            o.itag = parseInt(Links.urlParam(o.url, "itag"));
            o.mime = mimeOf(o.url, String.valueOf(a.getFormat()), true);
            int kb = a.getAverageBitrate();
            o.kbps = kb > 0 ? kb : Choose.audioKbpsByItag(o.itag);
            o.original = trackRank(a) == 0;
            o.size = parseLong(Links.urlParam(o.url, "clen"));
            audios.add(o);
        }

        Choose.Pick pick;
        if (format == Choose.MP4) {
            List<Choose.Opt> muxed = new ArrayList<>();
            for (VideoStream v : info.getVideoStreams()) {
                if (v.isVideoOnly()) {
                    continue;
                }
                Choose.Opt o = videoOpt(v, false);
                if (o != null) {
                    muxed.add(o);
                }
            }
            List<Choose.Opt> videoOnly = new ArrayList<>();
            for (VideoStream v : info.getVideoOnlyStreams()) {
                Choose.Opt o = videoOpt(v, true);
                if (o != null) {
                    videoOnly.add(o);
                }
            }
            pick = Choose.video(muxed, videoOnly, audios, cap, Mux.supported());
        } else {
            pick = Choose.audio(audios, format, cap);
        }
        if (pick == null) {
            throw new Msg(format == Choose.MP4
                    ? "Bu videoda MP4 oqim topilmadi."
                    : "Bu videoda audio oqim topilmadi.");
        }
        p.pick = pick;
        return p;
    }

    private static Choose.Opt videoOpt(VideoStream v, boolean videoOnly) {
        if (!v.isUrl()
                || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP
                || nz(v.getContent()).isEmpty()) {
            return null;
        }
        Choose.Opt o = new Choose.Opt();
        o.url = v.getContent();
        o.itag = parseInt(Links.urlParam(o.url, "itag"));
        o.mime = mimeOf(o.url, String.valueOf(v.getFormat()), false);
        o.videoOnly = videoOnly;
        int h = v.getHeight();
        if (h <= 0) {
            h = digits(v.getResolution());
        }
        o.height = h;
        String codec = codecOf(v);
        o.avc = codec != null
                ? codec.toLowerCase(Locale.ROOT).startsWith("avc")
                : Choose.avcItag(o.itag);
        o.size = parseLong(Links.urlParam(o.url, "clen"));
        return o;
    }

    /** Oqim turi: avval havoladagi mime parametri, bo'lmasa kutubxonadagi format nomi. */
    private static String mimeOf(String url, String format, boolean audio) {
        String m = Links.urlParam(url, "mime");
        if (m != null && !m.isEmpty()) {
            return m.toLowerCase(Locale.ROOT);
        }
        String f = format == null ? "" : format.toUpperCase(Locale.ROOT);
        if (audio) {
            if (f.contains("M4A")) {
                return "audio/mp4";
            }
            if (f.contains("WEBMA")) {
                return "audio/webm";
            }
            return "";
        }
        if (f.contains("MPEG_4") || f.contains("MP4")) {
            return "video/mp4";
        }
        if (f.contains("WEBM")) {
            return "video/webm";
        }
        if (f.contains("3GPP")) {
            return "video/3gpp";
        }
        return "";
    }

    /** Kodek nomi (masalan avc1.64001F). Kutubxona versiyasida bu usul bo'lmasa null. */
    private static String codecOf(Object stream) {
        try {
            Object c = stream.getClass().getMethod("getCodec").invoke(stream);
            return c == null ? null : c.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static int digits(String s) {
        if (s == null) {
            return 0;
        }
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) {
            i++;
        }
        int n = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && n < 100000) {
            n = n * 10 + (s.charAt(i) - '0');
            i++;
        }
        return n;
    }

    private static int parseInt(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
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

    /** YouTube so'rovlarni vaqtincha cheklab qo'ydimi (429, reCAPTCHA). */
    static boolean isRateLimited(Throwable t) {
        Throwable x = t;
        for (int i = 0; i < 6 && x != null; i++) {
            String n = x.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String m = String.valueOf(x.getMessage()).toLowerCase(Locale.ROOT);
            if (n.contains("recaptcha") || m.contains("429") || m.contains("recaptcha")) {
                return true;
            }
            x = x.getCause();
        }
        return false;
    }

    // ---------------------------------------------------------------- yordamchilar

    private static final Pattern URL_PAT = Pattern.compile("(https?://[^\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_PAT = Pattern.compile(
            "((?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)/[^\\s]+)", Pattern.CASE_INSENSITIVE);

    static String extractYoutubeUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL_PAT.matcher(text);
        while (m.find()) {
            String u = trimEnd(m.group(1));
            String l = u.toLowerCase(Locale.ROOT);
            if (l.contains("youtube.com/") || l.contains("youtu.be/")) {
                return u;
            }
        }
        Matcher b = BARE_PAT.matcher(text);
        if (b.find()) {
            return "https://" + trimEnd(b.group(1));
        }
        return null;
    }

    private static String trimEnd(String s) {
        int e = s.length();
        while (e > 0 && ".,;:!?)]}>\"'".indexOf(s.charAt(e - 1)) >= 0) {
            e--;
        }
        return s.substring(0, e);
    }

    static String spoken(long sec) {
        if (sec < 0) {
            return "Jonli efir";
        }
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) {
            b.append(h).append(" soat ");
        }
        if (m > 0) {
            b.append(m).append(" daqiqa ");
        }
        if (s > 0 || b.length() == 0) {
            b.append(s).append(" soniya");
        }
        return b.toString().trim();
    }

    /** Havolaning shaxsiy qismlarini (IP, imzo) olib tashlab, jurnal uchun qisqartiradi. */
    static String redact(String url) {
        try {
            Uri u = Uri.parse(url);
            StringBuilder b = new StringBuilder();
            b.append(u.getHost()).append(u.getPath() == null ? "" : u.getPath());
            String[] keep = {"c", "itag", "mime", "clen", "dur", "source", "ratebypass", "expire"};
            String sep = "?";
            for (String k : keep) {
                String v = u.getQueryParameter(k);
                if (v != null) {
                    b.append(sep).append(k).append('=').append(v);
                    sep = "&";
                }
            }
            return b.toString();
        } catch (Throwable e) {
            return "(havola)";
        }
    }

    /** Xatoni foydalanuvchiga tushunarli qilib tushuntiradi. */
    static String explain(Throwable t) {
        Throwable w = t;
        for (int i = 0; i < 6 && w != null; i++) {
            if (w instanceof Msg) {
                return w.getMessage();
            }
            w = w.getCause();
        }
        StringBuilder sb = new StringBuilder();
        Throwable x = t;
        for (int i = 0; i < 6 && x != null; i++) {
            sb.append(x.getClass().getSimpleName()).append(' ').append(x.getMessage()).append(' ');
            x = x.getCause();
        }
        String s = sb.toString().toLowerCase(Locale.ROOT);
        if (s.contains("no space left") || s.contains("enospc")) {
            return "Telefon xotirasi to'lib qoldi.";
        }
        if (s.contains("agerestricted")) {
            return "Bu video yosh cheklovi tufayli ochilmaydi.";
        }
        if (s.contains("privatecontent") || s.contains("private video")) {
            return "Bu video yopiq (private).";
        }
        if (s.contains("geographic")) {
            return "Bu video sizning mintaqangizda mavjud emas.";
        }
        if (s.contains("paidcontent") || s.contains("members")) {
            return "Bu video pullik yoki faqat a'zolar uchun.";
        }
        if (s.contains("signin") || s.contains("sign in") || s.contains("not a bot")) {
            return "YouTube hozir botdan himoya tekshiruvini so'ramoqda. Birozdan keyin qayta urinib ko'ring.";
        }
        if (s.contains("recaptcha") || s.contains("429")) {
            return "YouTube vaqtincha so'rovlarni cheklab qo'ydi. Bir necha daqiqadan keyin qayta urinib ko'ring.";
        }
        if (s.contains("unknownhost") || s.contains("unable to resolve") || s.contains("timeout")
                || s.contains("connectexception") || s.contains("sslexception") || s.contains("socketexception")) {
            return "Internet aloqasi yo'q yoki juda sekin.";
        }
        if (s.contains("contentnotavailable") || s.contains("not available") || s.contains("unavailable")) {
            return "Video mavjud emas yoki o'chirib tashlangan.";
        }
        return "YouTube bilan ishlashda xato chiqdi. Kutubxonani yangilash kerak bo'lishi mumkin.";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---------------------------------------------------------------- tarmoq (Downloader)

    /** NewPipeExtractor uchun oddiy HTTP yuklovchi. */
    static final class Dl extends Downloader {
        static final String UA =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";

        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            String url = request.url();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setRequestMethod(request.httpMethod());
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", UA);

                Map<String, List<String>> headers = request.headers();
                if (headers != null) {
                    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                        boolean first = true;
                        for (String v : e.getValue()) {
                            if (first) {
                                c.setRequestProperty(e.getKey(), v);
                                first = false;
                            } else {
                                c.addRequestProperty(e.getKey(), v);
                            }
                        }
                    }
                }

                byte[] data = request.dataToSend();
                if (data != null) {
                    c.setDoOutput(true);
                    c.setFixedLengthStreamingMode(data.length);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(data);
                    }
                }

                int code = c.getResponseCode();
                if (code == 429) {
                    throw new ReCaptchaException("reCaptcha Challenge requested", url);
                }

                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = "";
                if (in != null) {
                    String enc = c.getContentEncoding();
                    if (enc != null && enc.equalsIgnoreCase("gzip")) {
                        in = new GZIPInputStream(in);
                    }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        bo.write(buf, 0, n);
                    }
                    in.close();
                    body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                }

                Map<String, List<String>> rh = new HashMap<>();
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null) {
                        rh.put(e.getKey(), e.getValue());
                    }
                }
                return new Response(code, nz(c.getResponseMessage()), rh, body, c.getURL().toString());
            } finally {
                c.disconnect();
            }
        }
    }
}
