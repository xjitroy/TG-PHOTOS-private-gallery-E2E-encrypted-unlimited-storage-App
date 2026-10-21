package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class AiAnalysisSourceTest {
 private final String revision="1234567890123456789012345678901234567890";
 @Test public void unchangedLargeLocalVideoCanReuseDerivedAnalysis() { assertTrue(AiAnalysisSource.same("content://media/18","18:10000000000:1234","embedding",revision,"content://media/18","18:10000000000:1234","embedding",revision)); }
 @Test public void renamedReplacedOrModifiedSourcesCannotReuseAnOldRecord() {
  assertFalse(AiAnalysisSource.same("content://media/19","stamp","embedding",revision,"content://media/18","stamp","embedding",revision));
  assertFalse(AiAnalysisSource.same("id","new size or time","embedding",revision,"id","old size or time","embedding",revision));
 }
 @Test public void changingTheSelectedModelOrItsRevisionRequiresFreshAnalysis() {
  assertFalse(AiAnalysisSource.same("id","stamp","small",revision,"id","stamp","embedding",revision));
  assertFalse(AiAnalysisSource.same("id","stamp","embedding",revision,"id","stamp","embedding","a".repeat(40)));
 }
 @Test public void missingSourceProofIsNeverTreatedAsCompleted() {
  assertFalse(AiAnalysisSource.same("id","","embedding",revision,"id","","embedding",revision));
  assertFalse(AiAnalysisSource.same("id","stamp","unknown",revision,"id","stamp","unknown",revision));
 }
}
