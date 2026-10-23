package app.bilimusic.data

import android.text.Html
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
const val REFERER = "https://www.bilibili.com/"

data class UserInfo(val mid: Long, val name: String, val face: String)
data class Folder(val id: Long, val title: String, val count: Int)
class BiliException(val code: Int, message: String) : Exception(message)

/** 播放链路里的最近一次失败原因。服务和界面在同一进程，用它把友好文案带给界面。 */
object PlaybackNotes { @Volatile var last: String? = null }

/** 面向用户的错误文案：先说发生了什么，再说怎么办。 */
fun Throwable.friendly(): String {
    var cur: Throwable? = this
    while (cur != null) {
        val e: Throwable = cur
        when (e) {
            is BiliException -> return when (e.code) {
                -101 -> "登录已失效。请重新登录。"
                -412, -352, -799 -> "B 站暂时限制了请求。请等几分钟再试，或重新登录。"
                -404, 62002, 62004, 62012 -> "这个视频已不可用（被删除或设为私有）。"
                else -> if (e.message.isNullOrBlank()) "B 站返回了未知错误（${e.code}）。" else "B 站返回：${e.message}"
            }
            is UnknownHostException -> return "网络不可用。请检查网络连接后重试。"
            is SocketTimeoutException -> return "连接超时。请稍后重试。"
            else -> {}
        }
        cur = e.cause
    }
    return message ?: "出了点问题。请稍后重试。"
}

private fun String.https(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://" + removePrefix("http://")
    else -> this
}

private fun plain(html: String): String =
    Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()

private fun parseDuration(s: String): Int =
    s.split(":").mapNotNull { it.toIntOrNull() }.fold(0) { acc, n -> acc * 60 + n }

