package app.televault.domain;

/** Explicit owner requests take the next upload slot, while global data/privacy limits still apply. */
public final class UploadQueuePolicy {
    private UploadQueuePolicy() {}
    public static String lane(boolean manual,boolean wifi,long delaySeconds) {
        return wifi?"wifi":delaySeconds>0?"retry":manual?"manual":"automatic";
    }
    public static long nextRetry(long previousSeconds) { return Math.min(900,Math.max(30,previousSeconds>450?900:previousSeconds*2)); }
    public static boolean terminalFailure(String phase) {
        return phase.equals("FAILED") || phase.equals("SOURCE_UNAVAILABLE") || phase.equals("SOURCE_ACCESS_REQUIRED") || phase.equals("WAITING_STORAGE");
    }
    public static boolean photoAllowance(long limit,long used,long required,boolean override) {
        return override || (required>=0 && required<=Math.max(0,limit-used));
    }
    public static int compare(boolean manualA,long orderA,String idA,boolean manualB,long orderB,String idB) {
        if(manualA!=manualB) return manualA?-1:1;
        int time=Long.compare(orderA,orderB);return time!=0?time:idA.compareTo(idB);
    }
    public static boolean eligible(String phase,long retryAt,long now,boolean running,boolean manual,boolean wifiOnly,boolean cellular,boolean saver,boolean mobileVideos,boolean notices) {
        if(retryAt>now || running || terminalFailure(phase) || phase.equals("CANCELLED") || phase.equals("COMPLETE"))return false;
        if(cellular && (wifiOnly || phase.equals("DAILY_PHOTO_LIMIT") || (phase.equals("MOBILE_VIDEO_DISABLED") && !mobileVideos)))return false;
        if(!manual && phase.equals("DATA_SAVER_AUTO_PAUSED") && saver)return false;
        return manual || !phase.equals("VIDEO_NOTICE_REQUIRED") || notices;
    }
}
