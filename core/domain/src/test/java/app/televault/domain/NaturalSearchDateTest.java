package app.televault.domain;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;
public class NaturalSearchDateTest {
 @Test public void threeLanguagesUseCalendarYearBeforeSemanticSearch() {
  for(String q:new String[]{"last year beach photos","গত বছরের সমুদ্রের ছবিগুলো","पिछले साल समुद्र की तस्वीरें"}) {
   var plan=UniversalMediaSearch.parseNatural(q,"","",LocalDate.of(2026,10,21));
   assertTrue(plan.valid());assertEquals(LocalDate.of(2025,1,1),plan.start());assertEquals(LocalDate.of(2025,12,31),plan.end());
  }
 }
 @Test public void previousMonthHandlesYearBoundaryAndLeapDay() {
  var p=UniversalMediaSearch.parseNatural("last month flowers","","",LocalDate.of(2024,3,1));assertEquals(LocalDate.of(2024,2,29),p.end());
  p=UniversalMediaSearch.parseNatural("গত মাসের ছবি","","",LocalDate.of(2026,1,1));assertEquals(LocalDate.of(2025,12,1),p.start());
 }
 @Test public void explicitDatesRemainAuthoritativeAndConflictsDoNotMatchEverything() {
  var p=UniversalMediaSearch.parseNatural("last year beach","1/1/2023","31/1/2023",LocalDate.of(2026,10,21));assertEquals(LocalDate.of(2023,1,1),p.start());
  assertFalse(UniversalMediaSearch.parseNatural("last year this month beach","","",LocalDate.of(2026,10,21)).valid());
 }
}
