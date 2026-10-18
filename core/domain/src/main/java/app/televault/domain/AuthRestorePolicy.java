package app.televault.domain;

/** A cold start is not evidence that Telegram requires a new login. */
public final class AuthRestorePolicy {
    private AuthRestorePolicy() { }
    public enum Screen { RESTORING, WELCOME, LOGIN, CONTENT }
    public static Screen screen(AuthProtocol.State state, boolean previouslyAuthorized, boolean started) {
        if (state == AuthProtocol.State.READY) return Screen.CONTENT;
        if (state == AuthProtocol.State.STARTING || state == AuthProtocol.State.PARAMETERS
                || state == AuthProtocol.State.CLOSING || state == AuthProtocol.State.LOGGING_OUT) return Screen.RESTORING;
        if (state == AuthProtocol.State.PHONE && !previouslyAuthorized && !started) return Screen.WELCOME;
        return Screen.LOGIN;
    }
}
