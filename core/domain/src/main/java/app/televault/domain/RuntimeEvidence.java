package app.televault.domain;

/** A known injected agent must be a mapped library, not an installed package or ROM tag. */
public final class RuntimeEvidence {
    private RuntimeEvidence() {}
    public static boolean injectedAgent(String mapping) {
        if (mapping == null || !mapping.contains("/")) return false;
        String basename = mapping.substring(mapping.lastIndexOf('/') + 1).split(" ",2)[0];
        return basename.matches("(?i)(?:lib)?frida-(?:gadget|agent)[^/]*\\.so");
    }
}
