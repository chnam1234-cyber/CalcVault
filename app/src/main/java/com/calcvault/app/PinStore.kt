package com.calcvault.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** Stores a salted SHA-256 hash of the 6-digit code and the set of hidden packages. */
class PinStore(context: Context) {
    private val prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = prefs.contains(KEY_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        prefs.edit().putString(KEY_SALT, salt).putString(KEY_HASH, hash(pin, salt)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), hash(pin, salt).toByteArray())
    }

    var hiddenApps: Set<String>
        get() = prefs.getStringSet(KEY_APPS, emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet(KEY_APPS, value.toSet()).apply() }

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        const val PIN_LENGTH = 6
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_APPS = "hidden_apps"

        fun isValidPin(s: String) = s.length == PIN_LENGTH && s.all { it.isDigit() }
    }
}
