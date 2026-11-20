package com.example.chord.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.security.MessageDigest

/**
 * YouTube Music sign-in state. One object, no DI.
 *
 *  - Call [init] once in MainActivity.onCreate.
 *  - LoginScreen calls [save] with the WebView cookies.
 *  - HomeRepository calls [apply] on every request, so Home, search and
 *    artist pages come back personal once signed in.
 *
 * Cookie sits in private app storage. For extra safety later, swap the
 * SharedPreferences for EncryptedSharedPreferences. Nothing else changes.
 */
object AuthStore {
    private const val FILE = "chord_auth"
    private const val KEY = "cookie"

    private var prefs: SharedPreferences? = null
    @Volatile private var cookie: String? = null

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        cookie = prefs?.getString(KEY, null)?.takeIf { sapisid(it) != null }
        _signedIn.value = cookie != null
    }

    /** True when the cookie holds a login. */
    fun save(raw: String): Boolean {
        if (sapisid(raw) == null) return false
        cookie = raw
        prefs?.edit()?.putString(KEY, raw)?.apply()
        _signedIn.value = true
        return true
    }

    fun signOut() {
        cookie = null
        prefs?.edit()?.remove(KEY)?.apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        _signedIn.value = false
    }

    /** Add login headers. No-op when signed out. */
    fun apply(conn: HttpURLConnection, origin: String) {
        val c = cookie ?: return
        val sid = sapisid(c) ?: return
        val ts = System.currentTimeMillis() / 1000
        conn.setRequestProperty("Cookie", c)
        conn.setRequestProperty("Authorization", "SAPISIDHASH ${ts}_${sha1("$ts $sid $origin")}")
        conn.setRequestProperty("X-Goog-AuthUser", "0")
    }

    private fun sapisid(raw: String): String? {
        fun get(name: String) = raw.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
        return get("SAPISID") ?: get("__Secure-3PAPISID")
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}