package app.televault.domain;

/** External hand-offs never gain access to this app's own encrypted/private providers. */
public final class ExternalMediaPolicy {
    public static final int MAX_FILES = 100;
    private ExternalMediaPolicy() {}
    public static boolean readable(String scheme, String authority, String ownPackage) {
        if (!"content".equals(scheme) || authority == null || authority.isBlank() || ownPackage == null) return false;
        String provider = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
        String own = ownPackage.toLowerCase(java.util.Locale.ROOT);
        return !provider.equals(own) && !provider.startsWith(own + ".");
    }
}
