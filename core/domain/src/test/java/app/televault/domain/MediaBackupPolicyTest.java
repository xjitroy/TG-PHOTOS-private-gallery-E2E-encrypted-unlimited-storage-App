package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class MediaBackupPolicyTest {
 @Test public void enablingMobileVideoResumesWifiDeferredJobsWithoutBypassingNotices(){
  assertTrue(MediaBackupPolicy.retryEligible("MOBILE_VIDEO_DISABLED",false,true,false));
  assertFalse(MediaBackupPolicy.retryEligible("MOBILE_VIDEO_DISABLED",false,false,true));
  assertFalse(MediaBackupPolicy.retryEligible("VIDEO_NOTICE_REQUIRED",false,true,false));
  assertTrue(MediaBackupPolicy.retryEligible("VIDEO_NOTICE_REQUIRED",false,true,true));
  assertFalse(MediaBackupPolicy.retryEligible("VIDEO_NOTICE_REQUIRED",true,true,true));
  assertFalse(MediaBackupPolicy.retryEligible("CANCELLED",false,true,true));
 }
 @Test public void saverOnlyNewCanonicalCameraPhotosOnAnyNetwork(){
  assertTrue(MediaBackupPolicy.automaticAllowed(true,true,"image/jpeg",100,100));
  assertFalse(MediaBackupPolicy.automaticAllowed(true,true,"image/jpeg",99,100));
  assertFalse(MediaBackupPolicy.automaticAllowed(true,false,"image/jpeg",101,100));
  assertFalse(MediaBackupPolicy.automaticAllowed(true,true,"video/mp4",101,100));
  assertFalse(MediaBackupPolicy.automaticAllowed(true,true,"audio/mpeg",101,100));
  assertTrue(MediaBackupPolicy.automaticAllowed(false,false,"video/mp4",1,100));
 }
}
