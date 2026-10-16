package app.televault.domain;

import java.util.Locale;
import java.util.Set;

/** Exclude generated preview directories without hiding unrelated private-named folders. */
public final class DeviceFileVisibility {
    private static final Set<String> CACHES=Set.of(".thumbnails", ".thumbnail", ".thumbs", ".thumb", ".thubnal");
    private DeviceFileVisibility() {}
    public static boolean visible(String path) {
        if(path==null) return true;
        for(String segment:path.replace('\\','/').split("/")) {
            if(CACHES.contains(segment.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }
}
