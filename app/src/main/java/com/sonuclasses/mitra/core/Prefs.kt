package com.sonuclasses.mitra.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.sonuclasses.mitra.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

object Prefs {
    private lateinit var sp: SharedPreferences

    /** Bumped whenever memory/persona changes so the UI can refresh. */
    val version = MutableStateFlow(0)

    fun init(ctx: Context) {
        sp = try {
            val master = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "mitra_secure", master, ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore broken on some devices → fall back to private prefs rather than crash.
            ctx.getSharedPreferences("mitra_plain", Context.MODE_PRIVATE)
        }
    }

    private fun bump() { version.value = version.value + 1 }

    var userKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply(); bump() }

    val builtInKey: String get() = BuildConfig.GEMINI_API_KEY.trim()
    val hasBuiltInKey: Boolean get() = builtInKey.isNotEmpty()

    /** The key actually used: the user's own key overrides the built-in one. */
    val apiKey: String get() = userKey.ifEmpty { builtInKey }
    val hasKey: Boolean get() = apiKey.isNotEmpty()

    var personaName: String
        get() = sp.getString("persona_name", "मित्र") ?: "मित्र"
        set(v) { sp.edit().putString("persona_name", v.ifBlank { "मित्र" }).apply(); bump() }

    var voice: String
        get() = sp.getString("voice", "Kore") ?: "Kore"
        set(v) { sp.edit().putString("voice", v).apply(); bump() }

    /** dost | teacher | secretary */
    var style: String
        get() = sp.getString("style", "dost") ?: "dost"
        set(v) { sp.edit().putString("style", v).apply(); bump() }

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay", false)
        set(v) { sp.edit().putBoolean("overlay", v).apply(); bump() }

    var useLive: Boolean
        get() = sp.getBoolean("use_live", true)
        set(v) { sp.edit().putBoolean("use_live", v).apply(); bump() }

    var lastLiveModel: String
        get() = sp.getString("live_model", "") ?: ""
        set(v) { sp.edit().putString("live_model", v).apply() }

    var facts: List<String>
        get() {
            val raw = sp.getString("facts", "[]") ?: "[]"
            return try {
                val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }
            } catch (e: Exception) { emptyList() }
        }
        set(v) { sp.edit().putString("facts", JSONArray(v).toString()).apply(); bump() }

    fun addFact(f: String) {
        val t = f.trim()
        if (t.isEmpty()) return
        val cur = facts.toMutableList()
        if (cur.none { it.equals(t, ignoreCase = true) }) { cur.add(t); facts = cur.takeLast(100) }
    }

    fun removeFact(index: Int) {
        val cur = facts.toMutableList()
        if (index in cur.indices) { cur.removeAt(index); facts = cur }
    }

    val VOICES = listOf(
        "Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr",
        "Callirrhoe", "Autonoe", "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina",
        "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar",
        "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia",
        "Sadaltager", "Sulafat"
    )

    val STYLES = listOf("dost" to "दोस्त", "teacher" to "टीचर", "secretary" to "सेक्रेटरी")
}
