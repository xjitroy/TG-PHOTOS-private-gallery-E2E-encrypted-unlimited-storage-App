package app.televault.domain;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Bounded CSV/TSV preview; quoted separators/newlines are preserved, no formulas executed. */
public final class DelimitedPreview {
    private DelimitedPreview() {}
    public record Result(List<List<String>> rows, boolean limited) {}
    public static Result read(Reader reader, char separator) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, afterQuote = false, limited = false, pendingCr = false;
        int chars = 0;
        for (int ch; (ch = reader.read()) >= 0;) {
            if (++chars > 250000 || rows.size() >= 300) { limited = true; break; }
            char c = (char) ch;
            if (chars == 1 && c == '\ufeff') continue;
            if (pendingCr && c == '\n') { pendingCr = false; continue; }
            pendingCr = false;
            if (quoted) {
                if (c == '"') { quoted = false; afterQuote = true; }
                else if (cell.length() < 4096) cell.append(c);
                else limited = true;
                continue;
            }
            if (afterQuote && c == '"') { cell.append('"'); quoted = true; afterQuote = false; continue; }
            afterQuote = false;
            if (c == '"' && cell.isEmpty()) { quoted = true; continue; }
            if (c == separator || c == '\r' || c == '\n') {
                if (row.size() < 64) row.add(cell.toString()); else limited = true;
                cell.setLength(0);
                if (c != separator) {
                    rows.add(List.copyOf(row)); row.clear(); pendingCr = c == '\r';
                }
            } else if (cell.length() < 4096) cell.append(c);
            else limited = true;
        }
        if (rows.size() < 300 && (!row.isEmpty() || !cell.isEmpty())) {
            if (row.size() < 64) row.add(cell.toString()); else limited = true;
            rows.add(List.copyOf(row));
        }
        return new Result(List.copyOf(rows), limited);
    }
}
