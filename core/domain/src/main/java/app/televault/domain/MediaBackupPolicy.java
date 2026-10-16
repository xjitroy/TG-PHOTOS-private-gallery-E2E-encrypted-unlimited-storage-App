package app.televault.domain;
/** Data Saver changes automatic backup only; an explicit manual upload stays owner-controlled. */
public final class MediaBackupPolicy {
 private MediaBackupPolicy(){}
 public static boolean retryEligible(String phase,boolean saver,boolean mobileVideos,boolean noticesAllowed){
  return "MOBILE_VIDEO_DISABLED".equals(phase) && mobileVideos ||
   "DATA_SAVER_AUTO_PAUSED".equals(phase) && !saver ||
   "VIDEO_NOTICE_REQUIRED".equals(phase) && !saver && noticesAllowed;
 }
 public static boolean automaticAllowed(boolean saver,boolean camera,String mime,long capturedAt,long since){
  return !saver || camera && mime!=null && mime.startsWith("image/") && capturedAt>=since;
 }
}
