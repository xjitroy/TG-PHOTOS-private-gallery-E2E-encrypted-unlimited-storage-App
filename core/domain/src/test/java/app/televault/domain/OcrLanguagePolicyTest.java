package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class OcrLanguagePolicyTest {
    @Test public void allEightLanguagesRemainAvailableWithoutLoadingEightTogether() {
        var found = new java.util.HashSet<String>();
        for (var group : OcrLanguagePolicy.candidates(null, true)) {
            var languages = group.split("\\+");
            assertTrue(languages.length <= 3);
            found.addAll(java.util.List.of(languages));
        }
        assertEquals(java.util.Set.of("ben", "hin", "eng", "mar", "tam", "tel", "ara", "spa"), found);
    }
    @Test public void backgroundUsesOnlyDetectedScriptAndEnglish() {
        assertEquals(java.util.List.of("ben+hin+eng"), OcrLanguagePolicy.candidates("Bengali", false));
        assertEquals(java.util.List.of("hin+mar+eng"), OcrLanguagePolicy.candidates("Devanagari", false));
        assertEquals(java.util.List.of("ben+hin+eng", "eng+spa"), OcrLanguagePolicy.candidates("unknown", false));
    }
    @Test public void primaryScriptHasPriorityAndFallbacksDoNotRepeat() {
        var candidates = OcrLanguagePolicy.candidates("Tamil", true);
        assertEquals("tam+eng", candidates.get(0));
        assertEquals(candidates.size(), new java.util.HashSet<>(candidates).size());
    }
}
