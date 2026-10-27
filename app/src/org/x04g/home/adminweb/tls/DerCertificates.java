package org.x04g.home.adminweb.tls;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.security.auth.x500.X500Principal;

/** Small, non-networking DER issuer. Keys stay in their provider; only certificates leave it. */
public final class DerCertificates {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] ECDSA_SHA256 = seq(oid("1.2.840.10045.4.3.2"));
    private DerCertificates() { }

    public static X509Certificate ca(PublicKey publicKey, PrivateKey signingKey,
                                     Date notBefore, Date notAfter) throws Exception {
        byte[] name = new X500Principal("CN=X04G Local Administration CA").getEncoded();
        byte[] extensions = seq(
            extension("2.5.29.19", true, seq(bool(true), integer(BigInteger.ZERO))),
            extension("2.5.29.15", true, bits(1, new byte[] { 6 })));
        X509Certificate cert = issue(publicKey, signingKey, name, name, extensions, notBefore, notAfter);
        cert.verify(publicKey);
        if (cert.getBasicConstraints() != 0 || !cert.getKeyUsage()[5] || !cert.getKeyUsage()[6])
            throw new IllegalStateException("CA_CONSTRAINTS");
        return cert;
    }

    public static X509Certificate leaf(PublicKey publicKey, PrivateKey caKey, X509Certificate ca,
                                       byte[] ipv4, Date notBefore, Date notAfter) throws Exception {
        if (ipv4 == null || ipv4.length != 4) throw new IllegalArgumentException("IPV4_REQUIRED");
        byte[] extensions = seq(
            extension("2.5.29.19", true, seq()),
            extension("2.5.29.15", true, bits(7, new byte[] { (byte) 0x80 })),
            extension("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))),
            extension("2.5.29.17", false, seq(tlv(0x87, ipv4.clone()))));
        X509Certificate cert = issue(publicKey, caKey, ca.getSubjectX500Principal().getEncoded(),
            new X500Principal("CN=X04G Temporary Administration").getEncoded(),
            extensions, notBefore, notAfter);
        cert.verify(ca.getPublicKey());
        if (cert.getBasicConstraints() != -1 || !cert.getKeyUsage()[0]
                || !cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"))
            throw new IllegalStateException("LEAF_CONSTRAINTS");
        return cert;
    }

    private static X509Certificate issue(PublicKey publicKey, PrivateKey signingKey,
            byte[] issuer, byte[] subject, byte[] extensions, Date before, Date after) throws Exception {
        if (publicKey == null || signingKey == null || !"EC".equals(publicKey.getAlgorithm())
                || before == null || after == null || !after.after(before))
            throw new IllegalArgumentException("CERT_INPUT");
        byte[] serial = new byte[16]; RANDOM.nextBytes(serial); serial[0] &= 0x7f;
        serial[15] |= 1;
        byte[] tbs = seq(tlv(0xa0, integer(BigInteger.valueOf(2))), integer(new BigInteger(1, serial)),
            ECDSA_SHA256, issuer, seq(time(before), time(after)), subject,
            publicKey.getEncoded(), tlv(0xa3, extensions));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(signingKey); signer.update(tbs);
        byte[] encoded = seq(tbs, ECDSA_SHA256, bits(0, signer.sign()));
        if (encoded.length > 8192) throw new IllegalArgumentException("CERT_LIMIT");
        return (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(encoded));
    }

    private static byte[] time(Date value) {
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.US);
        year.setTimeZone(TimeZone.getTimeZone("UTC"));
        int y = Integer.parseInt(year.format(value));
        SimpleDateFormat f = new SimpleDateFormat(y >= 1950 && y < 2050
            ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(y >= 1950 && y < 2050 ? 0x17 : 0x18,
            f.format(value).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] extension(String oid, boolean critical, byte[] value) {
        return critical ? seq(oid(oid), bool(true), tlv(4, value)) : seq(oid(oid), tlv(4, value));
    }
    private static byte[] seq(byte[]... values) { return tlv(0x30, concat(values)); }
    private static byte[] bool(boolean value) { return tlv(1, new byte[] { value ? (byte) 0xff : 0 }); }
    private static byte[] integer(BigInteger value) { return tlv(2, value.toByteArray()); }
    private static byte[] bits(int unused, byte[] value) {
        return tlv(3, concat(new byte[] { (byte) unused }, value));
    }
    private static byte[] oid(String value) {
        String[] parts = value.split("\\.");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        base128(b, Long.parseLong(parts[0]) * 40 + Long.parseLong(parts[1]));
        for (int i = 2; i < parts.length; i++) base128(b, Long.parseLong(parts[i]));
        return tlv(6, b.toByteArray());
    }
    private static void base128(ByteArrayOutputStream b, long value) {
        if (value < 0) throw new IllegalArgumentException("OID");
        byte[] temp = new byte[10]; int i = temp.length;
        temp[--i] = (byte) (value & 127); value >>>= 7;
        while (value != 0) { temp[--i] = (byte) ((value & 127) | 128); value >>>= 7; }
        b.write(temp, i, temp.length - i);
    }
    private static byte[] tlv(int tag, byte[] data) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(data.length + 5); b.write(tag);
        if (data.length < 128) b.write(data.length);
        else {
            int n = 0, v = data.length; while (v != 0) { n++; v >>>= 8; }
            b.write(0x80 | n);
            for (int i = n - 1; i >= 0; i--) b.write((data.length >>> (8 * i)) & 255);
        }
        b.write(data, 0, data.length); return b.toByteArray();
    }
    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] value : values) b.write(value, 0, value.length);
        return b.toByteArray();
    }
}
