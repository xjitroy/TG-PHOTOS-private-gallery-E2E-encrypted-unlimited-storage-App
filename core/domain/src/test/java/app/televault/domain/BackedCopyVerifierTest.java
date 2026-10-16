package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;

public class BackedCopyVerifierTest {
    @Test public void verifiesEveryByteAndRejectsChangedOrTruncatedOriginal() throws Exception {
        byte[] data = new byte[180001];
        for(int i=0;i<data.length;i++) data[i]=(byte)i;
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(data);
        assertTrue(BackedCopyVerifier.verify(new ByteArrayInputStream(data),data.length,hash,()->{}));
        data[140000]^=1;
        assertFalse(BackedCopyVerifier.verify(new ByteArrayInputStream(data),data.length,hash,()->{}));
        assertFalse(BackedCopyVerifier.verify(new ByteArrayInputStream(data,0,data.length-1),data.length,hash,()->{}));
    }
    @Test public void rejectsExtraBytesAndInvalidDigest() throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(new byte[]{1,2});
        assertFalse(BackedCopyVerifier.verify(new ByteArrayInputStream(new byte[]{1,2,3}),2,hash,()->{}));
        assertFalse(BackedCopyVerifier.verify(new ByteArrayInputStream(new byte[]{1,2}),2,new byte[31],()->{}));
    }
    @Test public void cancellationCannotAuthorizeRemoval() throws Exception {
        try {
            BackedCopyVerifier.verify(new ByteArrayInputStream(new byte[]{1}),1,new byte[32],()->{throw new CancellationException();});
            fail("Canceled verification must not authorize removal");
        } catch(CancellationException expected) {}
    }
}
