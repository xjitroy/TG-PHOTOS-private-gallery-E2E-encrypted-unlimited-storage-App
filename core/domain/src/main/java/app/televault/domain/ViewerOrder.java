package app.televault.domain;
import java.util.*;
import java.util.function.Function;
public final class ViewerOrder {
    private ViewerOrder() {}
    public static <T> T adjacent(List<T> entries, int index, int direction) {
        if (index < 0 || index >= entries.size() || (direction != -1 && direction != 1)) return null;
        int next = index + direction;
        return next < 0 || next >= entries.size() ? null : entries.get(next);
    }
    public static <T> List<T> ensureSelected(List<T> entries,T selected,Function<T,String> identity) {
        LinkedHashMap<String,T> ordered=new LinkedHashMap<>();
        String selectedId=identity.apply(selected);
        if(entries.stream().noneMatch(value->identity.apply(value).equals(selectedId))) ordered.put(selectedId,selected);
        for(T value:entries) ordered.putIfAbsent(identity.apply(value),value);
        return Collections.unmodifiableList(new ArrayList<>(ordered.values()));
    }
}
