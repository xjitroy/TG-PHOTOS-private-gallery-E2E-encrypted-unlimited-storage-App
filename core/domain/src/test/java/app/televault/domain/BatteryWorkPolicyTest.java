package app.televault.domain;
import org.junit.Test;
import static org.junit.Assert.*;
public class BatteryWorkPolicyTest {
 @Test public void thresholdsSeparateNewBackupFromBacklog() { assertTrue(BatteryWorkPolicy.analysis(25,false));assertFalse(BatteryWorkPolicy.analysis(24,false));assertTrue(BatteryWorkPolicy.backup(24,false));assertTrue(BatteryWorkPolicy.backup(15,false));assertFalse(BatteryWorkPolicy.backup(14,false)); }
 @Test public void chargingResumesWorkEvenBelowThresholds() { assertTrue(BatteryWorkPolicy.backup(1,true));assertTrue(BatteryWorkPolicy.analysis(1,true)); }
 @Test public void unknownBatteryWaitsUntilItCanBeRead() { assertFalse(BatteryWorkPolicy.backup(-1,false));assertFalse(BatteryWorkPolicy.analysis(-1,false)); }
}
