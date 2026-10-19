package app.televault.domain;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.Test;

public class StableMosaicPlanTest {
    private static List<String> ids(int count) { return IntStream.range(0, count).mapToObj(i -> "photo" + i).toList(); }
    private static StableMosaicPlan.Snapshot plan(StableMosaicPlan.Snapshot previous, List<String> ids) {
        return StableMosaicPlan.update(previous, ids, i -> true);
    }
    private static List<String> geometry(StableMosaicPlan.Snapshot snapshot, String id) {
        for (var block : snapshot.blocks()) for (var cell : block.cells()) {
            if (snapshot.ids().get(cell.index()).equals(id)) return List.of("" + cell.column(), "" + cell.row(), "" + cell.columns(), "" + cell.rows(), "" + block.rows());
        }
        throw new AssertionError("Missing media");
    }
    @Test public void aRestoredPartialRowDoesNotTurnIntoATallTileWhenSevenItemsArrive() {
        var initial = plan(null, ids(4));
        var next = plan(initial, ids(7));
        for (var id : initial.ids()) assertEquals(geometry(initial, id), geometry(next, id));
        assertEquals(7, next.blocks().stream().mapToInt(b -> b.cells().size()).sum());
    }
    @Test public void newCameraPhotoDoesNotRegroupEveryExistingMosaicBlock() {
        var initial = plan(null, ids(22));
        var incoming = new ArrayList<>(List.of("new-camera-photo")); incoming.addAll(initial.ids());
        var next = plan(initial, incoming);
        for (var id : initial.ids()) assertEquals(geometry(initial, id), geometry(next, id));
    }
    @Test public void equalSnapshotKeepsTheOriginalMosaicAndLargePhotos() {
        var first = plan(null, ids(22));
        assertEquals(MosaicLayout.plan(22, i -> true), first.blocks());
        assertEquals(first, plan(first, first.ids()));
        assertTrue(first.blocks().stream().flatMap(b -> b.cells().stream()).anyMatch(c -> c.columns() > 1));
    }
    @Test public void removingAnItemOnlyReplansItsBrokenBlock() {
        var initial = plan(null, ids(22));
        var incoming = new ArrayList<>(initial.ids()); incoming.remove("photo2");
        var next = plan(initial, incoming);
        for (int i = 7; i < 22; i++) assertEquals(geometry(initial, "photo" + i), geometry(next, "photo" + i));
        var painted = next.blocks().stream().flatMap(b -> b.cells().stream()).map(c -> next.ids().get(c.index())).toList();
        assertEquals(incoming, painted);
    }
    @Test public void repeatedIncrementalRestoreNeverLosesOrDuplicatesAnEntry() {
        StableMosaicPlan.Snapshot previous = null;
        for (int count = 1; count <= 250; count++) {
            previous = plan(previous, ids(count));
            var current = previous;
            var painted = current.blocks().stream().flatMap(b -> b.cells().stream()).map(c -> current.ids().get(c.index())).toList();
            assertEquals(ids(count), painted);
            for (var block : current.blocks()) for (var cell : block.cells()) {
                assertTrue(cell.column() >= 0 && cell.column() + cell.columns() <= 4);
                assertTrue(cell.row() >= 0 && cell.row() + cell.rows() <= block.rows());
            }
        }
    }
    @Test public void aVideoCannotInheritAnOldLargePhotoSlot() {
        var initial = plan(null, ids(7));
        var next = StableMosaicPlan.update(initial, initial.ids(), i -> false);
        for (var block : next.blocks()) for (var cell : block.cells()) assertEquals(1, cell.columns());
    }
}
