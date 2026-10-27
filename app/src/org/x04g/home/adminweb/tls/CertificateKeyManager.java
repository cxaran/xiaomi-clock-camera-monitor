package org.x04g.home.adminweb.tls;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/** The real certificate chain with an opaque provider key, without exporting/reimporting private bytes. */
public final class CertificateKeyManager extends X509ExtendedKeyManager {
    private final PrivateKey key;
    private final X509Certificate[] chain;
    public CertificateKeyManager(PrivateKey key, X509Certificate[] chain) {
        if (key == null || chain == null || chain.length != 2) throw new IllegalArgumentException();
        this.key = key; this.chain = chain.clone();
    }
    @Override public String chooseServerAlias(String type, Principal[] issuers, Socket socket) {
        return "EC".equals(type) ? "x04g" : null;
    }
    @Override public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine engine) {
        return "EC".equals(type) ? "x04g" : null;
    }
    @Override public String[] getServerAliases(String type, Principal[] issuers) {
        return "EC".equals(type) ? new String[] { "x04g" } : null;
    }
    @Override public X509Certificate[] getCertificateChain(String alias) {
        return "x04g".equals(alias) ? chain.clone() : null;
    }
    @Override public PrivateKey getPrivateKey(String alias) { return "x04g".equals(alias) ? key : null; }
    @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
    @Override public String chooseClientAlias(String[] types, Principal[] issuers, Socket socket) { return null; }
}
