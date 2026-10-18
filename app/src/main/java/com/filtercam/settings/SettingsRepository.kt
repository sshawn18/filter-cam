package com.filtercam.settings

import android.content.SharedPreferences

enum class FlashSetting { OFF, ON, AUTO }

interface KeyValueStore {
    fun getBoolean(k: String, def: Boolean): Boolean
    fun putBoolean(k: String, v: Boolean)
    fun getInt(k: String, def: Int): Int
    fun putInt(k: String, v: Int)
    fun getString(k: String, def: String?): String?
    fun putString(k: String, v: String?)
}

class SharedPrefsStore(private val p: SharedPreferences) : KeyValueStore {
    override fun getBoolean(k: String, def: Boolean) = p.getBoolean(k, def)
    override fun putBoolean(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()
    override fun getInt(k: String, def: Int) = p.getInt(k, def)
    override fun putInt(k: String, v: Int) = p.edit().putInt(k, v).apply()
    override fun getString(k: String, def: String?) = p.getString(k, def)
    override fun putString(k: String, v: String?) = p.edit().putString(k, v).apply()
}

class SettingsRepository(private val kv: KeyValueStore) {
    var gridDefault: Boolean
        get() = kv.getBoolean("grid", false); set(v) = kv.putBoolean("grid", v)
    var flashDefault: FlashSetting
        get() = runCatching { FlashSetting.valueOf(kv.getString("flash", "OFF") ?: "OFF") }.getOrDefault(FlashSetting.OFF)
        set(v) = kv.putString("flash", v.name)
    var jpegQuality: Int
        get() = kv.getInt("quality", 95).coerceIn(70, 100); set(v) = kv.putInt("quality", v.coerceIn(70, 100))
    var mirrorFront: Boolean
        get() = kv.getBoolean("mirror", false); set(v) = kv.putBoolean("mirror", v)
    var lastFilter: String?
        get() = kv.getString("lastFilter", null); set(v) = kv.putString("lastFilter", v)
}
