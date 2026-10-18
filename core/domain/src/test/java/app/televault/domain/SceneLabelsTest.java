package app.televault.domain;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneLabelsTest {
    private List<String> labels() {
        var labels = new ArrayList<>(Collections.nCopies(1000, "n00000000 object"));
        labels.set(10, "n09428293 seashore, coast, seacoast, sea-coast");
        return labels;
    }
    @Test public void onlyConfidentFiniteOutputAddsAConcreteSubject() {
        float[] logits = new float[1000]; logits[10] = 10;
        assertTrue(SceneLabels.select(logits, labels()).contains("Beach days"));
        Arrays.fill(logits, 0);
        assertEquals("", SceneLabels.select(logits, labels()));
        logits[0] = Float.NaN;
        assertEquals("", SceneLabels.select(logits, labels()));
        assertEquals("", SceneLabels.select(new float[3], labels()));
    }
    @Test public void closeRivalsDoNotTurnAnAmbiguousImageIntoASearchFact() {
        float[] logits = new float[1000]; logits[10] = 15; logits[20] = 15;
        assertEquals("", SceneLabels.select(logits, labels()));
        logits[20] = 1;
        assertFalse(SceneLabels.select(logits, labels()).isBlank());
    }
    @Test public void themesNeverInventIdentityRelationshipsOrAnEvent() {
        assertEquals("", SceneLabels.theme("groom, bridegroom"));
        assertEquals("", SceneLabels.theme("academic gown"));
        assertEquals("Food moments", SceneLabels.theme("pizza, pizza pie"));
        assertEquals("Great outdoors", SceneLabels.theme("mountain, mount"));
    }
}
