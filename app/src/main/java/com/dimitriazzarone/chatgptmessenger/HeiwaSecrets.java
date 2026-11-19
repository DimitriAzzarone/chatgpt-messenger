package com.dimitriazzarone.chatgptmessenger;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** The API key is encrypted on disk; the encryption key stays in Android Keystore. */
final class HeiwaSecrets {
    private static final String ALIAS="heiwa-groq-v1";
    private final Context context;
    HeiwaSecrets(Context context){this.context=context.getApplicationContext();}
    private javax.crypto.SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    void save(String secret) throws Exception {
        if(secret.trim().isEmpty()){context.getSharedPreferences("heiwa_secrets",0).edit().clear().commit();return;}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
        String data=Base64.encodeToString(cipher.doFinal(secret.trim().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!context.getSharedPreferences("heiwa_secrets",0).edit().putString("iv",iv).putString("key",data).commit())
            throw new java.io.IOException("Chiave non salvata");
    }
    String read() throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("heiwa_secrets",0);
        if(!prefs.contains("key"))return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("key",""),Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
