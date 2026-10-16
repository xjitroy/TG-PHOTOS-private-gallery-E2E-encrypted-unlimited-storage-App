package app.televault.domain;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.televault.domain.MediaCategories.Category.*;

public class MediaCategoriesTest {
    private int tags(String name, String folder, String mime) { return MediaCategories.classify(name, folder, mime); }
    @Test public void screenshotAndSocialNamesCanHaveSeveralTags() {
        int value = tags("Screenshot_Instagram_20261015.PNG", "Pictures", "image/png");
        assertTrue((value & SCREENSHOTS.mask) != 0);
        assertTrue((value & INSTAGRAM.mask) != 0);
        assertTrue((value & PNG.mask) != 0);
        assertFalse(MediaCategories.clean(value, "image/png"));
    }
    @Test public void normalizesUnicodeCaseAndBothFolderSeparators() {
        assertTrue((tags("ＳＣＲＥＥＮＳＨＯＴ_1.jpg", "", "image/jpeg") & SCREENSHOTS.mask) != 0);
        assertTrue((tags("a.jpg", "Pictures\\Instagram", "image/jpeg") & INSTAGRAM.mask) != 0);
        assertTrue((tags("ইনস্টাগ্রাম_ছবি.jpg", "", "image/jpeg") & INSTAGRAM.mask) != 0);
        assertTrue((tags("স্ক্রিনশট ১.png", "", "image/png") & SCREENSHOTS.mask) != 0);
    }
    @Test public void sameNameRuleWorksForDeviceAndCloudWithoutOriginalStillExisting() {
        int local = tags("IMG-20261015-WA0013.jpg", "WhatsApp Images", "image/jpeg");
        int cloud = tags("IMG-20261015-WA0013.jpg", "Uploads", "image/jpeg");
        assertTrue((local & WHATSAPP.mask) != 0);
        assertTrue((cloud & WHATSAPP.mask) != 0);
        assertEquals(0, cloud & CAMERA.mask);
        assertTrue(MediaCategories.clean(cloud, "image/jpeg"));
    }
    @Test public void cleanKeepsCameraVideoAndDocumentScansIncludingPng() {
        assertTrue(MediaCategories.clean(tags("PXL_20261015_123456.jpg", "", "image/jpeg"), "image/jpeg"));
        assertTrue(MediaCategories.clean(tags("20261015_123456.mp4", "", "video/mp4"), "video/mp4"));
        assertTrue(MediaCategories.clean(tags("renamed.heic", "DCIM/Camera", "image/heic"), "image/heic"));
        assertTrue(MediaCategories.clean(tags("Document_scan.png", "WhatsApp Images", "image/png"), "image/png"));
        assertFalse(MediaCategories.clean(tags("Screenshot_document.png", "DCIM/Camera", "image/png"), "image/png"));
    }
    @Test public void hidesOnlyKnownScreenCapturesAndSocialPngGraphicsWhileEnabled() {
        for (String name : new String[]{"Screen_Recording_123.mp4", "Instagram_graphic.png", "Screenshot_1.jpg"}) {
            String mime = name.endsWith("mp4") ? "video/mp4" : "image/png";
            int value = tags(name, "Downloads", mime);
            assertFalse(MediaCategories.visible(value, mime, ALL, 0, true));
            assertTrue(MediaCategories.visible(value, mime, ALL, 0, false));
        }
    }
    @Test public void hiddenCategoriesAndSelectionAreIndependentReversibleViewRules() {
        int value = tags("Screenshot_Instagram_1.png", "Pictures", "image/png");
        int hidden = MediaCategories.hiddenMask(Set.of("INSTAGRAM", "unknown", "ALL"));
        assertFalse(MediaCategories.visible(value, "image/png", SCREENSHOTS, hidden, false));
        assertTrue(MediaCategories.visible(value, "image/png", SCREENSHOTS, 0, false));
        assertFalse(MediaCategories.visible(value, "image/png", CAMERA, 0, false));
        assertFalse(MediaCategories.visible(value, "image/png", SCREENSHOTS, 0, true));
    }
    @Test public void ordinaryDocumentsAudioAndFilesSurviveCleanView() {
        for (String mime : new String[]{"application/pdf", "application/zip", "audio/mpeg", "text/plain"})
            assertTrue(MediaCategories.visible(tags("sample", "Download", mime), mime, ALL, 0, true));
        assertTrue((tags("Invoice.PDF", "Uploads", "application/octet-stream") & DOCUMENTS.mask) != 0);
        assertEquals(ALL, MediaCategories.category("invalid preference"));
    }
    @Test public void avoidsCameraSubstringAndDocumentaryFalsePositives() {
        assertEquals(0, tags("Documentary.mp4", "MyCameraEdits", "video/mp4") & (CAMERA.mask | DOCUMENTS.mask));
        assertEquals(0, tags("image.jpeg", "Downloads", "image/jpeg") & CAMERA.mask);
        assertTrue((tags("image.jpg", "Download", "image/jpeg") & DOWNLOADS.mask) != 0);
        assertEquals(OTHER.mask, tags(null, null, null));
        assertEquals(OTHER.mask, tags("Doctor holiday.jpg", "Pictures", "image/jpeg"));
    }
    @Test public void embeddedCameraFactsKeepRenamedCapturesButNeverOverrideScreenshotsOrSocial() {
        assertTrue(MediaCategories.clean(MediaCategories.classify("Holiday.jpg", "Uploads", "image/jpeg", true), "image/jpeg"));
        assertFalse(MediaCategories.clean(MediaCategories.classify("Screenshot_1.png", "Camera", "image/png", true), "image/png"));
        assertTrue(MediaCategories.clean(MediaCategories.classify("Instagram_holiday.jpg", "Uploads", "image/jpeg", true), "image/jpeg"));
    }

