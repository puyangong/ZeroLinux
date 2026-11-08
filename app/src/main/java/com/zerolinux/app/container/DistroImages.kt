package com.zerolinux.app.container

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RootfsSource(
    val distro: DistroId,
    val url: String,
    val fileName: String,
    /** SHA256SUMS 文本地址，空表示该源不提供校验。 */
    val sumUrl: String = "",
    val approxBytes: Long = 0L,
    val label: String = "",
    /**
     * SHA256SUMS 文本里对应的条目名，默认与本地 fileName 相同。
     * LXC 镜像目录（debian）内条目固定叫 rootfs.tar.xz，
     * 而本地需要按发行版/架构区分的文件名，故单独给出。
     */
    val checksumEntry: String = fileName
)

/**
 * rootfs 一律运行时下载，不打进 APK。版本优先走目录索引解析，
 * 解析失败时回退到写死的稳定文件名。
 *
 * 每个发行版给出**候选列表**（国内镜像优先，官方兜底），由
 * [RootfsDownloader.downloadAll] 依次尝试；[resolve] 只返回首个候选，
 * 需要完整列表的调用方用 [resolveAll]。
 */
object DistroImages {

    private const val UBUNTU_RELEASE = "26.04"
    private const val ALPINE_BRANCH = "v3.21"
    private const val ALPINE_PIN = "3.21.9"
    private const val DEBIAN_SUITE = "bookworm"
    private const val DEBIAN_FALLBACK_TS = "20261029_05:24"

    /**
     * 顺序即下载尝试顺序。2026-11-03 实测：
     * - ubuntu-base 只有官方 cdimage 完整镜像，国内站点不收录该目录（404/403）；
     * - 因此官方放首位，国内站仅作兜底，避免每次先等 403。
     */
    private val UBUNTU_BASES = listOf(
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_RELEASE/release",
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-base/releases/$UBUNTU_RELEASE/release",
        "https://mirrors.aliyun.com/ubuntu-base/releases/$UBUNTU_RELEASE/release"
    )

    /** Alpine 官方 CDN 在境外；USTC/Aliyun 实测可用，TUNA 在部分网络会 403。 */
    private val ALPINE_BASES = listOf(
        "https://mirrors.ustc.edu.cn/alpine",
        "https://mirrors.aliyun.com/alpine",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine",
        "https://dl-cdn.alpinelinux.org/alpine"
    )

    /** LXC 镜像：官方实测稳定；NJU 有反爬跳转，TUNA 在部分网络 403，USTC 目录已不存在且会返回 200 HTML。 */
    private val LXC_BASES = listOf(
        "https://images.linuxcontainers.org",
        "https://mirror.nju.edu.cn/lxc-images",
        "https://mirrors.tuna.tsinghua.edu.cn/lxc-images"
    )

    /** Kali 官方下载站实测可用；TUNA 作为国内兜底。 */
    private val KALI_BASES = listOf(
        "https://kali.download/nethunter-images/current/rootfs",
        "https://mirrors.tuna.tsinghua.edu.cn/kali/nethunter-images/current/rootfs"
    )

    fun archFor(abi: String): String = when {
        abi.startsWith("arm64") -> "arm64"
        abi.startsWith("armeabi") -> "armhf"
        abi.startsWith("x86_64") -> "amd64"
        abi.startsWith("x86") -> "i386"
        else -> "arm64"
    }

    fun alpineArchFor(abi: String): String = when {
        abi.startsWith("arm64") -> "aarch64"
        abi.startsWith("armeabi") -> "armv7"
        abi.startsWith("x86_64") -> "x86_64"
        abi.startsWith("x86") -> "x86"
        else -> "aarch64"
    }

    /** 首个候选（国内镜像）。保持原有签名，老调用方不用改。 */
    suspend fun resolve(
        distro: DistroId,
        abi: String
    ): RootfsSource = resolveAll(distro, abi).first()

    /** 全部候选，按优先级排序；下载器逐个尝试。 */
    suspend fun resolveAll(
        distro: DistroId,
        abi: String
    ): List<RootfsSource> = withContext(Dispatchers.IO) {
        when (distro) {
            DistroId.UBUNTU -> ubuntu(abi)
            DistroId.ALPINE -> alpine(abi)
            DistroId.KALI -> kali(abi)
            DistroId.DEBIAN -> debian(abi)
        }
    }