private val MIXIN_TAB = intArrayOf(
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19,
    29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~")
private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
    .joinToString("") { "%02x".format(it) }

/** 所有方法都是阻塞调用，请在 IO 线程使用。 */
class BiliApi(private val session: Session) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    @Volatile private var wbiKey: Pair<String, Long>? = null
    @Volatile private var buvid: String? = null

    // ---------- 基础请求 ----------
    private fun fetchBuvid(): String? = try {
        val req = Request.Builder().url("https://api.bilibili.com/x/frontend/finger/spi").header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            val d = JSONObject(r.body?.string().orEmpty()).optJSONObject("data")
            if (d == null) null else "buvid3=${d.optString("b_3")}; buvid4=${d.optString("b_4")}"
        }
    } catch (e: Exception) { null }

    /** 未登录或 Cookie 里没有 buvid3 时补一个，否则搜索容易被风控拦截。 */
    private fun cookieHeader(): String? {
        val base = session.cookie
        if (base != null && base.contains("buvid3=")) return base
        val extra: String? = buvid ?: fetchBuvid().also { buvid = it }
        return listOfNotNull(base, extra).joinToString("; ").ifBlank { null }
    }

    private fun fetchJson(url: String, allowCodes: Set<Int>): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .apply { cookieHeader()?.let { header("Cookie", it) } }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) throw BiliException(resp.code, "服务器没有返回内容")
            val json = JSONObject(text)
            val code = json.optInt("code", 0)
            if (code != 0 && code !in allowCodes) throw BiliException(code, json.optString("message"))
            return json
        }
    }

    /** 遇到 -412（风控）时，稍等一下重试一次，并让后续请求重新获取签名密钥。 */
    private fun getJson(url: String, allowCodes: Set<Int> = emptySet()): JSONObject = try {
        fetchJson(url, allowCodes)
    } catch (e: BiliException) {
        if (e.code != -412) throw e
        Thread.sleep(700)
        wbiKey = null
        fetchJson(url, allowCodes)
    }

    // ---------- WBI 签名 ----------
    private fun mixinKey(): String {
        wbiKey?.let { (k, t) -> if (System.currentTimeMillis() - t < 6 * 3_600_000L) return k }
        val img = fetchJson("https://api.bilibili.com/x/web-interface/nav", setOf(-101))
            .getJSONObject("data").getJSONObject("wbi_img")
        fun stem(u: String) = u.substringAfterLast('/').substringBefore('.')
        val raw = stem(img.getString("img_url")) + stem(img.getString("sub_url"))
        val key = buildString { for (i in MIXIN_TAB) append(raw[i]) }.take(32)
        wbiKey = key to System.currentTimeMillis()
        return key
    }

    private fun signed(base: String, params: Map<String, String>): String {
        val all = (params + ("wts" to (System.currentTimeMillis() / 1000).toString())).toSortedMap()
        val query = all.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v.filter { c -> c !in "!'()*" })}"
        }
        return "$base?$query&w_rid=${md5(query + mixinKey())}"
    }

    // ---------- 账号 ----------
    /** 返回 null 表示未登录或已过期；网络问题会抛异常。 */
    fun me(): UserInfo? {
        val d = getJson("https://api.bilibili.com/x/web-interface/nav", setOf(-101)).optJSONObject("data") ?: return null
        if (!d.optBoolean("isLogin")) return null
        return UserInfo(d.optLong("mid"), d.optString("uname"), d.optString("face").https())
    }

    fun folders(mid: Long): List<Folder> {
        val arr = getJson("https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid")
            .optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Folder(it.getLong("id"), it.optString("title"), it.optInt("media_count")) }
    }

    /** 返回（本页歌曲，是否还有下一页）。已失效的内容（attr=9）会被过滤。 */
    fun folderItems(folderId: Long, page: Int): Pair<List<Song>, Boolean> {
        val d = getJson("https://api.bilibili.com/x/v3/fav/resource/list?media_id=$folderId&pn=$page&ps=20&order=mtime&type=0&platform=web")
            .optJSONObject("data") ?: return emptyList<Song>() to false
        val arr = d.optJSONArray("medias") ?: return emptyList<Song>() to false
        val songs = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optInt("type") == 2 && it.optInt("attr") != 9 && it.optString("bvid").isNotBlank() }
            .map {
                Song(
                    bvid = it.getString("bvid"),
                    title = plain(it.optString("title")),
                    artist = it.optJSONObject("upper")?.optString("name").orEmpty(),
                    cover = it.optString("cover").https(),
                    durationSec = it.optInt("duration")
                )
            }
        return songs to d.optBoolean("has_more")
    }

    // ---------- 搜索 ----------
    fun search(keyword: String, page: Int): List<Song> {
        val url = signed(
            "https://api.bilibili.com/x/web-interface/wbi/search/type",
            mapOf("search_type" to "video", "keyword" to keyword, "page" to page.toString())
        )
        val arr = getJson(url).optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Song(
                bvid = o.getString("bvid"),
                title = plain(o.optString("title")),
                artist = o.optString("author"),
                cover = o.optString("pic").https(),
                durationSec = parseDuration(o.optString("duration"))
            )
        }
    }

    // ---------- 取流 ----------
    /** 同一音轨有主链接和备用链接；尽量避开 PCDN（mcdn / 非标准端口），它们更容易失败。 */
    private fun pickUrl(a: JSONObject): String {
        val list = ArrayList<String>()
        a.optString("baseUrl").ifBlank { a.optString("base_url") }.takeIf { it.isNotBlank() }?.let { list += it }
        val backup = a.optJSONArray("backupUrl") ?: a.optJSONArray("backup_url")
        if (backup != null) for (i in 0 until backup.length()) list += backup.getString(i)
        return list.firstOrNull { "mcdn" !in it && !Regex(":\\d{4,5}/").containsMatchIn(it) } ?: list.firstOrNull().orEmpty()
    }

    /** 解析音频直链。链接约两小时失效，所以只缓存 90 分钟。多 P 视频取第一个分 P。 */
    fun audioUrl(bvid: String): String {
        urlCache[bvid]?.let { (u, t) -> if (System.currentTimeMillis() - t < 90 * 60_000L) return u }
        val cid = getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid").getJSONObject("data").getLong("cid")
        val play = getJson(
            signed(
                "https://api.bilibili.com/x/player/wbi/playurl",
                mapOf("bvid" to bvid, "cid" to cid.toString(), "fnval" to "16", "fnver" to "0", "fourk" to "1")
            )
        ).getJSONObject("data")
        val audios = play.optJSONObject("dash")?.optJSONArray("audio")
        val best = audios?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.maxByOrNull { it.optInt("bandwidth") } }
        val url = best?.let { pickUrl(it) } ?: play.optJSONArray("durl")?.optJSONObject(0)?.optString("url")
        if (url.isNullOrBlank()) throw BiliException(-1, "没有找到可播放的音频")
        val safe = url.https()
        urlCache[bvid] = safe to System.currentTimeMillis()
        return safe
    }
}
