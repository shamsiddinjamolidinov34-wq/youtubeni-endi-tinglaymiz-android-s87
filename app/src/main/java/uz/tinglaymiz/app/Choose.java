package uz.tinglaymiz.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Yuklab olish uchun format va sifat chegarasiga mos oqimni tanlaydi.
 * NewPipe turlariga bog'liq emas, shuning uchun alohida sinab ko'rish mumkin.
 */
final class Choose {

    static final int M4A = 0;
    static final int WEBM = 1;
    static final int MP4 = 2;

    /** Yuklab olish mumkin bo'lgan bitta oqim. */
    static final class Opt {
        String url = "";
        String mime = "";
        int itag;
        /** Audio uchun o'rtacha bitreyt (kbit/s), noma'lum bo'lsa 0. */
        int kbps;
        /** Video uchun balandlik (piksel). */
        int height;
        boolean videoOnly;
        boolean avc;
        boolean original = true;
        /** Baytlarda, noma'lum bo'lsa -1. */
        long size = -1;
    }

    /** Tanlov natijasi. */
    static final class Pick {
        /** Yuklanadigan asosiy oqim (birlashtirishda: tasvir oqimi). */
        Opt main;
        /** Birlashtirishda qo'shiladigan ovoz oqimi. */
        Opt extraAudio;
        boolean merge;
        String ext = "";
        String mime = "";
        String label = "";
        String note = "";
        /** Birlashtirib bo'lmasa ishlatiladigan tayyor (ovozli) oqim, bo'lmasa null. */
        Pick fallback;
    }

    private Choose() {
    }

    // ---------------------------------------------------------------- audio

    /**
     * @param format M4A yoki WEBM
     * @param cap    eng katta bitreyt (kbit/s); 0 - cheklovsiz (eng yaxshisi),
     *               1 - eng pasti; boshqa qiymatlarda shu chegaradan oshmaydigan eng yaxshisi
     * @return tanlov yoki null, agar audio oqim bo'lmasa
     */
    static Pick audio(List<Opt> all, int format, int cap) {
        String want = format == WEBM ? "audio/webm" : "audio/mp4";
        String other = format == WEBM ? "audio/mp4" : "audio/webm";
        Opt best = pickAudio(all, want, cap);
        String note = "";
        if (best == null) {
            best = pickAudio(all, other, cap);
            if (best != null) {
                note = format == WEBM
                        ? "WebM mavjud emas, M4A tanlandi"
                        : "M4A mavjud emas, WebM tanlandi";
            }
        }
        if (best == null) {
            return null;
        }
        boolean webm = "audio/webm".equals(best.mime);
        Pick p = new Pick();
        p.main = best;
        p.ext = webm ? "webm" : "m4a";
        p.mime = webm ? "audio/webm" : "audio/mp4";
        p.label = (webm ? "WebM Opus" : "M4A") + (best.kbps > 0 ? " " + best.kbps + " kbit/s" : "");
        p.note = note;
        return p;
    }

