package app.televault.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** Deterministic, chronology-preserving four-column blocks; no bitmap analysis or random reflow. */
public final class MosaicLayout {
    private MosaicLayout() {}
    public record Cell(int index, int column, int row, int columns, int rows) {}
    public record Block(int rows, List<Cell> cells) {}
    public static List<Block> plan(int count, IntPredicate photo) {
        if (count < 0) throw new IllegalArgumentException("Negative item count");
        List<Block> result = new ArrayList<>();
        int index = 0;
        boolean right = true, separator = false;
        while (index < count) {
            int left = count - index;
            if (!separator && right && left >= 7 && photo.test(index + 2)) {
                result.add(new Block(3, List.of(new Cell(index, 0, 0, 1, 1), new Cell(index+1, 1, 0, 1, 1), new Cell(index+2, 2, 0, 2, 3), new Cell(index+3, 0, 1, 1, 1), new Cell(index+4, 1, 1, 1, 1), new Cell(index+5, 0, 2, 1, 1), new Cell(index+6, 1, 2, 1, 1))));
                index += 7; right = false; separator = true;
            } else if (!separator && !right && left >= 4 && photo.test(index)) {
                result.add(new Block(3, List.of(new Cell(index, 0, 0, 3, 3), new Cell(index+1, 3, 0, 1, 1), new Cell(index+2, 3, 1, 1, 1), new Cell(index+3, 3, 2, 1, 1))));
                index += 4; right = true; separator = true;
            } else {
                List<Cell> cells = new ArrayList<>();
                for (int column=0; column<Math.min(4, left); column++) cells.add(new Cell(index+column, column, 0, 1, 1));
                index += cells.size(); result.add(new Block(1, List.copyOf(cells))); separator = false;
            }
        }
        return List.copyOf(result);
    }
}
