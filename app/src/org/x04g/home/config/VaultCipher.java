package org.x04g.home.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Authenticated, versioned whole-config envelope; no plaintext credential file. */
final class VaultCipher {
    interface Keys { SecretKey get(boolean create) throws ConfigException; }
    static final class Opened {
        final long revision;final byte[] plaintext;
        Opened(long revision,byte[] plaintext){this.revision=revision;this.plaintext=plaintext;}
    }
    private static final byte[] MAGIC={'X','0','4','G','C','F','G','1'};
    private final Keys keys;
    VaultCipher(Keys keys){this.keys=keys;}
    private byte[] aad(long revision){return ("org.x04g.home.config|schema=1|revision="+revision).getBytes(StandardCharsets.US_ASCII);}
    byte[] seal(long revision,byte[] plaintext) throws ConfigException {
        try {
            if(revision<=0||plaintext.length>ConfigRules.MAX_FILE_BYTES-128)throw new IOException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get(true));
            cipher.updateAAD(aad(revision));byte[] encrypted=cipher.doFinal(plaintext),iv=cipher.getIV();
            if(iv.length!=12)throw new GeneralSecurityException();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.write(MAGIC);out.writeInt(1);out.writeLong(revision);out.writeByte(iv.length);out.write(iv);out.writeInt(encrypted.length);out.write(encrypted);out.flush();
            return bytes.toByteArray();
        } catch(ConfigException e){throw e;}
        catch(Exception e){throw new ConfigException(ConfigException.Code.SECRET_UNAVAILABLE);}
    }
    Opened open(byte[] envelope) throws ConfigException {
        try {
            if(envelope.length>ConfigRules.MAX_FILE_BYTES)throw new IOException();
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(envelope));byte[] magic=new byte[MAGIC.length];in.readFully(magic);
            if(!Arrays.equals(magic,MAGIC)||in.readInt()!=1)throw new IOException();
            long revision=in.readLong();int ivSize=in.readUnsignedByte();if(revision<=0||ivSize!=12)throw new IOException();
            byte[] iv=new byte[ivSize];in.readFully(iv);int size=in.readInt();
            if(size<16||size>ConfigRules.MAX_FILE_BYTES||size!=in.available())throw new IOException();
            byte[] encrypted=new byte[size];in.readFully(encrypted);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,keys.get(false),new GCMParameterSpec(128,iv));
            cipher.updateAAD(aad(revision));return new Opened(revision,cipher.doFinal(encrypted));
        } catch(ConfigException e){throw e;}
        catch(AEADBadTagException e){throw new ConfigException(ConfigException.Code.CORRUPT);}
        catch(GeneralSecurityException e){throw new ConfigException(ConfigException.Code.SECRET_UNAVAILABLE);}
        catch(Exception e){throw new ConfigException(ConfigException.Code.CORRUPT);}
    }
}
