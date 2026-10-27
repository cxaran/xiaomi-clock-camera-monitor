package org.x04g.home.config;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Key belongs to the calling Android UID; this class never deletes or silently replaces it. */
final class AndroidVaultKeys implements VaultCipher.Keys {
    private static final String ALIAS="org.x04g.home.cameras.aesgcm.v1";
    @Override public synchronized SecretKey get(boolean create) throws ConfigException {
        try {
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            if(store.containsAlias(ALIAS)) {
                java.security.Key key=store.getKey(ALIAS,null);
                if(!(key instanceof SecretKey))throw new Exception();return (SecretKey)key;
            }
            if(!create)throw new ConfigException(ConfigException.Code.SECRET_UNAVAILABLE);
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build());
            return generator.generateKey();
        } catch(ConfigException e){throw e;}
        catch(Exception e){throw new ConfigException(ConfigException.Code.SECRET_UNAVAILABLE);}
    }
}
