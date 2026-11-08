package com.zerolinux.app.container

import android.content.Context
import android.util.Base64
import android.util.Log
import com.zerolinux.app.R
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 所有镜像下载共用的小型 HTTP 客户端。
 *
 * 证书链：
 * - Android 系统 CA（`AndroidCAStore`）
 * - 用户在系统设置里安装的自签 CA（同一 store 里的 `user:` 条目）
 * - APK 内置的 Mozilla CA bundle（老系统 root store 太旧时兜底）
 *
 * 三者合并成一个 TrustManager，避免旧 Android 缺少 Let's Encrypt/新根证书
 * 时直接 `certificate verify failed`。`network_security_config.xml` 同时允许
 * 用户 CA 与明文回退，给自建镜像/代理留出口。
 */
internal object Http {

    private const val TAG = "ZlHttp"

    /** 常见浏览器 UA：部分镜像站会拦截“非常用软件”特征。 */
    internal const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var sslFactory: SSLSocketFactory? = null

    fun init(ctx: Context) {
        if (sslFactory != null) return
        synchronized(this) {
            if (sslFactory != null) return
            sslFactory = runCatching { buildSslFactory(ctx.applicationContext) }
                .onFailure { Log.w(TAG, "build ssl factory failed: ${it.message}") }
                .getOrNull()
        }
    }

    private fun buildSslFactory(ctx: Context): SSLSocketFactory? {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        var count = 0

        // 系统 CA + 用户安装的自签 CA（AndroidCAStore 同时包含 system:/user: 别名）。
        runCatching {
            val android = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            val aliases = android.aliases()
            while (aliases.hasMoreElements()) {
                val cert = android.getCertificate(aliases.nextElement())
                if (cert is X509Certificate) {
                    keyStore.setCertificateEntry("android-${count++}", cert)
                }
            }
        }.onFailure { Log.w(TAG, "AndroidCAStore unavailable: ${it.message}") }

        // 内置 Mozilla bundle：老系统缺少的新根证书从这里补。
        runCatching {
            val text = ctx.resources.openRawResource(R.raw.zl_cacert)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val pem = Regex("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----")
            val factory = CertificateFactory.getInstance("X.509")
            pem.findAll(text).forEach { match ->
                val der = Base64.decode(match.groupValues[1].replace(Regex("\\s+"), ""), Base64.DEFAULT)
                val cert = factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                keyStore.setCertificateEntry("mozilla-${count++}", cert)
            }
        }.onFailure { Log.w(TAG, "bundled CA bundle invalid: ${it.message}") }

        if (count == 0) {
            Log.w(TAG, "no CA certificate available")
            return null
        }

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        val context = SSLContext.getInstance("TLS")
        context.init(null, tmf.trustManagers, null)
        Log.i(TAG, "trust store ready: $count CA(s)")
        return context.socketFactory
    }

    fun open(
        url: String,
        rangeStart: Long = -1L,
        timeoutMs: Int = 30_000,
        connectTimeoutMs: Int = timeoutMs
    ): HttpURLConnection {
        // 连接超时与读取超时分开：有些 CDN（如 kali.download 的 Cloudflare）
        // 同时有 AAAA/A 记录，而设备所在网络 IPv6 不通。HttpURLConnection 会
        // 逐个地址顺序连接，每个都等满 connectTimeout，表现为「下载一直 0%」
        // 好几分钟。连接阶段用短超时，尽快落到 IPv4 地址。
        val conn = newConnection(url, connectTimeoutMs, timeoutMs)
        if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")
        return conn
    }

