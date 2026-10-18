package app.televault.domain;

import org.junit.Test;
import static org.junit.Assert.*;
import static app.televault.domain.AuthRestorePolicy.Screen.*;
import static app.televault.domain.AuthProtocol.State.*;

public class AuthRestorePolicyTest {
    @Test public void coldStartWaitsForRealAuthorizationBeforeOfferingNewLogin() {
        for (var state : new AuthProtocol.State[] {STARTING, PARAMETERS, CLOSING}) {
            assertEquals(RESTORING, AuthRestorePolicy.screen(state, false, false));
            assertEquals(RESTORING, AuthRestorePolicy.screen(state, true, false));
        }
    }
    @Test public void previousSessionNeverReturnsToFirstInstallWelcome() {
        assertEquals(LOGIN, AuthRestorePolicy.screen(PHONE, true, false));
        assertEquals(WELCOME, AuthRestorePolicy.screen(PHONE, false, false));
        assertEquals(LOGIN, AuthRestorePolicy.screen(PHONE, false, true));
    }
    @Test public void persistedSessionNeverBypassesTelegramAuthorization() {
        for (var state : new AuthProtocol.State[] {PHONE, CODE, PASSWORD, CLOSED, SETUP_REQUIRED})
            assertEquals(LOGIN, AuthRestorePolicy.screen(state, true, false));
        assertEquals(CONTENT, AuthRestorePolicy.screen(READY, true, false));
    }
    @Test public void resumedCodeChallengeDoesNotRestartOnboardingOrSendNewCode() {
        assertEquals(LOGIN, AuthRestorePolicy.screen(CODE, false, false));
        assertEquals(LOGIN, AuthRestorePolicy.screen(PASSWORD, false, false));
        assertEquals(LOGIN, AuthRestorePolicy.screen(EMAIL_CODE, false, false));
    }
}
