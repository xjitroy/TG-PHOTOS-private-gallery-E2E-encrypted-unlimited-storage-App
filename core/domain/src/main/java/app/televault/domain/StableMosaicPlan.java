package app.televault.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/** Keep painted blocks intact when restore/new captures prepend or append to a timeline. */
public final class StableMosaicPlan {
    private StableMosaicPlan() { }
    public record Snapshot(List<String> ids, List<MosaicLayout.Block> blocks) { }
    public static Snapshot update(Snapshot previous, List<String> ids, IntPredicate photo) {
        var positions = new HashMap<String, Integer>();
        for (int i = 0; i < ids.size(); i++) {
            if (positions.put(ids.get(i), i) != null) throw new IllegalArgumentException("Duplicate media id");
        }
        var reusable = new TreeMap<Integer, MosaicLayout.Block>();
        if (previous != null) {
            for (var block : previous.blocks()) {
                int oldStart = block.cells().get(0).index();
                Integer start = positions.get(previous.ids().get(oldStart));
                if (start == null || start + block.cells().size() > ids.size()) continue;
                boolean matches = true;
                for (var cell : block.cells()) {
                    int index = start + cell.index() - oldStart;
                    if (!ids.get(index).equals(previous.ids().get(cell.index())) || (cell.columns() > 1 && !photo.test(index))) { matches = false; break; }
                }
                if (matches) reusable.put(start, translated(block, start - oldStart));
            }
        }
        var result = new ArrayList<MosaicLayout.Block>();
        int index = 0;
        while (index < ids.size()) {
            var block = reusable.get(index);
            if (block != null) {
                // Fill empty columns in an old partial row without moving any painted cell.
                Integer next = reusable.higherKey(index);
                int limit = next == null ? ids.size() : next;
                if (block.rows() == 1 && block.cells().size() < 4 && block.cells().stream().allMatch(c -> c.columns() == 1 && c.rows() == 1)) {
                    var cells = new ArrayList<>(block.cells());
                    while (cells.size() < 4 && index + cells.size() < limit) {
                        int column = cells.size();
                        cells.add(new MosaicLayout.Cell(index + column, column, 0, 1, 1));
                    }
                    block = new MosaicLayout.Block(1, List.copyOf(cells));
                }
                result.add(block);
                index += block.cells().size();
            } else {
                Integer next = reusable.ceilingKey(index);
                int end = next == null ? ids.size() : next;
                final int offset = index;
                for (var fresh : MosaicLayout.plan(end - index, i -> photo.test(offset + i))) result.add(translated(fresh, offset));
                index = end;
            }
        }
        return new Snapshot(List.copyOf(ids), List.copyOf(result));
    }
    private static MosaicLayout.Block translated(MosaicLayout.Block block, int offset) {
        var cells = new ArrayList<MosaicLayout.Cell>(block.cells().size());
        for (var cell : block.cells()) cells.add(new MosaicLayout.Cell(cell.index() + offset, cell.column(), cell.row(), cell.columns(), cell.rows()));
        return new MosaicLayout.Block(block.rows(), List.copyOf(cells));
    }
}
