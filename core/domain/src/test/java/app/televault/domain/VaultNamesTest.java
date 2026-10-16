package app.televault.domain;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultNamesTest {
    private final String id = "11111111-2222-3333-4444-555555555555";

    @Test public void creationUsesPhoneLocalDateAndUniqueSuffix() {
        long now = Instant.parse("2026-10-16T20:00:00Z").toEpochMilli();
        assertEquals("Vault TG Photos E2EE · 2026-10-17 · 11111111", VaultNames.generated(now, ZoneId.of("Asia/Kolkata"), id));
        assertNotEquals(VaultNames.generated(now, ZoneId.of("UTC"), id), VaultNames.generated(now, ZoneId.of("UTC"), "aaaaaaaa-2222-3333-4444-555555555555"));
    }
    @Test public void blankOptionalNameUsesDefaultAndCustomBengaliNameIsRetained() {
        assertEquals(VaultNames.generated(1, ZoneId.of("UTC"), id), VaultNames.forCreation("  ", 1, ZoneId.of("UTC"), id));
        assertEquals("আমার ব্যক্তিগত ছবি", VaultNames.forCreation("  আমার ব্যক্তিগত ছবি  ", 1, ZoneId.of("UTC"), id));
    }
    @Test public void labelsCannotSpoofDirectionOrInjectControlsAndNeverSplitEmoji() {
        assertEquals("abc", VaultNames.clean("a\u202eb\u0000c"));
        String result = VaultNames.clean("😀".repeat(100));
        assertEquals(80, result.codePointCount(0, result.length()));
        assertFalse(Character.isHighSurrogate(result.charAt(result.length() - 1)));
    }
    @Test public void legacyNameNeverInventsACreationDate() {
        assertEquals("Private Vault · 11111111", VaultNames.display(null, 0, ZoneId.of("UTC"), id));
        try { VaultNames.generated(1, ZoneId.of("UTC"), "../../descriptor"); fail("Invalid identity"); } catch (IllegalArgumentException expected) { }
    }
}
