package com.rodinrescue.data

import android.content.Context
import com.rodinrescue.transport.HostKeyStore

/** Non-secret preferences + pinned SSH host keys. */
class Settings(context: Context) : HostKeyStore {
    private val p = context.getSharedPreferences("rodin_settings", Context.MODE_PRIVATE)

    var host: String get() = p.getString("host", "") ?: ""; set(v) = p.edit().putString("host", v).apply()
    var port: Int get() = p.getInt("port", 22); set(v) = p.edit().putInt("port", v).apply()
    var user: String get() = p.getString("user", "") ?: ""; set(v) = p.edit().putString("user", v).apply()
    var useKeyAuth: Boolean get() = p.getBoolean("useKey", false); set(v) = p.edit().putBoolean("useKey", v).apply()
    var provider: String get() = p.getString("provider", "deepseek") ?: "deepseek"; set(v) = p.edit().putString("provider", v).apply()
    var deepseekModel: String get() = p.getString("dsModel", "deepseek-flash") ?: "deepseek-flash"; set(v) = p.edit().putString("dsModel", v).apply()
    var deepseekThinking: Boolean get() = p.getBoolean("dsThinking", false); set(v) = p.edit().putBoolean("dsThinking", v).apply()
    var openaiModel: String get() = p.getString("oaModel", "gpt-4.1") ?: "gpt-4.1"; set(v) = p.edit().putString("oaModel", v).apply()
    var claudeApiModel: String get() = p.getString("claudeApiModel", com.rodinrescue.ai.ClaudeApiProvider.DEFAULT_MODEL) ?: com.rodinrescue.ai.ClaudeApiProvider.DEFAULT_MODEL; set(v) = p.edit().putString("claudeApiModel", v).apply()
    /** Built-in CLI brains: chosen model / effort per CLI id ("" = the CLI's own default). */
    fun cliModel(id: String): String = p.getString("cliModel_$id", "") ?: ""
    fun setCliModel(id: String, v: String) = p.edit().putString("cliModel_$id", v).apply()
    fun cliEffort(id: String): String = p.getString("cliEffort_$id", "") ?: ""
    fun setCliEffort(id: String, v: String) = p.edit().putString("cliEffort_$id", v).apply()
    /** Claude subscription: one long session per conversation (option B); off = one CLI run per step. */
    /** Built-in CLI brains: one long session per conversation (option B); off = one CLI run per step. Claude's key predates the others. */
    private fun longKey(id: String) = if (id == "claude") "claudeLongSession" else "longSession_$id"
    fun longSession(id: String): Boolean = p.getBoolean(longKey(id), true)
    fun setLongSession(id: String, v: Boolean) = p.edit().putBoolean(longKey(id), v).apply()
    var geminiModel: String get() = p.getString("gmModel", "gemini-2.5-pro") ?: "gemini-2.5-pro"; set(v) = p.edit().putString("gmModel", v).apply()
    /** Custom AI instructions; null means "use the shipped default". */
    var systemPrompt: String? get() = p.getString("systemPrompt", null); set(v) = p.edit().apply { if (v == null) remove("systemPrompt") else putString("systemPrompt", v) }.apply()
    var replyLanguage: String get() = p.getString("replyLang", "") ?: ""; set(v) = p.edit().putString("replyLang", v).apply()
    var keepScreenOn: Boolean get() = p.getBoolean("keepScreenOn", true); set(v) = p.edit().putBoolean("keepScreenOn", v).apply()
    /** Keyboard rescue: photo after every action (off = the AI looks when it needs to). */
    /** Keyboard rescue: the phone is held in the hand, so every look is a photo the user takes on a card (off = on a stand, automatic photos). */
    var kbHandheld: Boolean get() = p.getBoolean("kbHandheld", false); set(v) = p.edit().putBoolean("kbHandheld", v).apply()
    var kbAutoPhoto: Boolean get() = p.getBoolean("kbAutoPhoto", false); set(v) = p.edit().putBoolean("kbAutoPhoto", v).apply()
    /** Keyboard rescue token saver: each photo is sent only with the next request. */
    var kbPhotoOnce: Boolean get() = p.getBoolean("kbPhotoOnce", false); set(v) = p.edit().putBoolean("kbPhotoOnce", v).apply()
    /** Keyboard rescue's own AI instructions (null = the shipped default). */
    var kbSystemPrompt: String? get() = p.getString("kbSystemPrompt", null); set(v) = p.edit().apply { if (v == null) remove("kbSystemPrompt") else putString("kbSystemPrompt", v) }.apply()
    var showSplash: Boolean get() = p.getBoolean("showSplash", true); set(v) = p.edit().putBoolean("showSplash", v).apply()
    var autoCleanup: Boolean get() = p.getBoolean("autoCleanup", false); set(v) = p.edit().putBoolean("autoCleanup", v).apply()
    var appLock: Boolean get() = p.getBoolean("appLock", false); set(v) = p.edit().putBoolean("appLock", v).apply()
    /** Which data migrations already ran (see Migrations). 1 = data from the first releases. */
    var dataVersion: Int get() = p.getInt("dataVersion", 1); set(v) = p.edit().putInt("dataVersion", v).apply()
    /** Laptop setup also adds a login prompt on this phone's USB serial port (off by default: rarely needed). */
    var serialLogin: Boolean get() = p.getBoolean("serialLogin", false); set(v) = p.edit().putBoolean("serialLogin", v).apply()
    /** Storage: delete screen photos and audit logs older than this many days (0 = keep them). */
    var autoDeleteDays: Int get() = p.getInt("autoDeleteDays", 0); set(v) = p.edit().putInt("autoDeleteDays", v).apply()
    var maxSteps: Int get() = p.getInt("maxSteps", 25); set(v) = p.edit().putInt("maxSteps", v).apply()

    override fun pinnedHostKey(hostPort: String): String? = p.getString("hk:$hostPort", null)
    override fun pinHostKey(hostPort: String, fingerprint: String) = p.edit().putString("hk:$hostPort", fingerprint).apply()
    override fun forgetHostKey(hostPort: String) = p.edit().remove("hk:$hostPort").apply()

    /** Fingerprints of laptops the user has set up through the one-click flow (lets a new DHCP address be re-trusted automatically). */
    fun knownLaptopFps(): Set<String> = p.getStringSet("knownFps", emptySet()) ?: emptySet()
    fun addKnownLaptopFps(fps: Collection<String>) = p.edit().putStringSet("knownFps", knownLaptopFps() + fps).apply()
    fun removeKnownLaptopFps(fps: Collection<String>) = p.edit().putStringSet("knownFps", knownLaptopFps() - fps.toSet()).apply()
    /**
     * Keeps trust only for laptops that still have a device profile: drops auto-trusted fingerprints and pinned
     * host keys that belong to none of [keep]. Returns true if anything was removed.
     */
    fun pruneTrust(keep: Set<String>): Boolean {
        val e = p.edit(); var changed = false
        val known = knownLaptopFps()
        if (!keep.containsAll(known)) { e.putStringSet("knownFps", known.intersect(keep)); changed = true }
        p.all.forEach { (k, v) -> if (k.startsWith("hk:") && v is String && v.split(",").none { it in keep }) { e.remove(k); changed = true } }
        e.apply(); return changed
    }

    /** Drops every pinned host key whose fingerprint list contains one of [fps]. */
    fun forgetHostKeysWith(fps: Collection<String>) {
        val e = p.edit()
        p.all.forEach { (k, v) -> if (k.startsWith("hk:") && v is String && v.split(",").any { it in fps }) e.remove(k) }
        e.apply()
    }
}
