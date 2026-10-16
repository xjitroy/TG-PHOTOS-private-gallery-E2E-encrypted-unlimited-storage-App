package app.televault.domain;

/** Narrow deletion ownership and server-time grace period; no client clock can shorten it. */
public final class ForgottenVaultPolicy {
    public static final long GRACE_MILLIS = 86400000L;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private ForgottenVaultPolicy() { }
    public static boolean validId(String id) { return id != null && id.matches(UUID); }
    public static boolean due(long requestServerTime, long proofServerTime) {
        return requestServerTime > 0 && proofServerTime >= requestServerTime && proofServerTime - requestServerTime >= GRACE_MILLIS;
    }
    public static boolean lifecycleMarker(String marker) {
        return marker != null && marker.matches("TGDRIVE_V1_RESET:"+UUID+":(REQUEST|CANCEL|START|DONE|CLOCK:"+UUID+")");
    }
    public enum Phase { PENDING, CANCELLED, STARTED, DONE }
    public static Phase phase(long requestedAt, long cancelMessage, long startMessage, long startedAt, long doneMessage, long doneAt) {
        if (cancelMessage > 0 && (startMessage == 0 || cancelMessage < startMessage)) return Phase.CANCELLED;
        if (startMessage > 0 && !due(requestedAt, startedAt)) throw new IllegalArgumentException("Premature deletion start");
        if (doneMessage > 0) {
            if (startMessage <= 0 || doneMessage <= startMessage || !due(requestedAt, doneAt)) throw new IllegalArgumentException("Invalid deletion completion");
            return Phase.DONE;
        }
        return startMessage > 0 ? Phase.STARTED : Phase.PENDING;
    }
    public static boolean owns(String caption, String photos, String privateVault) {
        if (caption == null || !validId(photos) || privateVault != null && !validId(privateVault)) return false;
        if (privateVault != null) return OwnedCloudCaption.matches(caption, ":PV2:"+photos+":"+privateVault);
        if (caption.equals("TGDRIVE_V1_ROOT:"+photos) || caption.equals("TGDRIVE_V1_PRIVATE_ROOT:"+photos)) return true;
        if (caption.matches("TGDRIVE_V1_(FILE|EVENT):"+photos+":"+UUID)) return true;
        if (caption.matches("TGDRIVE_V1_DATA:"+photos+":"+UUID+"(:[0-9]+|:"+UUID+":[0-9]+:)?")) return true;
        return caption.matches("TGDRIVE_V1_FILE:PV2:"+photos+":"+UUID)
            || caption.matches("TGDRIVE_V1_DATA:PV2:"+photos+":"+UUID+":"+UUID+":[0-9]+");
    }
}
