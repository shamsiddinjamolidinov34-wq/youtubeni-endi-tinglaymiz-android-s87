package uz.tinglaymiz.app;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * YouTube havolasining turini aniqlaydi: video, pleylist, kanal yoki qidiruv sahifasi.
 * Android va NewPipe'ga bog'liq emas.
 */
final class Links {

    static final int NONE = 0;
    static final int VIDEO = 1;
    static final int PLAYLIST = 2;
    static final int CHANNEL = 3;
    static final int SEARCH = 4;

    static final class Info {
        int type = NONE;
        /** Video identifikatori (video havolasi yoki pleylistdagi video). */
        String videoId;
        /** Pleylist identifikatori, havolada bo'lsa. */
        String listId;
        /** Kanal identifikatori (UC...), havolada bo'lsa. */
        String channelId;
        /** Kanal yo'li: /@nom, /c/nom yoki /user/nom. */
        String channelPath;
        /** Qidiruv so'zi (qidiruv sahifasi havolasi uchun). */
        String query;
    }

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern LIST_ID = Pattern.compile("[A-Za-z0-9_-]{10,}");
    private static final Pattern CHANNEL_ID = Pattern.compile("UC[A-Za-z0-9_-]{22}");

    private Links() {
    }

    /** "vnd.youtube:ID" kabi maxsus havolalarni oddiy https havolasiga aylantiradi. */
    static String normalize(String data) {
        if (data == null) {
            return null;
        }
        String s = data.trim();
        if (!s.regionMatches(true, 0, "vnd.youtube", 0, 11)) {
            return s;
        }
        if (s.length() > 11 && s.charAt(11) != ':') {
            return s;
        }
        String rest = s.length() > 12 ? s.substring(12) : "";
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        int end = 0;
        while (end < rest.length() && "?&#/".indexOf(rest.charAt(end)) < 0) {
            end++;
        }
        String head = rest.substring(0, end);
        if (VIDEO_ID.matcher(head).matches()) {
            String qs = "";
            int qi = rest.indexOf('?');
            if (qi >= 0) {
                qs = rest.substring(qi + 1);
            }
            String list = validList(param(qs, "list"));
            return "https://www.youtube.com/watch?v=" + head + (list != null ? "&list=" + list : "");
        }
        return "https://" + rest;
    }

    static Info parse(String raw) {
        Info r = new Info();
        String s = normalize(raw);
        if (s == null || s.isEmpty()) {
            return r;
        }

        String rest = s;
        int p = rest.indexOf("://");
        if (p >= 0) {
            rest = rest.substring(p + 3);
        }
        int h = rest.indexOf('#');
        if (h >= 0) {
            rest = rest.substring(0, h);
        }
        String qs = "";
        int q = rest.indexOf('?');
        if (q >= 0) {
            qs = rest.substring(q + 1);
            rest = rest.substring(0, q);
        }
        String host = rest;
        String path = "/";
        int sl = rest.indexOf('/');
        if (sl >= 0) {
            host = rest.substring(0, sl);
            path = rest.substring(sl);
        }
        host = host.toLowerCase(Locale.ROOT);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        } else if (host.startsWith("m.")) {
            host = host.substring(2);
        } else if (host.startsWith("music.")) {
            host = host.substring(6);
        }

        String[] seg = segments(path);
        String list = validList(param(qs, "list"));
        String v = param(qs, "v");

        if (host.equals("youtu.be")) {
            if (seg.length > 0 && VIDEO_ID.matcher(seg[0]).matches()) {
                r.type = VIDEO;
                r.videoId = seg[0];
                r.listId = list;
            }
            return r;
        }
        if (!host.equals("youtube.com") && !host.equals("youtube-nocookie.com")) {
            return r;
        }
        if (seg.length == 0) {
            return r;
        }

