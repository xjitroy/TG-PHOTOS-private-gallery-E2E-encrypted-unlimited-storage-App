package app.televault.domain;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultDirectoryPolicyTest {
    @Test public void preferredReplacementSurvivesDeletionOfItsLegacyBaseDescriptor() {
        assertEquals("replacement",VaultDirectoryPolicy.choose("replacement",null,Arrays.asList("newest","replacement")));
    }
    @Test public void deletingTheSelectedForgottenBaseRevealsAnExistingSiblingWithoutCloudReload() {
        assertEquals("newest",VaultDirectoryPolicy.choose("deleted-base",null,Arrays.asList("newest","older")));
    }
    @Test public void anExistingBaseAndExplicitSelectionRemainCompatible() {
        assertEquals("base",VaultDirectoryPolicy.choose("base","base",Arrays.asList("newest")));
        assertEquals("base",VaultDirectoryPolicy.choose("missing","base",Arrays.asList("newest")));
        assertNull(VaultDirectoryPolicy.choose("deleted",null,Collections.emptyList()));
    }
}
