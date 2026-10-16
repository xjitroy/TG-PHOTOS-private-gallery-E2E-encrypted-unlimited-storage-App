package app.televault.domain;

/** Hierarchical display paths, independent of filesystem write authority. */
public final class LibraryPath {
    private LibraryPath() {}
    public static String clean(String path) {
        if (path == null) return "";
        var parts = new java.util.ArrayList<String>();
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            if (part.equals(".") || part.equals("..") || part.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid folder path");
            parts.add(part);
        }
        return String.join("/", parts);
    }
    public static String parent(String path) {
        path = clean(path);
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
    /** Returns only the immediate child directory, never prefix-matches sibling names. */
    public static String child(String current, String folder) {
        current = clean(current); folder = clean(folder);
        String prefix = current.isEmpty() ? "" : current + "/";
        if (!folder.startsWith(prefix) || folder.equals(current)) return null;
        String rest = folder.substring(prefix.length());
        return rest.isEmpty() ? null : prefix + rest.split("/", 2)[0];
    }
    /** Inclusive subtree boundary. Never include prefix siblings such as Download/Downloads. */
    public static boolean within(String current,String parent) {
        current=clean(current);parent=clean(parent);
        return current.isEmpty() || parent.equals(current) || parent.startsWith(current+"/");
    }
    public static String category(String mime) {
        mime = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        if (mime.startsWith("image/")) return "Photos";
        if (mime.startsWith("video/")) return "Videos";
        if (mime.startsWith("audio/")) return "Audio";
        if (mime.startsWith("text/") || mime.equals("application/pdf") || mime.contains("officedocument") || mime.contains("msword") || mime.contains("ms-excel") || mime.contains("ms-powerpoint") || mime.contains("opendocument") || mime.equals("application/rtf")) return "Documents";
        return "Other files";
    }
}