        String first = seg[0];
        if (first.equals("watch")) {
            if (v != null && VIDEO_ID.matcher(v).matches()) {
                r.type = VIDEO;
                r.videoId = v;
                r.listId = list;
            } else if (list != null) {
                r.type = PLAYLIST;
                r.listId = list;
            }
        } else if (first.equals("shorts") || first.equals("live") || first.equals("embed")
                || first.equals("v") || first.equals("e")) {
            if (seg.length > 1) {
                if (first.equals("embed") && seg[1].equals("videoseries") && list != null) {
                    r.type = PLAYLIST;
                    r.listId = list;
                } else if (VIDEO_ID.matcher(seg[1]).matches()) {
                    r.type = VIDEO;
                    r.videoId = seg[1];
                    r.listId = list;
                }
            }
        } else if (first.equals("playlist")) {
            if (list != null) {
                r.type = PLAYLIST;
                r.listId = list;
            }
        } else if (first.equals("channel")) {
            if (seg.length > 1 && CHANNEL_ID.matcher(seg[1]).matches()) {
                r.type = CHANNEL;
                r.channelId = seg[1];
            }
        } else if (first.startsWith("@") && first.length() > 1) {
            r.type = CHANNEL;
            r.channelPath = "/" + first;
        } else if ((first.equals("c") || first.equals("user")) && seg.length > 1) {
            r.type = CHANNEL;
            r.channelPath = "/" + first + "/" + seg[1];
        } else if (first.equals("results")) {
            String sq = param(qs, "search_query");
            if (sq != null && !sq.trim().isEmpty()) {
                r.type = SEARCH;
                r.query = sq.trim();
            }
        }
        return r;
    }

    static String watchUrl(String id) {
        return "https://www.youtube.com/watch?v=" + id;
    }

    /** Pleylistning asosiy havolasi. "Mix" (RD...) ro'yxatlari boshlang'ich videoni ham talab qiladi. */
    static String playlistUrl(Info i) {
        if (i.listId == null) {
            return null;
        }
        if (i.listId.startsWith("RD") && i.videoId != null) {
            return "https://www.youtube.com/watch?v=" + i.videoId + "&list=" + i.listId;
        }
        return "https://www.youtube.com/playlist?list=" + i.listId;
    }

    static String channelUrl(Info i) {
        if (i.channelId != null) {
            return "https://www.youtube.com/channel/" + i.channelId;
        }
        if (i.channelPath != null) {
            return "https://www.youtube.com" + i.channelPath;
        }
        return null;
    }

    /** Kanalning barcha yuklamalari joylashgan avtomatik pleylist (UC... dan UU... hosil bo'ladi). */
    static String uploadsListId(String channelId) {
        if (channelId == null || !channelId.startsWith("UC") || channelId.length() < 3) {
            return null;
        }
        return "UU" + channelId.substring(2);
    }

    /** Havola turiga qarab asosiy havolani qaytaradi. */
    static String canonical(Info i) {
        switch (i.type) {
            case VIDEO:
                return watchUrl(i.videoId);
            case PLAYLIST:
                return playlistUrl(i);
            case CHANNEL:
                return channelUrl(i);
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- yordamchilar

    static String param(String query, String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq >= 0 ? part.substring(0, eq) : part;
            if (k.equals(name)) {
                String val = eq >= 0 ? part.substring(eq + 1) : "";
                try {
                    return URLDecoder.decode(val, "UTF-8");
                } catch (Exception e) {
                    return val;
                }
            }
        }
        return null;
    }

    /** To'liq havoladagi so'rov parametrini oladi (masalan, googlevideo havolasidan clen, mime). */
    static String urlParam(String url, String name) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        String qs = url.substring(q + 1);
        int h = qs.indexOf('#');
        if (h >= 0) {
            qs = qs.substring(0, h);
        }
        return param(qs, name);
    }

    private static String validList(String l) {
        return l != null && LIST_ID.matcher(l).matches() ? l : null;
    }

    private static String[] segments(String path) {
        ArrayList<String> out = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }
}
