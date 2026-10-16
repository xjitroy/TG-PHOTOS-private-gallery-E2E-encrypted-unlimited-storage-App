package app.televault.domain;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class PasswordAttemptsTest {
    @Test public void retriesExpireEscalateAndSuccessfulRecoveryResetsWithoutDeletingKeys() {
        var clock = new AtomicLong(1000);
        var attempts = new PasswordAttempts(clock::get);
        for (int i = 0; i < 4; i++) { attempts.failed(); assertEquals(0, attempts.remainingMillis()); }
        attempts.failed(); assertEquals(30000, attempts.remainingMillis());
        clock.addAndGet(30001); assertEquals(0, attempts.remainingMillis());
        attempts.failed(); assertEquals(60000, attempts.remainingMillis());
        for (int i = 0; i < 20; i++) attempts.failed();
        assertEquals(300000, attempts.remainingMillis());
        attempts.succeeded(); assertEquals(0, attempts.remainingMillis());
        attempts.failed(); assertEquals(0, attempts.remainingMillis());
    }
}
