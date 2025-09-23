package com.frostr.igloo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class SecureStorage {
    private static final String TAG = "SecureStorage";
    private static final String KEYSTORE_ALIAS = "IglooKeystoreAlias";
    private static final String PREFS_NAME = "igloo_secure_prefs";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    private Context context;
    private KeyStore keyStore;

    public SecureStorage(Context context) {
        this.context = context;
        initKeyStore();
    }

    private void initKeyStore() {
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            // Generate key if it doesn't exist
            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                generateKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize KeyStore", e);
        }
    }

    private void generateKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build();

            keyGenerator.init(keyGenParameterSpec);
            keyGenerator.generateKey();
            Log.d(TAG, "Key generated successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate key", e);
        }
    }

    public boolean storeSecret(String key, String value) {
        try {
            SecretKey secretKey = (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);

            byte[] iv = cipher.getIV();
            byte[] encryptedData = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            // Combine IV and encrypted data
            byte[] combined = new byte[iv.length + encryptedData.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);

            String encodedData = Base64.encodeToString(combined, Base64.DEFAULT);

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(key, encodedData).apply();

            Log.d(TAG, "Secret stored successfully for key: " + key);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to store secret for key: " + key, e);
            return false;
        }
    }

    public String retrieveSecret(String key) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String encodedData = prefs.getString(key, null);
            if (encodedData == null) {
                Log.d(TAG, "No secret found for key: " + key);
                return null;
            }

            byte[] combined = Base64.decode(encodedData, Base64.DEFAULT);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encryptedData = new byte[combined.length - GCM_IV_LENGTH];

            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encryptedData, 0, encryptedData.length);

            SecretKey secretKey = (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] decryptedData = cipher.doFinal(encryptedData);
            String result = new String(decryptedData, StandardCharsets.UTF_8);

            Log.d(TAG, "Secret retrieved successfully for key: " + key);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve secret for key: " + key, e);
            return null;
        }
    }

    public boolean hasSecret(String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.contains(key);
    }

    public boolean deleteSecret(String key) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(key).apply();
            Log.d(TAG, "Secret deleted for key: " + key);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete secret for key: " + key, e);
            return false;
        }
    }

    public void clearAll() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            Log.d(TAG, "All secrets cleared");
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear all secrets", e);
        }
    }
}