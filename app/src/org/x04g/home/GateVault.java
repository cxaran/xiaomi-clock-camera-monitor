package org.x04g.home;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;
import org.x04g.home.gate.GateProfile;
import org.x04g.home.gate.LearnedRfPulse;

/** Separate, private gate credentials. No values are exported or embedded in the APK. */
public final class GateVault {
    private static final String ALIAS="org.x04g.home.gate.v1";
    private static final byte[] AAD="x04g.home.gate.v1".getBytes(StandardCharsets.US_ASCII);
    private final AtomicFile file;
    public GateVault(Context c)throws IOException{File dir=new File(c.getNoBackupFilesDir(),"x04g-home");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("gate_store_unavailable");file=new AtomicFile(new File(dir,"gate.v1.enc"));}
    private static synchronized SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(!store.containsAlias(ALIAS)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());g.generateKey();}return(SecretKey)store.getKey(ALIAS,null);}
    public synchronized GateProfile load()throws Exception{if(!file.getBaseFile().exists())return null;byte[] envelope=file.readFully();if(envelope.length<29||envelope.length>131072)throw new IOException("gate_store_invalid");byte[] iv=Arrays.copyOfRange(envelope,0,12),plain=null;try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));c.updateAAD(AAD);plain=c.doFinal(envelope,12,envelope.length-12);return profile(new JSONObject(new String(plain,StandardCharsets.UTF_8)));}finally{Arrays.fill(envelope,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}}
    /** Explicit local provisioning only. Exceptions carry fixed identifiers, never JSON content. */
    public synchronized void provision(String json)throws Exception{byte[] plain=null,encrypted=null;FileOutputStream out=null;try{if(json==null||json.length()>98304)throw new IOException("gate_provision_invalid");JSONObject o=new JSONObject(json);try(GateProfile verified=profile(o)){}plain=o.toString().getBytes(StandardCharsets.UTF_8);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(AAD);encrypted=c.doFinal(plain);out=file.startWrite();out.write(c.getIV());out.write(encrypted);file.finishWrite(out);out=null;}catch(Exception e){if(out!=null)file.failWrite(out);throw new IOException("gate_provision_failed");}finally{if(plain!=null)Arrays.fill(plain,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}}
    private static GateProfile profile(JSONObject o)throws Exception{if(o.length()<3||o.length()>6||o.optInt("schema_version",1)!=1)throw new IOException("gate_profile_invalid");String id=o.getString("device_id"),host=o.getString("host"),secret=o.getString("local_key");byte[] raw=secret.getBytes(StandardCharsets.UTF_8);try{LearnedRfPulse pulse=o.has("rf_code")&&!o.isNull("rf_code")?LearnedRfPulse.fromCapturedBase64(o.getString("rf_code"),o.optBoolean("physical_replay_verified",false)):null;return new GateProfile(host,id,raw,pulse);}finally{Arrays.fill(raw,(byte)0);}}
}