    @Test public void cleanPreservesOrdinaryVideosAndUncertainUsefulPhotos() {
        for(String mime:new String[]{"video/mp4","video/x-matroska","video/webm","video/quicktime"})
            assertTrue(MediaCategories.clean(tags("Holiday", "Download", mime),mime));
        for(String name:new String[]{"family.jpg","drawing.png","WhatsApp_holiday.jpg","Instagram_clip.mp4"}) {
            String mime=name.endsWith("mp4")?"video/mp4":name.endsWith("png")?"image/png":"image/jpeg";
            assertTrue(MediaCategories.clean(tags(name,"Download",mime),mime));
        }
    }
    @Test public void moviesUseDurationAndVideoTypeNotNameOrBytes() {
        for(String mime:new String[]{"video/mp4","video/x-matroska","video/unknown"}) {
            assertEquals(0,MediaCategories.classify("Movie","Downloads",mime,false,3599999)&MOVIES.mask);
            assertTrue((MediaCategories.classify("unnamed","Downloads",mime,false,3600000)&MOVIES.mask)!=0);
        }
        for(long duration:new long[]{0,-1,3599999,Long.MAX_VALUE})
            assertEquals(0,MediaCategories.classify("Movie.mp3","", "audio/mpeg",false,duration)&MOVIES.mask);
        int movie=MediaCategories.classify("Screen_recording.mkv","Downloads","application/octet-stream",false,7200000);
        assertTrue(MediaCategories.visible(movie,"video/x-matroska",MOVIES,0,true));
        assertFalse(MediaCategories.visible(movie,"video/x-matroska",MOVIES,MOVIES.mask,true));
    }
    @Test public void knownVideoContainersRecoverMissingMimeWithoutOverridingDocumentsOrAudio() {
        assertEquals("video/x-matroska",MediaCategories.videoMime("Film.MKV","application/octet-stream"));
        assertEquals("video/mp4",MediaCategories.videoMime("film.mp4",null));
        assertEquals("application/pdf",MediaCategories.videoMime("film.mp4","application/pdf"));
        assertEquals("audio/mp4",MediaCategories.videoMime("recording.mp4","audio/mp4"));
        assertEquals("application/octet-stream",MediaCategories.videoMime("unknown",null));
    }
}
