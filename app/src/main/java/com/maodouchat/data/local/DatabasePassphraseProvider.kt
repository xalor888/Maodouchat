package com.maodouchat.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.sqlcipher.database.SQLiteDatabase
import java.security.SecureRandom

/**
 * SQLCipher passphrase manager.
 *
 * The random database passphrase is stored inside EncryptedSharedPreferences,
 * whose master key is backed by Android Keystore. The passphrase is generated
 * once per install and is never hard-coded or derived from user credentials.
 */
object DatabasePassphraseProvider {

    private const val PREFS_NAME = "secure_database_key_prefs"
    private const val PASSPHRASE_KEY = "sqlcipher_passphrase_hex"
    private const val PASSPHRASE_BYTES = 32

    fun getPassphrase(context: Context): ByteArray {
        val appContext = context.applicationContext
        SQLiteDatabase.loadLibs(appContext)
        val prefs = encryptedPrefs(appContext)
        val existing = prefs.getString(PASSPHRASE_KEY, null)
        val hex = existing ?: generatePassphraseHex().also { generated ->
            check(prefs.edit().putString(PASSPHRASE_KEY, generated).commit()) {
                "Failed to persist SQLCipher passphrase"
            }
        }
        return SQLiteDatabase.getBytes(hex.toCharArray())
    }

    fun destroyPassphrase(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            encryptedPrefs(appContext).edit().remove(PASSPHRASE_KEY).commit()
        }
        appContext.deleteSharedPreferences(PREFS_NAME)
    }

    private fun encryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun generatePassphraseHex(): String {
        val bytes = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
