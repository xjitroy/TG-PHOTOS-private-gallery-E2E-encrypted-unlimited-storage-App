package app.televault.domain;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackupIdentityTest {
    private String hash(String value)throws Exception { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    @Test public void sameNameAndSizeCannotSuppressChangedContents()throws Exception { assertFalse(BackupIdentity.same(hash("one"),3,hash("two"),3,true,false)); }
    @Test public void renamedOriginalAndChangedProviderUriStillMatchContents()throws Exception { assertTrue(BackupIdentity.same(hash("same"),4,hash("same"),4,true,false)); }
    @Test public void allFileKindsHaveTheSameIdentityRuleIncludingEmptyFiles()throws Exception { assertTrue(BackupIdentity.same(hash(""),0,hash(""),0,true,false));assertFalse(BackupIdentity.same(hash("same"),5,hash("same"),4,true,false)); }
    @Test public void forceCopyAndTrashCannotBeMistakenForLiveBackups()throws Exception { String h=hash("same");assertFalse(BackupIdentity.same(h,4,h,4,true,true));assertFalse(BackupIdentity.same(h,4,h,4,false,false)); }
    @Test public void malformedMissingAndWrongLengthDigestsNeverSkipNewData() { assertFalse(BackupIdentity.same("?",4,"?",4,true,false));assertFalse(BackupIdentity.same("",4,"",4,true,false));assertFalse(BackupIdentity.same(null,4,null,4,true,false)); }
}
