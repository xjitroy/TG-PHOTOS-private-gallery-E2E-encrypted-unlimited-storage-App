package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class ExternalMediaPolicyTest {
    @Test public void acceptsOtherGrantedContentProviders() {
        assertTrue(ExternalMediaPolicy.readable("content","com.whatsapp.provider.media","app.televault.photos"));
        assertTrue(ExternalMediaPolicy.readable("content","media","app.televault.photos"));
        assertEquals(100,ExternalMediaPolicy.MAX_FILES);
    }
    @Test public void rejectsDirectPrivateFilesNetworkAndInternalPlaybackUris() {
        for(String scheme:new String[]{"file","https","http","tgphotos",null}) assertFalse(ExternalMediaPolicy.readable(scheme,"media","app.televault.photos"));
    }
    @Test public void cannotForgeAccessToOwnSharedOrEncryptedProviders() {
        for(String authority:new String[]{"app.televault.photos.private-media","app.televault.photos.files","app.televault.photos","0@app.televault.photos.private-media","APP.TELEVAULT.PHOTOS.FILES",null,""}) assertFalse(ExternalMediaPolicy.readable("content",authority,"app.televault.photos"));
    }
}
