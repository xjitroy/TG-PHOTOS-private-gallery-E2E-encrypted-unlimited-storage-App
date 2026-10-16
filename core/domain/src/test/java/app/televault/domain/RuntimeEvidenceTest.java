package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class RuntimeEvidenceTest {
 @Test public void detectsAnActuallyMappedKnownAgent(){assertTrue(RuntimeEvidence.injectedAgent("7abc r-xp 0000 /data/local/tmp/frida-agent-64.so"));assertTrue(RuntimeEvidence.injectedAgent("7abc r-xp 0000 /data/app/lib/libfrida-gadget.so (deleted)"));}
 @Test public void normalRomLibrariesAndPackageNamesDoNotTrigger(){assertFalse(RuntimeEvidence.injectedAgent("test-keys"));assertFalse(RuntimeEvidence.injectedAgent("org.third.party.accessibility"));assertFalse(RuntimeEvidence.injectedAgent("7abc r-xp 0000 /system/lib64/libart.so"));assertFalse(RuntimeEvidence.injectedAgent("frida-agent.so"));assertFalse(RuntimeEvidence.injectedAgent(null));}
}