    /**
     * 镜像探活：每个候选只取 1 字节并计时。
     * 返回 null 表示 HTTPS/HTTP 都失败；否则返回耗时毫秒。
     */
    fun probe(url: String, timeoutMs: Int = 8_000): Long? {
        val started = System.nanoTime()
        for (candidate in listOfNotNull(url, httpUrl(url))) {
            val conn = runCatching { newConnection(candidate, timeoutMs) }.getOrNull() ?: continue
            try {
                conn.setRequestProperty("Range", "bytes=0-0")
                val code = conn.responseCode
                // 某些镜像对不存在的文件返回 200 + text/html 错误页（实测 USTC LXC），
                // 不能只凭状态码判定可达，否则会把它排到第一并下载到 HTML。
                val type = conn.contentType.orEmpty().lowercase()
                if (code in 200..299 && !type.startsWith("text/html")) {
                    return (System.nanoTime() - started) / 1_000_000L
                }
                if (code == 416) {
                    return (System.nanoTime() - started) / 1_000_000L
                }
            } catch (_: Exception) {
                // 换下一个协议/候选
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    data class Bench(val ms: Long, val bytes: Int)

    /**
     * 只走 HTTPS 的镜像测速：读取最多 [maxBytes] 字节并计时。
     *
     * 有内置 CA 后不再需要明文回退；探测失败直接返回 null，避免把
     * “HTTP 能通但 HTTPS 证书错误”的镜像误判为可用。
     */
    fun benchmarkHttps(
        url: String,
        maxBytes: Int = 64 * 1024,
        timeoutMs: Int = 8_000
    ): Bench? {
        if (!url.startsWith("https://")) return null
        val started = System.nanoTime()
        val conn = runCatching { newConnection(url, timeoutMs) }.getOrNull() ?: return null
        return try {
            conn.setRequestProperty("Range", "bytes=0-${(maxBytes - 1).coerceAtLeast(0)}")
            val code = conn.responseCode
            val type = conn.contentType.orEmpty().lowercase()
            if (code !in 200..299 && code != 416) return null
            if (type.startsWith("text/html")) return null
            var total = 0
            if (code != 416) {
                conn.inputStream.use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (total < maxBytes) {
                        val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                        if (n <= 0) break
                        total += n
                    }
                }
            }
            if (total <= 0) return null
            Bench((System.nanoTime() - started) / 1_000_000L, total)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun getText(url: String, timeoutMs: Int = 30_000): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                if (response.header("Content-Type").orEmpty().lowercase().startsWith("text/html")) {
                    return null
                }
                response.body?.string()
            }
        }.getOrNull()
    }

    /**
     * 共享 OkHttp 客户端。OkHttp 会按 Dns 返回的顺序逐个连接，
     * 配合 [NetDns] 把可达的镜像地址排到最前面。
     */
    internal val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(NetDns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** HTTPS 失败时的明文回退；镜像内容仍由 SHA256 校验兜底。 */
    fun httpUrl(url: String): String? =
        if (url.startsWith("https://")) "http://" + url.removePrefix("https://") else null

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 从 SHA256SUMS 文本里取出某个文件名对应的哈希。 */
    fun findHash(sumsText: String, fileName: String): String? =
        sumsText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .firstOrNull { line ->
                val name = line.split(Regex("\\s+")).lastOrNull()?.removePrefix("*")
                name == fileName
            }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase()

    private fun newConnection(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int = connectTimeoutMs
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn is HttpsURLConnection) {
            sslFactory?.let { conn.sslSocketFactory = it }
        }
        return conn
    }
}

/**
 * 解析后先做 TCP 探活，把可达地址排在最前面。
 *
 * 国内网络对 Cloudflare 的部分 anycast IP 会黑洞（TCP 连接直接超时），
 * 而 OkHttp 只对 ConnectException 继续尝试下一个地址，遇到
 * SocketTimeoutException 就直接放弃；如果 DNS 正好把不可达 IP 排在前面，
 * 下载会一直停在 0%。这里先探测 443 端口，可达的排前面。
 */
internal object NetDns : Dns {

    private const val TAG = "ZlNet"
    private const val TTL_MS = 5 * 60_000L
    private const val PROBE_MS = 1_200

    private data class Entry(val addresses: List<InetAddress>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (now - it.at < TTL_MS) return it.addresses }

        val all = Dns.SYSTEM.lookup(hostname)
        val ranked = if (all.size <= 1 || probe(all.first())) {
            // 常见情况：第一个地址就通，保持系统顺序，零额外延迟。
            all
        } else {
            val reachable = ArrayList<InetAddress>(all.size)
            val blocked = ArrayList<InetAddress>(all.size)
            for (address in all) {
                if (probe(address)) reachable += address else blocked += address
            }
            if (reachable.isEmpty()) all else reachable + blocked
        }
        if (ranked !== all) {
            Log.i(TAG, "dns $hostname: ${ranked.joinToString { it.hostAddress.orEmpty() }}")
        }
        cache[hostname] = Entry(ranked, now)
        return ranked
    }

    private fun probe(address: InetAddress): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(address, 443), PROBE_MS) }
        true
    }.getOrDefault(false)
}
