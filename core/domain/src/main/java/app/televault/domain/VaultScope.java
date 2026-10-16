package app.televault.domain;

/** Prevent a background key from being borrowed or its associated AAD scope from changing. */
public final class VaultScope {
    private VaultScope() {}
    public static boolean workerMayUse(long account, String vault, long requestedAccount, String requestedVault) {
        return requestedAccount > 0 && !requestedVault.isEmpty()
            && (account == 0 || account == requestedAccount)
            && (vault.isEmpty() || vault.equals(requestedVault));
    }
    public static boolean maySelect(long account, String vault, long requestedAccount, String requestedVault, int activeKeys) {
        return activeKeys == 0 || activeKeys > 0 && account == requestedAccount && vault.equals(requestedVault);
    }
}
