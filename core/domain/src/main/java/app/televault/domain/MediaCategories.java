package app.televault.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline presentation hints from existing names/folders. No file reads or backup policy calls. */
public final class MediaCategories {
    public enum Category {
        ALL(0), CAMERA(1), DOCUMENTS(2), SCREENSHOTS(4), SCREEN_RECORDINGS(8),
        INSTAGRAM(16), WHATSAPP(32), TELEGRAM(64), FACEBOOK(128), DOWNLOADS(256), PNG(512), OTHER(1024), MOVIES(2048);
        public final int mask;
        Category(int mask) { this.mask = mask; }
    }
    private static final int SOCIAL = 16 | 32 | 64 | 128;
    private static final Pattern SHOT = Pattern.compile("screen[\\s_.-]*shots?");
    private static final Pattern RECORD = Pattern.compile("screen[\\s_.-]*(?:record|recorder|recording|capture)");
    private static final Pattern CAMERA_NAME = Pattern.compile("^(?:(?:img|vid|dscn?|dscf|pxl|mvimg|mvi)[_.-]?\\d|20\\d{6}[_-]\\d{6})");
    private static final Pattern WA = Pattern.compile("(?:^|[-_])wa\\d{3,}(?:\\D|$)");
    private static final Pattern DOCUMENT = Pattern.compile("(?:^|[^\\p{L}\\p{N}])(?:scan(?:ned|s)?|document(?:s)?|doc|camscanner|adobescan|microsoft[ _-]?lens|office[ _-]?lens)(?:$|[^\\p{L}])");
    private static final Set<String> DOCUMENT_TYPES = Set.of("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "odt", "ods", "rtf", "epub");
    private MediaCategories() {}
    private static String normalized(String value, int bound) {
        if (value == null) return "";
        String limited = value.length() > bound ? value.substring(0, bound) : value;
        return Normalizer.normalize(limited, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('\\', '/');
    }
    private static boolean folder(String path, String word) {
        return ("/" + path + "/").contains("/" + word + "/");
    }
    public static int classify(String name, String parent, String mime) { return classify(name, parent, mime, false); }
    public static String videoMime(String name, String mime) {
        String type=normalized(mime,128),file=normalized(name,1024);
        if(type.startsWith("video/"))return type;
        if(!type.isEmpty() && !type.equals("application/octet-stream") && !type.equals("application/x-matroska"))return mime;
        String ext=file.substring(file.lastIndexOf('.')+1);
        return switch(ext) {
            case "mkv" -> "video/x-matroska";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "avi" -> "video/x-msvideo";
            case "mov" -> "video/quicktime";
            case "3gp", "3gpp" -> "video/3gpp";
            case "ts", "mts", "m2ts" -> "video/mp2t";
            case "mpg", "mpeg", "vob" -> "video/mpeg";
            default -> mime == null ? "application/octet-stream" : mime;
        };
    }
    public static int classify(String name, String parent, String mime, boolean embeddedCamera) { return classify(name,parent,mime,embeddedCamera,0); }
    public static int classify(String name, String parent, String mime, boolean embeddedCamera, long durationMs) {
        String file = normalized(name, 1024), path = normalized(parent, 4096), type = normalized(mime, 128);
        String text = file + "/" + path;
        int tags = durationMs>=3600000L && videoMime(name,mime).startsWith("video/") ? Category.MOVIES.mask : 0;
        if (SHOT.matcher(text).find() || text.contains("স্ক্রিনশট")) tags |= Category.SCREENSHOTS.mask;
        if (RECORD.matcher(text).find() || text.contains("স্ক্রিন রেকর্ড")) tags |= Category.SCREEN_RECORDINGS.mask;
        if (text.contains("instagram") || text.contains("ইনস্টাগ্রাম") || text.contains("ইন্সটাগ্রাম") || folder(path, "insta")) tags |= Category.INSTAGRAM.mask;
        if (text.contains("whatsapp") || text.contains("হোয়াটসঅ্যাপ") || text.contains("হোয়াটসঅ্যাপ") || WA.matcher(file).find()) tags |= Category.WHATSAPP.mask;
        if (text.contains("telegram") || text.contains("টেলিগ্রাম")) tags |= Category.TELEGRAM.mask;
        if (text.contains("facebook") || text.contains("ফেসবুক") || text.contains("messenger") || file.startsWith("fb_img")) tags |= Category.FACEBOOK.mask;
        if (folder(path, "download") || folder(path, "downloads")) tags |= Category.DOWNLOADS.mask;
        String extension = file.substring(file.lastIndexOf('.') + 1);
        if (type.equals("image/png") || extension.equals("png")) tags |= Category.PNG.mask;
        if (type.equals("application/pdf") || type.startsWith("text/") || DOCUMENT_TYPES.contains(extension) || DOCUMENT.matcher(text).find() || text.contains("স্ক্যান") || text.contains("ডকুমেন্ট")) tags |= Category.DOCUMENTS.mask;
        // A copied/social/screenshot IMG_* is not evidence of an original camera capture.
        if ((tags & (SOCIAL | Category.SCREENSHOTS.mask | Category.SCREEN_RECORDINGS.mask)) == 0 &&
            (embeddedCamera || folder(path, "camera") || folder(path, "dcim") || CAMERA_NAME.matcher(file).find())) tags |= Category.CAMERA.mask;
        return tags == 0 ? Category.OTHER.mask : tags;
    }
    public static Category category(String value) {
        try { return Category.valueOf(value == null ? "ALL" : value); }
        catch (IllegalArgumentException ignored) { return Category.ALL; }
    }
    public static int hiddenMask(Set<String> ids) {
        int mask = 0;
        if (ids != null) for (String id : ids) mask |= category(id).mask;
        return mask;
    }
    public static boolean clean(int tags, String mime) {
        String type = mime == null ? "" : mime;
        if (!type.startsWith("image/") && !type.startsWith("video/")) return true;
        if ((tags & Category.MOVIES.mask) != 0) return true;
        if ((tags & (Category.SCREENSHOTS.mask | Category.SCREEN_RECORDINGS.mask)) != 0) return false;
        // Keep unknown media and ordinary videos. Filename/folder absence is not proof of junk.
        if (type.startsWith("video/")) return true;
        if ((tags & Category.DOCUMENTS.mask) != 0) return true;
        // Only clearly labelled social graphics are hidden automatically; family JPEGs remain.
        return (tags & Category.PNG.mask) == 0 || (tags & SOCIAL) == 0;
    }
    public static boolean visible(int tags, String mime, Category selected, int hidden, boolean clean) {
        return (selected == Category.ALL || (tags & selected.mask) != 0) && (tags & hidden) == 0 && (!clean || clean(tags, mime));
    }
}
