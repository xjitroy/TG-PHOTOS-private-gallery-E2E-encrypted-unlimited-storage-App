package app.televault.domain;

import java.io.StringReader;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DelimitedPreviewTest {
    @Test public void quotedMultilineAndEscapedCellsArePreserved() throws Exception {
        var data = DelimitedPreview.read(new StringReader("\ufeffName,Value\r\n\"a,b\",\"line1\nline2\"\r\n\"say \"\"hello\"\"\",=1+2"), ',');
        assertEquals(List.of(List.of("Name","Value"), List.of("a,b","line1\nline2"), List.of("say \"hello\"","=1+2")), data.rows());
        assertFalse(data.limited());
    }
    @Test public void rowsColumnsAndCellBytesAreBounded() throws Exception {
        var rows = DelimitedPreview.read(new StringReader("a\tb\n".repeat(500)), '\t');
        assertEquals(300, rows.rows().size()); assertTrue(rows.limited());
        var columns = DelimitedPreview.read(new StringReader("x,".repeat(100)), ',');
        assertEquals(64, columns.rows().get(0).size()); assertTrue(columns.limited());
        var longCell = DelimitedPreview.read(new StringReader("a".repeat(10000)), ',');
        assertEquals(4096, longCell.rows().get(0).get(0).length()); assertTrue(longCell.limited());
    }
}
