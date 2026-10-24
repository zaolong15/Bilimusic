package app.bilimusic.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 外观设置。accent 为预设序号。 */
data class Appearance(
    val mode: Int = 0,            // 0 跟随系统 1 浅色 2 深色
    val accent: Int = 0,          // 0..4 为预设，5 为自定义
    val hue: Float = 20f,         // 自定义强调色的色相（0..360）
    val serifTitles: Boolean = true,
    val corners: Int = 1          // 0 紧凑 1 标准 2 圆润
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(
        Appearance(
            mode = sp.getInt("mode", 0),
            accent = sp.getInt("accent", 0),
            hue = sp.getFloat("hue", 20f),
            serifTitles = sp.getBoolean("serif", true),
            corners = sp.getInt("corners", 1)
        )
    )
    val appearance: StateFlow<Appearance> = _appearance

    private val _wifiOnly = MutableStateFlow(sp.getBoolean("wifiOnly", true))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly
    fun setWifiOnly(v: Boolean) { _wifiOnly.value = v; sp.edit().putBoolean("wifiOnly", v).apply() }

    fun update(f: (Appearance) -> Appearance) {
        val a = f(_appearance.value)
        _appearance.value = a
        sp.edit().putInt("mode", a.mode).putInt("accent", a.accent).putFloat("hue", a.hue)
            .putBoolean("serif", a.serifTitles).putInt("corners", a.corners).apply()
    }
}
