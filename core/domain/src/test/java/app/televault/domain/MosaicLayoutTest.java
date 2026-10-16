package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class MosaicLayoutTest {
    @Test public void everySizePreservesEveryItemOnceWithoutOverlapsOrOutOfBounds() {
        for (int count=0; count<301; count++) {
            int next=0;
            for (var block:MosaicLayout.plan(count, i -> true)) {
                boolean[][] occupied=new boolean[block.rows()][4];
                for (var cell:block.cells()) {
                    assertEquals(next++,cell.index());
                    assertTrue(cell.column()>=0 && cell.row()>=0 && cell.column()+cell.columns()<=4 && cell.row()+cell.rows()<=block.rows());
                    for (int row=cell.row();row<cell.row()+cell.rows();row++) for (int col=cell.column();col<cell.column()+cell.columns();col++) {
                        assertFalse(occupied[row][col]); occupied[row][col]=true;
                    }
                }
            }
            assertEquals(count,next);
        }
    }
    @Test public void alternatesPortraitAndSquareEmphasisSeparatedByRegularThumbnails() {
        var blocks=MosaicLayout.plan(30,i -> true);
        var portrait=blocks.get(0).cells().get(2);
        assertEquals(2,portrait.columns()); assertEquals(3,portrait.rows());
        assertEquals(1,blocks.get(1).rows());
        var square=blocks.get(2).cells().get(0);
        assertEquals(3,square.columns()); assertEquals(3,square.rows());
        assertEquals(1,blocks.get(3).rows());
    }
    @Test public void videoOnlyAndSmallGroupsStayRegularAndDeterministic() {
        var first=MosaicLayout.plan(67,i -> false);
        assertEquals(first,MosaicLayout.plan(67,i -> false));
        assertTrue(first.stream().allMatch(b -> b.rows()==1));
        assertTrue(MosaicLayout.plan(3,i -> true).stream().allMatch(b -> b.rows()==1));
    }
    @Test public void mixedMediaNeverEnlargesVideoAndInputCannotCreateNegativePlans() {
        for (var block:MosaicLayout.plan(101,i -> i%3==0)) for (var cell:block.cells())
            if (cell.columns()>1) assertEquals(0,cell.index()%3);
        assertThrows(IllegalArgumentException.class,() -> MosaicLayout.plan(-1,i -> true));
    }
}
