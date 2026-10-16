package app.televault.domain;

import java.security.MessageDigest;
import java.util.Base64;

/** Names, dates, URIs and categories are hints only. A live authenticated digest decides. */
public final class BackupIdentity {
    private BackupIdentity() { }
    public static boolean same(String cloudHash, long cloudBytes, String sourceHash, long sourceBytes, boolean live, boolean forceCopy) {
        if (!live || forceCopy || cloudBytes < 0 || cloudBytes != sourceBytes || cloudHash == null || sourceHash == null) return false;
        try {
            byte[] expected = Base64.getDecoder().decode(cloudHash), actual = Base64.getDecoder().decode(sourceHash);
            return expected.length == 32 && actual.length == 32 && MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException malformed) { return false; }
    }
}
