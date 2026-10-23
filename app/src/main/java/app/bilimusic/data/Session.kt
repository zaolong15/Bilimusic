package app.bilimusic.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录 Cookie 存放处。
 * - 用 Android Keystore 里的密钥加密后落盘（EncryptedSharedPreferences）
 * - 只保留必要的几项，不保存 WebView 里的其他 Cookie
 * - 若加密存储不可用，只在内存里保留（重启后需重新登录），绝不退回明文
 */
class Session(private val ctx: Context) {
    private var memory: String? = null
    private val sp: SharedPreferences? = open()

    init { ctx.deleteSharedPreferences("session") } // 清理旧版明文文件

    private fun open(): SharedPreferences? {
        fun create() = EncryptedSharedPreferences.create(
            ctx, FILE,
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return try { create() } catch (e: Exception) {
            // 密钥损坏（例如恢复备份或系统升级后）：清掉再建一次
            ctx.deleteSharedPreferences(FILE)
            try { create() } catch (e2: Exception) { null }
        }
    }

    val cookie: String?
        get() = memory ?: sp?.getString(KEY, null)?.also { memory = it }

    fun save(raw: String) {
        val kept = raw.split(";").map { it.trim() }
            .filter { part -> ALLOWED.any { part.startsWith("$it=") } }
            .joinToString("; ")
        memory = kept
        sp?.edit()?.putString(KEY, kept)?.apply()
    }

    fun clear() {
        memory = null
        sp?.edit()?.clear()?.apply()
    }

    private companion object {
        const val FILE = "session_secure"
        const val KEY = "cookie"
        val ALLOWED = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4", "b_nut")
    }
}
