package app.televault.domain;
/** Owner policy: even metered Wi-Fi has no app mobile-data cap. */
public final class UploadTransport {
    private UploadTransport() {}
    public static boolean mobileBudget(boolean wifi, boolean cellular, boolean metered) {
        return !wifi && (cellular || metered);
    }
}
