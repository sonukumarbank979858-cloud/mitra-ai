package com.sonuclasses.mitra.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Site passwords the user chose to remember ("इस साइट के लिए याद रखें").
 * Stored only in an Android-Keystore-backed EncryptedSharedPreferences file, keyed by host.
 * Values are never logged, never sent to Gemini and never written to chat history.
 * If the Keystore is unavailable nothing is saved (no plain-text fallback).
 */
object PasswordVault {
    @Volatile private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        sp = try {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "mitra_site_passwords", master, ctx.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) { null }
    }

    val available: Boolean get() = sp != null

    private fun norm(host: String) = host.lowercase().removePrefix("www.").trim()

    fun get(host: String): String? = try {
        if (host.isBlank()) null else sp?.getString("pw:" + norm(host), null)?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) { null }

    fun save(host: String, value: String): Boolean = try {
        val s = sp
        if (s == null || host.isBlank() || value.isEmpty()) false
        else { s.edit().putString("pw:" + norm(host), value).apply(); true }
    } catch (e: Exception) { false }

    fun remove(host: String) { try { sp?.edit()?.remove("pw:" + norm(host))?.apply() } catch (_: Exception) {} }

    fun count(): Int = try { sp?.all?.keys?.count { it.startsWith("pw:") } ?: 0 } catch (e: Exception) { 0 }

    fun clearAll() { try { sp?.edit()?.clear()?.apply() } catch (_: Exception) {} }
}
