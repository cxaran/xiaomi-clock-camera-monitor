package org.x04g.home.adminweb.tls;

/** KeyGenParameterSpec digest names. NONE allows the TLS provider's prehashed EC signing upcall. */
public final class KeyAuthorizations {
    private KeyAuthorizations() { }
    public static String[] caDigests() { return new String[] { "SHA-256" }; }
    public static String[] leafDigests() { return new String[] { "SHA-256", "NONE" }; }
}
