package uz.tinglaymiz.app;

/** Yuklab olish uchun format va sifat variantlari hamda ularning matnlari. */
final class DlOpts {

    static final String[] FORMATS = {
            "Audio, M4A (hamma joyda ishlaydi)",
            "Audio, WebM Opus (hajmi kichikroq)",
            "Video, MP4 (tasvir va ovoz)"
    };

    /** Audio: eng katta bitreyt (kbit/s). 0 - eng yaxshisi, 1 - eng pasti. */
    static final int[] AUDIO_CAPS = {0, 128, 64, 1};
    static final String[] AUDIO_QUALITIES = {
            "Eng yuqori sifat",
            "128 kbit/s gacha (yaxshi, hajmi o'rtacha)",
            "64 kbit/s gacha (nutq uchun yetarli, hajmi kichik)",
            "Eng past sifat (hajmi eng kichik)"
    };

    /** Video: eng katta balandlik (piksel). 0 - eng yaxshisi, 1 - eng pasti. */
    static final int[] VIDEO_CAPS = {0, 1080, 720, 480, 360, 1};
    static final String[] VIDEO_QUALITIES = {
            "Eng yuqori sifat",
            "1080p gacha",
            "720p gacha",
            "480p gacha",
            "360p gacha",
            "Eng past sifat (hajmi eng kichik)"
    };

    private DlOpts() {
    }

    static int[] caps(int format) {
        return format == Choose.MP4 ? VIDEO_CAPS : AUDIO_CAPS;
    }

    static String[] qualities(int format) {
        return format == Choose.MP4 ? VIDEO_QUALITIES : AUDIO_QUALITIES;
    }

    static String formatName(int format) {
        switch (format) {
            case Choose.WEBM:
                return "Audio WebM";
            case Choose.MP4:
                return "Video MP4";
            default:
                return "Audio M4A";
        }
    }

    static String qualityName(int format, int cap) {
        if (cap <= 0) {
            return "eng yuqori sifat";
        }
        if (cap == 1) {
            return "eng past sifat";
        }
        return format == Choose.MP4 ? cap + "p gacha" : cap + " kbit/s gacha";
    }

    static String describe(int format, int cap) {
        return formatName(format) + ", " + qualityName(format, cap);
    }
}
