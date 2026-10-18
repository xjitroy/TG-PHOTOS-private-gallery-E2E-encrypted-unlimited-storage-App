package app.televault.domain;
import java.util.Comparator;
import java.util.List;
/** Explicit encrypted profile preference, not inferred face identity. */
public final class SelfIdentityChoice {
 private SelfIdentityChoice() { }
 public record Choice(String id, boolean selected, long changedAt, boolean deleted) { }
 public static String current(List<Choice> choices) {
  Choice newest = choices.stream().filter(c -> c.changedAt > 0).max(Comparator.comparingLong(Choice::changedAt).thenComparing(Choice::id)).orElse(null);
  return newest != null && newest.selected && !newest.deleted ? newest.id : null;
 }
}
