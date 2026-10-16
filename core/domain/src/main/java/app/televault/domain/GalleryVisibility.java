package app.televault.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative, reversible presentation filter. Never deletes or changes backup eligibility. */
public final class GalleryVisibility {
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif", "image/gif", "image/bmp");
    private static final Pattern ASSET = Pattern.compile("(^|[_. -])(icon|logo|favicon|sprite|emoji|sticker)([_. -]|$)");
    private GalleryVisibility() {}
    public static boolean clean(String mime, String name, String folder, int width, int height, long size) {
        String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (!type.startsWith("image/") && !type.startsWith("video/")) return false;
        for (String part : (folder == null ? "" : folder).replace('\\', '/').split("/")) {
            String value = part.toLowerCase(Locale.ROOT);
            if (value.startsWith(".") || Set.of("assets", "icons", "cache", "stickers", "res").contains(value) || value.startsWith("drawable")) return false;
        }
        if (name != null && name.startsWith(".")) return false;
        if (size <= 0) return false;
        if (type.startsWith("image/")) {
            if (!IMAGES.contains(type)) return false;
            if (width > 0 && height > 0 && Math.max(width, height) <= 128) return false;
            if (ASSET.matcher(name == null ? "" : name.toLowerCase(Locale.ROOT)).find()) return false;
        }
        return true;
    }
}
