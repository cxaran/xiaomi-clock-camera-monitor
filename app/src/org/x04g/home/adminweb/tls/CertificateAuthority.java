package org.x04g.home.adminweb.tls;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;

/** Blocking worker-only PKI. Persistent CA private material is never exported from AndroidKeyStore. */
public final class CertificateAuthority {
    private static final String CA_ALIAS = "org.x04g.home.adminweb.ca.v1";
    private static final String LEAF_ALIAS = "org.x04g.home.adminweb.leaf.v1";
    private static final Object OWNER_LOCK = new Object();
    private static Object leafOwner;
    private final File directory;

    public CertificateAuthority(Context context) {
        directory = new File(context.getNoBackupFilesDir(), "x04g-adminweb");
    }
    public static final class PublicCa {
        public final byte[] certificateDer;
        public final String sha256;
        private PublicCa(byte[] der) throws Exception {
            certificateDer = der.clone(); sha256 = hex(MessageDigest.getInstance("SHA-256").digest(der));
        }
    }
    public static final class Identity implements AutoCloseable {
        public final SSLContext sslContext;
        public final PublicCa ca;
        private final Object token;
        private boolean closed;
        private Identity(SSLContext context, PublicCa ca, Object token) {
            sslContext = context; this.ca = ca; this.token = token;
        }
        @Override public void close() {
            synchronized (OWNER_LOCK) {
                if (closed) return; closed = true;
                if (leafOwner != token) return;
                try { store().deleteEntry(LEAF_ALIAS); } catch (Exception ignored) { }
                leafOwner = null;
            }
        }
    }
    public PublicCa publicCa() throws Exception {
        synchronized (OWNER_LOCK) { return new PublicCa(ensureCa().getEncoded()); }
    }
    public Identity identity(byte[] ipv4) throws Exception {
        synchronized (OWNER_LOCK) {
            if (leafOwner != null) throw new IllegalStateException("IDENTITY_BUSY");
            X509Certificate ca = ensureCa(); KeyStore keys = store();
            // Only this module's stale ephemeral leaf is replaced, never arbitrary aliases or the CA.
            if (keys.containsAlias(LEAF_ALIAS)) keys.deleteEntry(LEAF_ALIAS);
            Object token = new Object(); leafOwner = token;
            try {
                KeyPair pair = generate(LEAF_ALIAS, true);
                long now = System.currentTimeMillis();
                Date end = new Date(Math.min(now + 86400000L, ca.getNotAfter().getTime()));
                X509Certificate leaf = DerCertificates.leaf(pair.getPublic(),
                    (PrivateKey) keys.getKey(CA_ALIAS, null), ca, ipv4,
                    new Date(Math.max(now - 300000L, ca.getNotBefore().getTime())), end);
                final PrivateKey privateKey = (PrivateKey) keys.getKey(LEAF_ALIAS, null);
                final X509Certificate[] chain = new X509Certificate[] { leaf, ca };
                SSLContext tls = SSLContext.getInstance("TLSv1.2");
                tls.init(new KeyManager[] { new CertificateKeyManager(privateKey, chain) }, null, null);
                return new Identity(tls, new PublicCa(ca.getEncoded()), token);
            } catch (Exception e) {
                try { keys.deleteEntry(LEAF_ALIAS); } catch (Exception ignored) { }
                leafOwner = null; throw e;
            }
        }
    }

    private X509Certificate ensureCa() throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("CA_STORAGE");
        if (!directory.setReadable(false, false) || !directory.setWritable(false, false)
                || !directory.setExecutable(false, false) || !directory.setReadable(true, true)
                || !directory.setWritable(true, true) || !directory.setExecutable(true, true))
            throw new java.io.IOException("CA_PERMISSIONS");
        File certFile = new File(directory, "ca.der"); AtomicFile atomic = new AtomicFile(certFile);
        KeyStore keys = store(); boolean hasKey = keys.containsAlias(CA_ALIAS);
        // AtomicFile.openRead recovers an interrupted public certificate commit when a backup exists.
        boolean hasCert = certFile.exists() || new File(certFile.getPath() + ".bak").exists();
        if (hasKey != hasCert) throw new java.io.IOException("CA_INCOMPLETE_NO_SILENT_ROTATION");
        if (!hasKey) {
            KeyPair pair = generate(CA_ALIAS, false); long now = System.currentTimeMillis();
            X509Certificate cert = DerCertificates.ca(pair.getPublic(),
                (PrivateKey) keys.getKey(CA_ALIAS, null), new Date(now - 86400000L),
                new Date(now + 315360000000L));
            FileOutputStream out = null;
            try {
                out = atomic.startWrite(); out.write(cert.getEncoded()); out.getFD().sync();
                atomic.finishWrite(out); out = null;
            } finally { if (out != null) atomic.failWrite(out); }
            if (!certFile.setReadable(false, false) || !certFile.setWritable(false, false)
                    || !certFile.setReadable(true, true) || !certFile.setWritable(true, true))
                throw new java.io.IOException("CA_PERMISSIONS");
        }
        byte[] der;
        try (FileInputStream in = atomic.openRead()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024]; int count;
            while ((count = in.read(chunk)) != -1) {
                if (b.size() + count > 8192) throw new java.io.IOException("CA_LIMIT");
                b.write(chunk, 0, count);
            }
            der = b.toByteArray();
        }
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(der));
        cert.checkValidity(); cert.verify(cert.getPublicKey());
        boolean[] usage = cert.getKeyUsage();
        if (cert.getBasicConstraints() != 0 || usage == null || usage.length <= 6 || !usage[5] || !usage[6]
                || !Arrays.equals(cert.getPublicKey().getEncoded(), keys.getCertificate(CA_ALIAS).getPublicKey().getEncoded()))
            throw new java.security.cert.CertificateException("CA_MISMATCH");
        return cert;
    }
    private static KeyStore store() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); return keys;
    }
    private static KeyPair generate(String alias, boolean tlsLeaf) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            // Conscrypt's opaque EC-key upcall signs its already-computed TLS hash using
            // NONEwithECDSA. Only the ephemeral TLS leaf authorizes that delegated operation;
            // CA certificate issuance continues to authorize SHA-256 exclusively.
            .setDigests(tlsLeaf ? KeyAuthorizations.leafDigests() : KeyAuthorizations.caDigests())
            .setUserAuthenticationRequired(false).build());
        return generator.generateKeyPair();
    }
    private static String hex(byte[] bytes) {
        char[] hex = "0123456789ABCDEF".toCharArray(); StringBuilder b = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i != 0) b.append(':'); b.append(hex[(bytes[i] >>> 4) & 15]).append(hex[bytes[i] & 15]);
        }
        return b.toString();
    }
}