    private static Opt pickAudio(List<Opt> all, String mime, int cap) {
        List<Opt> c = new ArrayList<>();
        boolean anyOriginal = false;
        boolean anyKnown = false;
        for (Opt o : all) {
            if (usable(o) && mime.equals(o.mime)) {
                c.add(o);
                if (o.original) {
                    anyOriginal = true;
                }
                if (o.kbps > 0) {
                    anyKnown = true;
                }
            }
        }
        if (c.isEmpty()) {
            return null;
        }
        // Dublyaj va tavsif yo'llari bo'lsa, asl ovoz yo'li afzal
        if (anyOriginal) {
            List<Opt> keep = new ArrayList<>();
            for (Opt o : c) {
                if (o.original) {
                    keep.add(o);
                }
            }
            c = keep;
        }
        // Bitreyti noma'lum oqimlar faqat boshqa oqim bo'lmasa olinadi
        if (anyKnown) {
            List<Opt> keep = new ArrayList<>();
            for (Opt o : c) {
                if (o.kbps > 0) {
                    keep.add(o);
                }
            }
            if (!keep.isEmpty()) {
                c = keep;
            }
        }

        Opt best = null;
        if (cap > 0) {
            for (Opt o : c) {
                if (o.kbps <= cap && (best == null || o.kbps > best.kbps)) {
                    best = o;
                }
            }
            if (best != null) {
                return best;
            }
            // Hammasi chegaradan yuqori: eng pasti olinadi
            for (Opt o : c) {
                if (best == null || o.kbps < best.kbps) {
                    best = o;
                }
            }
            return best;
        }
        for (Opt o : c) {
            if (best == null || o.kbps > best.kbps) {
                best = o;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- video

    /**
     * @param muxed     tayyor (tasvir va ovozli) oqimlar
     * @param videoOnly ovozsiz tasvir oqimlari
     * @param audios    ovoz oqimlari (birlashtirish uchun)
     * @param cap       eng katta balandlik (piksel); 0 - cheklovsiz, 1 - eng pasti
     * @param canMerge  telefon tasvir va ovozni birlashtira oladimi
     * @return tanlov yoki null, agar mos oqim bo'lmasa
     */
    static Pick video(List<Opt> muxed, List<Opt> videoOnly, List<Opt> audios, int cap, boolean canMerge) {
        List<Opt> ready = new ArrayList<>();
        for (Opt o : muxed) {
            if (usable(o) && "video/mp4".equals(o.mime) && o.height > 0) {
                ready.add(o);
            }
        }
        List<Opt> all = new ArrayList<>(ready);
        Opt aud = null;
        if (canMerge) {
            aud = pickAudio(audios, "audio/mp4", 0);
            if (aud != null) {
                for (Opt o : videoOnly) {
                    if (usable(o) && "video/mp4".equals(o.mime) && o.avc && o.height > 0) {
                        all.add(o);
                    }
                }
            }
        }
        Opt best = bestHeight(all, cap);
        if (best == null) {
            return null;
        }
        Pick p = new Pick();
        p.main = best;
        p.ext = "mp4";
        p.mime = "video/mp4";
        p.label = "MP4 " + best.height + "p";
        if (best.videoOnly) {
            p.merge = true;
            p.extraAudio = aud;
            Opt fb = bestHeight(ready, cap);
            if (fb != null) {
                Pick f = new Pick();
                f.main = fb;
                f.ext = "mp4";
                f.mime = "video/mp4";
                f.label = "MP4 " + fb.height + "p";
                p.fallback = f;
            }
        }
        return p;
    }

    private static Opt bestHeight(List<Opt> list, int cap) {
        Opt best = null;
        for (Opt o : list) {
            if (cap > 0 && o.height > cap) {
                continue;
            }
            if (best == null || higher(o, best)) {
                best = o;
            }
        }
        if (best != null) {
            return best;
        }
        // Hammasi chegaradan baland: eng pasti olinadi
        for (Opt o : list) {
            if (best == null || lower(o, best)) {
                best = o;
            }
        }
        return best;
    }

    private static boolean higher(Opt o, Opt b) {
        if (o.height != b.height) {
            return o.height > b.height;
        }
        if (o.videoOnly != b.videoOnly) {
            return !o.videoOnly; // tayyor (ovozli) oqim birlashtirishdan afzal
        }
        return o.size > b.size;
    }

    private static boolean lower(Opt o, Opt b) {
        if (o.height != b.height) {
            return o.height < b.height;
        }
        if (o.videoOnly != b.videoOnly) {
            return !o.videoOnly;
        }
        return o.size < b.size;
    }

    // ---------------------------------------------------------------- yordamchilar

    private static boolean usable(Opt o) {
        return o != null && o.url != null && !o.url.isEmpty();
    }

    /** H.264 (avc1) tasvir oqimlarining itag raqamlari. Kodek nomi ma'lum bo'lmaganda ishlatiladi. */
    static boolean avcItag(int itag) {
        switch (itag) {
            case 133:
            case 134:
            case 135:
            case 136:
            case 137:
            case 138:
            case 160:
            case 212:
            case 264:
            case 266:
            case 298:
            case 299:
            case 304:
            case 305:
                return true;
            default:
                return false;
        }
    }

    /** Bitreyt ma'lum bo'lmaganda itag bo'yicha taxminiy qiymat (kbit/s). */
    static int audioKbpsByItag(int itag) {
        switch (itag) {
            case 139:
                return 48;
            case 140:
                return 128;
            case 141:
                return 256;
            case 249:
                return 50;
            case 250:
                return 70;
            case 251:
                return 160;
            default:
                return 0;
        }
    }
}
