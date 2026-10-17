package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class TextTopicsTest {
 @Test public void multilingualRestaurantClues() { assertTrue(TextTopics.tags("আজ রেস্টুরেন্ট থেকে").contains("restaurant"));assertTrue(TextTopics.tags("مطعم").contains("restaurant"));assertTrue(TextTopics.tags("Restaurante Sol").contains("রেস্টুরেন্ট")); }
 @Test public void avoidsSubstringHallucinations() { assertEquals("",TextTopics.tags("Barcelona landscape photograph"));assertEquals("",TextTopics.tags("")); }
 @Test public void printedBrandCanSuggestFood() { assertTrue(TextTopics.tags("STARBUCKS coffee").contains("food")); }
 @Test public void receiptRemainsDistinct() { assertTrue(TextTopics.tags("Invoice 1234").contains("receipt"));assertFalse(TextTopics.tags("Invoice 1234").contains("restaurant")); }
}
