package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class PhoneNumberInputTest {
    @Test public void pastedInternationalUsesLongestPrefixAndNationalKeepsSelection() {
        var known = List.of("1", "91", "880", "1242");
        var india = PhoneNumberInput.split("+91 98765-43210", "880", known);
        assertEquals("91", india.code());
        assertEquals("9876543210", india.national());
        assertTrue(india.international());
        assertEquals("91", PhoneNumberInput.split("+৯১ ৯৮৭৬৫৪৩২১০", "880", known).code());
        assertEquals("9876543210", PhoneNumberInput.split("+৯১ ৯৮৭৬৫৪৩২১০", "880", known).national());
        assertEquals("1242", PhoneNumberInput.split("+12425551234", "91", known).code());
        var local = PhoneNumberInput.split("98765 43210", "91", known);
        assertEquals("91", local.code());
        assertEquals("+919876543210", PhoneNumberInput.international(local.code(), local.national()));
        assertFalse(local.international());
    }
    @Test public void unknownPrefixIsPreservedForManualInternationalAndLengthsAreBounded() {
        var unknown = PhoneNumberInput.split("+999123", "91", List.of("91"));
        assertEquals("", unknown.code());
        assertEquals("999123", unknown.national());
        assertEquals("", PhoneNumberInput.international("91", "1".repeat(14)));
        assertEquals("", PhoneNumberInput.international("", "12345"));
        assertEquals("", PhoneNumberInput.international("91", ""));
    }
}
