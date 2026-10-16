package app.televault.domain;

/** Exact app-owned protocol boundaries, never substring ownership of an unrelated Saved Message. */
public final class OwnedCloudCaption {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private OwnedCloudCaption() {}
    public static boolean matches(String caption,String owner) {
        if(caption==null || owner==null)return false;
        boolean privateVault=owner.matches(":PV2:"+UUID+":"+UUID);
        if(!privateVault && !owner.matches(":"+UUID+":"+UUID))return false;
        if(caption.equals("TGDRIVE_V1_FILE"+owner))return true;
        String prefix="TGDRIVE_V1_DATA"+owner;
        if(!caption.startsWith(prefix))return false;
        String suffix=caption.substring(prefix.length());
        return privateVault?suffix.matches(":"+UUID+":[0-9]+"):(suffix.isEmpty() || suffix.matches(":[0-9]+") || suffix.matches(":"+UUID+":[0-9]+:"));
    }
}
