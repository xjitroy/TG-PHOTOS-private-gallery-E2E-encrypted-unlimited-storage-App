package app.televault.domain;

import java.util.*;

/** A gesture owns a frozen visible order and baseline; shrinking restores the original selection. */
public final class DragSelectionRange {
    private final List<String> order;
    private final Map<String,Integer> positions = new HashMap<>();
    private final Set<String> baseline;
    private final int anchor;
    private final boolean selecting;
    public DragSelectionRange(List<String> visible, Set<String> selected, String first) {
        order = List.copyOf(visible);
        for (int i=0;i<order.size();i++) positions.put(order.get(i),i);
        Integer start=positions.get(first);
        if (start==null) throw new IllegalArgumentException("Anchor not visible");
        anchor=start; baseline=Set.copyOf(selected); selecting=!baseline.contains(first);
    }
    public Set<String> at(String endpoint) {
        Integer end=positions.get(endpoint);
        if (end==null) return baseline;
        Set<String> result=new HashSet<>(baseline);
        for (int i=Math.min(anchor,end);i<=Math.max(anchor,end);i++) {
            if (selecting) result.add(order.get(i)); else result.remove(order.get(i));
        }
        return result;
    }
}
