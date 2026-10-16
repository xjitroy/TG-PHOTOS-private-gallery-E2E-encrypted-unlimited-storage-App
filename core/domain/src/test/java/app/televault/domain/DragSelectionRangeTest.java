package app.televault.domain;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DragSelectionRangeTest {
    private final List<String> order=List.of("a","b","c","d","e","f");
    @Test public void extendsAndShrinksWithoutLosingEarlierSelections() {
        var range=new DragSelectionRange(order,Set.of("f"),"b");
        assertEquals(Set.of("b","c","d","e","f"),range.at("e"));
        assertEquals(Set.of("b","c","f"),range.at("c"));
        assertEquals(Set.of("b","f"),range.at("b"));
    }
    @Test public void reversesAcrossAnchorAndRestoresDraggedOutsideArea() {
        var range=new DragSelectionRange(order,Set.of(),"d");
        assertEquals(Set.of("b","c","d"),range.at("b"));
        assertEquals(Set.of("d","e","f"),range.at("f"));
    }
    @Test public void selectedAnchorRemovesOnlyCurrentRangeAndRestoresOnShrink() {
        var range=new DragSelectionRange(order,Set.of("a","b","c","d","f"),"c");
        assertEquals(Set.of("a","b","f"),range.at("d"));
        assertEquals(Set.of("a","b","d","f"),range.at("c"));
    }
    @Test public void laterUploadsAndHiddenIdsCannotEnterFrozenRange() {
        var visible=new ArrayList<>(order);var range=new DragSelectionRange(visible,Set.of("old"),"a");visible.add("new");
        assertEquals(Set.of("old"),range.at("new"));
        assertEquals(Set.of("old","a","b"),range.at("b"));
        assertThrows(IllegalArgumentException.class,()->new DragSelectionRange(order,Set.of(),"hidden"));
    }
}
