package app.fuckclip.update

import android.os.Build
import app.fuckclip.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service querying GitHub Releases for application updates and version comparisons.
 *
 * 查询 GitHub Releases 获取应用最新版本并执行版本比对的更新检测服务。
 */
object UpdateChecker {

    /**
     * Canonical GitHub repository URL for FuckClip.
     *
     * FuckClip 的官方 GitHub 开源仓库 URL。
     */
    const val REPO_URL = "https://github.com/Cail-Gainey/FuckClip"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/Cail-Gainey/FuckClip/releases/latest"

    /**
     * Queries GitHub API synchronously for the latest release metadata. Must be executed on an IO dispatcher.
     *
     * 同步查询 GitHub API 获取最新发布元数据。必须在 IO 调度器或后台线程执行。
     *
     * @param abis Supported device CPU ABIs in priority order.
     * @return [UpdateInfo] containing release details and best matching APK asset.
     */
    @Throws(Exception::class)
    fun fetchLatest(abis: List<String> = getDeviceAbis()): UpdateInfo {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "FuckClip-App/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP response code: $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body, abis)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parses GitHub API JSON payload into an [UpdateInfo] instance.
     *
     * 解析 GitHub API 返回的 JSON 报文为 [UpdateInfo] 实例。
     *
     * @param json Raw JSON response string from GitHub API.
     * @param abis Supported device CPU ABIs in priority order.
     */
    fun parse(json: String, abis: List<String>): UpdateInfo {
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "")
        val pageUrl = root.optString("html_url", "").ifEmpty { "$REPO_URL/releases" }

        val assets = ArrayList<ApkAsset>()
        val array = root.optJSONArray("assets")
        if (array != null) {
            for (i in 0 until array.length()) {
                val asset = array.optJSONObject(i) ?: continue
                val url = asset.optString("browser_download_url", "")
                if (url.isEmpty()) continue
                assets += ApkAsset(
                    name = asset.optString("name", ""),
                    url = url,
                    size = asset.optLong("size", 0L),
                    sha256 = asset.optString("digest", "")
                        .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                        ?.substring("sha256:".length)
                        ?.lowercase()
                )
            }
        }

        return UpdateInfo(
            tag = tag,
            title = root.optString("name", tag),
            notes = root.optString("body", ""),
            pageUrl = pageUrl,
            apk = pickApk(assets, abis)
        )
    }

    /**
     * Selects the most suitable APK asset matching device ABIs, falling back to universal builds.
     *
     * 挑选最匹配本机 CPU 架构的 APK 资产，优先匹配原生 ABI，其次匹配 universal，杜绝未签名或调试包。
     *
     * @param assets List of available release assets.
     * @param abis Supported device CPU ABIs in priority order.
     */
    fun pickApk(assets: List<ApkAsset>, abis: List<String>): ApkAsset? {
        val candidates = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) &&
                !it.name.contains("debug", ignoreCase = true) &&
                !it.name.contains("unsigned", ignoreCase = true)
        }
        for (abi in abis) {
            val token = Regex("(?<![a-z0-9_])${Regex.escape(abi.lowercase())}(?![a-z0-9_])")
            candidates.firstOrNull { token.containsMatchIn(it.name.lowercase()) }?.let { return it }
        }
        return candidates.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    /**
     * Evaluates whether [remoteTag] is strictly newer than [currentVersion] via dotted numeric comparison.
     *
     * 通过点分数值比较算法判断 [remoteTag] 远端版本是否严格新于 [currentVersion] 本地版本。
     */
    fun isNewerVersion(remoteTag: String?, currentVersion: String): Boolean {
        if (remoteTag.isNullOrBlank()) return false

        val remote = numericParts(remoteTag)
        val current = numericParts(currentVersion)
        for (i in 0 until maxOf(remote.size, current.size)) {
            val r = remote.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun numericParts(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split(".")
            .map { Regex("^\\d+").find(it)?.value?.toIntOrNull() ?: 0 }

    private fun getDeviceAbis(): List<String> = try {
        Build.SUPPORTED_ABIS?.toList().orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }
}
