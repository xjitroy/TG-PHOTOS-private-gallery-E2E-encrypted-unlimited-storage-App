package app.televault.domain;

/** A MediaStore relative folder identity, never a filesystem traversal path. */
public final class DeviceBackupFolder {
    private DeviceBackupFolder() {}
    public static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("Missing folder");
        String folder = value.replaceAll("/+$", "");
        if (folder.isBlank() || folder.startsWith("/") || folder.length() > 1024)
            throw new IllegalArgumentException("Invalid relative folder");
        for (String part : folder.split("/", -1))
            if (part.isBlank() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("Invalid folder component");
        return folder;
    }
    public static boolean matches(String actual, String selected, boolean legacyCamera) {
        try {
            String folder = normalize(actual);
            if (legacyCamera) return folder.substring(folder.lastIndexOf('/') + 1).equalsIgnoreCase("Camera");
            return folder.equals(normalize(selected));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
