package za.co.epservice.jamguard;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Token is encrypted using a non-exportable Android Keystore AES key, never exposed to UI. */
public final class Vault {
  private static SecretKey key() throws Exception {
    KeyStore k = KeyStore.getInstance("AndroidKeyStore");
    k.load(null);
    if (!k.containsAlias("JamGuardSecrets")) {
      KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  "JamGuardSecrets", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (SecretKey) k.getKey("JamGuardSecrets", null);
  }

  public static void save(Context c, String token) throws Exception {
    if (token.isEmpty()) {
      c.getSharedPreferences("vault", 0).edit().remove("token").commit();
      return;
    }
    Cipher x = Cipher.getInstance("AES/GCM/NoPadding");
    x.init(Cipher.ENCRYPT_MODE, key());
    String v =
        Base64.encodeToString(x.getIV(), 2)
            + ":"
            + Base64.encodeToString(
                x.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2);
    if (!c.getSharedPreferences("vault", 0).edit().putString("token", v).commit())
      throw new Exception("Secret storage failed");
  }

  public static String get(Context c) throws Exception {
    String v = c.getSharedPreferences("vault", 0).getString("token", "");
    if (v.isEmpty()) return "";
    String[] p = v.split(":");
    Cipher x = Cipher.getInstance("AES/GCM/NoPadding");
    x.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], 2)));
    return new String(x.doFinal(Base64.decode(p[1], 2)), java.nio.charset.StandardCharsets.UTF_8);
  }

  public static boolean present(Context c) {
    return !c.getSharedPreferences("vault", 0).getString("token", "").isEmpty();
  }
}
