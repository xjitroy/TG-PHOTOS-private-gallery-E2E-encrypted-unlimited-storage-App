package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferRateTest {
    @Test public void waitsForMeasuredTrafficAndHandlesStallsAndPause() {
        TransferRate rate = new TransferRate();
        rate.sample(1000, 0, true);
        assertEquals(-1, rate.secondsRemaining(10000, true));
        rate.sample(2000, 1000, true);
        assertEquals(1000, rate.bytesPerSecond());
        assertEquals(9, rate.secondsRemaining(9000, true));
        assertEquals(-1, rate.secondsRemaining(9000, false));
        rate.sample(8000, 1000, true);
        assertEquals(-1, rate.secondsRemaining(9000, true));
        rate.sample(9000, 2000, true);
        rate.sample(10000, 2000, false);
        assertEquals(0, rate.bytesPerSecond());
    }
    @Test public void retriesAndClockRegressionDoNotInventThroughput() {
        TransferRate rate = new TransferRate();
        rate.sample(0, 2000, true);
        rate.sample(1000, 3000, true);
        rate.sample(2000, 0, true);
        assertEquals(-1, rate.secondsRemaining(10000, true));
        rate.sample(1000, 1000, true);
        assertEquals(0, rate.bytesPerSecond());
        assertEquals(-1, rate.secondsRemaining(0, true));
    }
}