    private fun ubuntu(abi: String): List<RootfsSource> {
        val arch = archFor(abi)
        val name = "ubuntu-base-$UBUNTU_RELEASE-base-$arch.tar.gz"
        return UBUNTU_BASES.map { base ->
            RootfsSource(
                distro = DistroId.UBUNTU,
                url = "$base/$name",
                fileName = name,
                sumUrl = "$base/SHA256SUMS",
                approxBytes = 32L * 1024 * 1024,
                label = "Ubuntu $UBUNTU_RELEASE base ($arch)"
            )
        }
    }

    private fun alpine(abi: String): List<RootfsSource> {
        val arch = alpineArchFor(abi)
        val latest = runCatching { latestAlpine(arch) }.getOrNull() ?: ALPINE_PIN
        val name = "alpine-minirootfs-$latest-$arch.tar.gz"
        return ALPINE_BASES.map { base ->
            val dir = "$base/$ALPINE_BRANCH/releases/$arch"
            RootfsSource(
                distro = DistroId.ALPINE,
                url = "$dir/$name",
                fileName = name,
                sumUrl = "$dir/$name.sha256",
                approxBytes = 4L * 1024 * 1024,
                label = "Alpine $latest ($arch)"
            )
        }
    }

    /** 目录索引也优先走国内镜像；第一个能解析出版的镜像即用。 */
    private fun latestAlpine(arch: String): String? {
        val re = Regex("alpine-minirootfs-([0-9]+(?:\\.[0-9]+){1,3})-$arch\\.tar\\.gz")
        for (base in ALPINE_BASES) {
            val html = runCatching { Http.getText("$base/$ALPINE_BRANCH/releases/$arch/") }
                .getOrNull() ?: continue
            val version = re.findAll(html)
                .map { it.groupValues[1] }
                .maxByOrNull { versionKey(it) }
            if (version != null) return version
        }
        return null
    }

    private fun versionKey(version: String): Long {
        val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
        var acc = 0L
        for (i in 0 until 4) acc = acc * 1000L + (parts.getOrNull(i) ?: 0)
        return acc
    }

    /** Kali 只提供 NetHunter full 一份，体积约 1.7 GB。 */
    private fun kali(abi: String): List<RootfsSource> {
        val arch = archFor(abi)
        val name = "kali-nethunter-rootfs-full-$arch.tar.xz"
        return KALI_BASES.map { base ->
            RootfsSource(
                distro = DistroId.KALI,
                url = "$base/$name",
                fileName = name,
                sumUrl = "$base/SHA256SUMS",
                approxBytes = 1837L * 1024 * 1024,
                label = "Kali NetHunter full ($arch)"
            )
        }
    }

    private fun debian(abi: String): List<RootfsSource> {
        val arch = archFor(abi)
        val entry = runCatching { latestLxc(arch) }.getOrNull()
        // 索引解析失败时用写死的快照目录，仍然对所有候选镜像生效。
        val path = (entry ?: "/images/debian/$DEBIAN_SUITE/$arch/default/${DEBIAN_FALLBACK_TS}/")
            .replace(":", "%3A")
        return LXC_BASES.map { base ->
            val dirUrl = "$base$path"
            RootfsSource(
                distro = DistroId.DEBIAN,
                url = "${dirUrl}rootfs.tar.xz",
                fileName = "debian-$DEBIAN_SUITE-$arch-rootfs.tar.xz",
                // LXC 目录的 SHA256SUMS 只有 rootfs.tar.xz / meta.tar.xz 两个条目，
                // 不能拿本地改名后的 fileName 去匹配。
                checksumEntry = "rootfs.tar.xz",
                sumUrl = "${dirUrl}SHA256SUMS",
                approxBytes = 34L * 1024 * 1024,
                label = "Debian $DEBIAN_SUITE ($arch)"
            )
        }
    }

    /**
     * LXC 镜像索引每行：distro;release;arch;variant;timestamp;/images/...
     * 取同一 arch、default 变体里时间戳最大的一条；索引同样优先国内镜像。
     */
    private fun latestLxc(arch: String): String? {
        val prefix = "debian;$DEBIAN_SUITE;$arch;default;"
        for (base in LXC_BASES) {
            val text = runCatching { Http.getText("$base/meta/1.0/index-system") }
                .getOrNull() ?: continue
            val hit = text.lineSequence()
                .filter { it.startsWith(prefix) }
                .maxByOrNull { it.split(';').getOrNull(4) ?: "" }
                ?.split(';')
                ?.getOrNull(5)
                ?.trim()
                ?.takeIf { it.startsWith("/images/") }
            if (hit != null) return hit
        }
        return null
    }
}
