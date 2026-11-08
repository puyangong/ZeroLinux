package com.zerolinux.app.ui.files

import com.zerolinux.app.files.GuestPaths

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerolinux.app.R
import com.zerolinux.app.engine.TerminalLogStore
import com.zerolinux.app.files.FileOps
import com.zerolinux.app.files.RootfsBrowser
import com.zerolinux.app.files.ShortcutStore
import com.zerolinux.app.ui.common.ZlGhostButton
import com.zerolinux.app.ui.common.ZlTopBar
import com.zerolinux.app.ui.theme.ZlAccent
import com.zerolinux.app.ui.theme.ZlBorder
import com.zerolinux.app.ui.theme.ZlDanger
import com.zerolinux.app.ui.theme.ZlFg
import com.zerolinux.app.ui.theme.ZlMonoBody
import com.zerolinux.app.ui.theme.ZlMonoSmall
import com.zerolinux.app.ui.theme.ZlMuted
import com.zerolinux.app.ui.theme.ZlPinned
import com.zerolinux.app.ui.theme.ZlRunning
import com.zerolinux.app.ui.theme.ZlSurface
import com.zerolinux.app.ui.theme.ZlSurfaceAlt
import com.zerolinux.app.ui.theme.ZlUnread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

private const val OBSERVE_MASK = FileObserver.CREATE or FileObserver.DELETE or
    FileObserver.MODIFY or FileObserver.ATTRIB or FileObserver.MOVED_FROM or
    FileObserver.MOVED_TO

/** 侧栏当前浏览的「根」：容器 rootfs、设备真实 SD 卡，或该容器的终端日志目录。 */
private enum class Zone { CONTAINER, SD, LOG }

private val STORAGE_PERMS = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE
)

/**
 * 目录列表缓存。
 *
 * 抽屉关闭时 Compose 会把 [FileDrawer] 移出组合树，`remember` 里的 entries 随之
 * 丢弃，重开时 `LaunchedEffect(path, zone, …)` 必然再跑一次 —— 没有缓存就会重读
 * 一遍目录（大目录肉眼可见地卡一下），这正是「侧栏关了再开就刷新」。
 *
 * key = uuid|zone|path。只缓存成功读到的结果，最多 24 条，超了按插入顺序淘汰。
 */
private object DirCache {
    private const val MAX = 24
    private val map = LinkedHashMap<String, List<RootfsBrowser.Entry>>()

    @Synchronized
    fun get(key: String): List<RootfsBrowser.Entry>? = map[key]

    @Synchronized
    fun put(key: String, entries: List<RootfsBrowser.Entry>) {
        if (map.size >= MAX) map.keys.firstOrNull()?.let { map.remove(it) }
        map[key] = entries
    }

    /** 用户主动刷新 / 增删改之后：该容器的所有目录缓存一次性作废。 */
    @Synchronized
    fun clearContainer(uuid: String) {
        map.keys.filter { it.startsWith("$uuid|") }.forEach { map.remove(it) }
    }
}

/**
 * 容器初始化 / 重建后由容器管理页（`:main`）调用：
 * 作废该容器的目录缓存并通知 DocumentsProvider，下次打开文件抽屉会重读根目录。
 *
 * 注意 [FileDrawer] 跑在 `:terminal` 进程，跨进程清不到这里的静态缓存；
 * 抽屉里对 rootfs 的 FileObserver 会在文件变化后自行 tick，这里再补一次
 * provider 通知，保证 DocumentsUI 一侧也重新枚举。
 */
fun invalidateContainerRoot(ctx: Context, uuid: String) {
    DirCache.clearContainer(uuid)
    DrawerMemo.clear(uuid)
    runCatching {
        val authority = ctx.packageName + ".documents"
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
        ctx.contentResolver.notifyChange(
            DocumentsContract.buildDocumentUri(authority, "container:$uuid/"),
            null
        )
    }
}

/**
 * 抽屉的「上次位置」记忆（进程内单例，按容器 uuid 存）。
 *
 * 抽屉关闭时 Compose 会把整个 [FileDrawer] 移出组合树，`remember` 里的
 * zone / path / 滚动位置全部丢弃 —— [DirCache] 只缓存 entries，不足以让用户
 * 回到上次看的目录。这里记住 zone + path + 首可见项，重新打开直接还原。
 *
 * 失效场景：进程被杀（纯内存）；切换容器（换了 uuid 自然没有记忆，回到根目录）。
 */
private object DrawerMemo {
    data class Snapshot(
        val zone: String,
        val path: String,
        val index: Int,
        val offsetPx: Int
    )

    private val map = HashMap<String, Snapshot>()

    @Synchronized
    fun get(uuid: String): Snapshot? = map[uuid]

    @Synchronized
    fun put(uuid: String, snapshot: Snapshot) {
        map[uuid] = snapshot
    }

    /** 切换容器时清掉旧容器的记忆（重新进去要回到根目录）。 */
    @Synchronized
    fun clear(uuid: String) {
        map.remove(uuid)
    }
}

/** 目录分页大小：先读一屏多一点，滚动到底再追加下一页。 */
private const val FILE_PAGE_SIZE = 80

/**
 * 右侧文件抽屉。直接读宿主 rootfs 目录，不经过 proot；
 * 展示时把宿主前缀映射回 guest 路径。
 *
 * 侧栏里的「快捷方式」有两类，都按列表项渲染（不再堆在顶栏/顶部横条）：
 *  - 固定项「容器根目录」→ 当前容器 rootfs，始终置顶、不可删除；
 *  - 固定项「SD 卡」→ 真实外部存储，不可删除；
 *  - 用户创建的软链快捷方式 → 可进入、长按可删（删的只是这条记录）。
 *
 * 顺序固定：容器根目录 → SD 卡 → 用户快捷方式 → 分隔线 → 目录条目。
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
fun FileDrawer(
    ctx: Context,
    uuid: String,
    label: String,
    onOpenTerminal: (String, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerBrowser = remember(uuid) { RootfsBrowser(ctx, uuid) }
    val sdRoot = remember { Environment.getExternalStorageDirectory() }
    val sdBrowser = remember(uuid) { RootfsBrowser(ctx, uuid, sdRoot) }
    // 日志目录不依赖 rootfs：容器名就是顶层目录名，直接当浏览根用。
    val logBrowser = remember(uuid, label) {
        RootfsBrowser(ctx, uuid, TerminalLogStore.containerDir(label))
    }
    val shortcutStore = remember { ShortcutStore(ctx) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }

    // 上次关闭抽屉时记下的 zone / path / 滚动位置（进程内单例，按 uuid 存）。
    val restored = remember(uuid) { DrawerMemo.get(uuid) }
    var zone by remember(uuid) {
        mutableStateOf(
            when (restored?.zone) {
                Zone.SD.name -> Zone.SD
                Zone.LOG.name -> Zone.LOG
                else -> Zone.CONTAINER
            }
        )
    }
    // 所有文件操作都走这里，切换根目录时不需要改调用点。
    val browser = when (zone) {
        Zone.SD -> sdBrowser
        Zone.LOG -> logBrowser
        Zone.CONTAINER -> containerBrowser
    }

    var path by remember(uuid) { mutableStateOf(restored?.path ?: "/") }
    // 用户主动跳到根时忽略记忆，直接回顶部（见 jumpTo）。
    var pendingTopReset by remember(uuid) { mutableStateOf(false) }

    // 切换容器（active uuid 变化）：旧容器的记忆立即作废，下次进去回到根目录。
    val prevUuid = remember { arrayOfNulls<String>(1) }
    SideEffect {
        val old = prevUuid[0]
        if (old != uuid) {
            if (old != null) DrawerMemo.clear(old)
            prevUuid[0] = uuid
        }
    }
    // 隐藏文件（.bashrc、.config 等）始终显示，不再提供开关。
    // 初值优先取缓存：抽屉关掉再打开时，Compose 会重建这份 state，
    // 先回填缓存就不会出现「开关抽屉 = 重新读一遍目录」的观感与开销。
    var entries by remember {
        mutableStateOf(DirCache.get("$uuid|${zone.name}|$path") ?: emptyList())
    }
    // 分页状态：entryOffset 是下一次要读的目录名下标，不是 entries.size
    // （stat 失败的条目会被丢掉，不能拿列表长度当偏移）。
    var entryOffset by remember { mutableIntStateOf(entries.size) }
    var hasMore by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    // 快捷方式读盘：初值给空，进 IO 线程回填，避免打开抽屉时同步读文件。
    var shortcuts by remember {
        mutableStateOf<List<ShortcutStore.Shortcut>>(emptyList())
    }
    LaunchedEffect(uuid) {
        shortcuts = withContext(Dispatchers.IO) { shortcutStore.all(uuid) }
    }

    // 本次组合是不是「刚打开抽屉」的第一趟。只在第一趟允许吃缓存，
    // 之后 path / zone 变化一律重新读盘（那是真的换了目录）。
    val startupGate = remember { booleanArrayOf(true) }

    // 跳系统设置授权「所有文件访问」后要自动回到 SD 卡。
    var pendingSdOpen by remember { mutableStateOf(false) }
    var showAllFilesDialog by remember { mutableStateOf(false) }

    var sheetTarget by remember { mutableStateOf<RootfsBrowser.Entry?>(null) }
    var deleteShortcut by remember { mutableStateOf<ShortcutStore.Shortcut?>(null) }
    var menuShortcut by remember { mutableStateOf<ShortcutStore.Shortcut?>(null) }
    var menuLog by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf<RootfsBrowser.Entry?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    /** 主动跳转：换 zone/path 并回到顶部（容器根目录 / SD 卡 / 快捷方式）。 */
    fun jumpTo(targetZone: Zone, targetPath: String) {
        if (zone == targetZone && path == targetPath) {
            // 路径没变不会触发重载，直接滚回顶部。
            scope.launch { runCatching { listState.scrollToItem(0) } }
            return
        }
        pendingTopReset = true
        zone = targetZone
        path = targetPath
    }

    // 提示条自动消失，不再提供「取消」按钮。
    LaunchedEffect(note) {
        if (note != null) {
            kotlinx.coroutines.delay(1800)
            note = null
        }
    }

    // 追加下一页；entryOffset 是目录名下标，不是 entries.size。
    suspend fun loadMore() {
        if (!hasMore || loadingMore) return
        loadingMore = true
        val page = withContext(Dispatchers.IO) {
            runCatching { browser.listPage(path, true, entryOffset, FILE_PAGE_SIZE) }
                .getOrDefault(RootfsBrowser.Page(emptyList(), entryOffset, false))
        }
        if (page.entries.isNotEmpty()) entries = entries + page.entries
        entryOffset = page.nextOffset
        hasMore = page.hasMore
        loadingMore = false
    }

    // 目录内容加载（zone 变了要重新列，否则 SD 卡会沿用 rootfs 的列表）。
    //
    // 刷新时机严格限定为：path / zone 变化、tick++（刷新按钮、文件系统事件、
    // 增删改之后）。关掉抽屉再打开的「第一趟」命中 [DirCache] 就只回填，
    // 不再读盘 —— 这就是「关了再开不刷新」。
    LaunchedEffect(uuid, path, zone, tick) {
        val key = "$uuid|${zone.name}|$path"
        val firstPass = startupGate[0].also { startupGate[0] = false }
        val cached = if (firstPass && tick == 0) DirCache.get(key) else null
        if (cached != null) {
            entries = cached
            entryOffset = cached.size
            hasMore = false
        } else {
            loadingMore = true
            val page = withContext(Dispatchers.IO) {
                runCatching { browser.listPage(path, true, 0, FILE_PAGE_SIZE) }
                    .getOrDefault(RootfsBrowser.Page(emptyList(), 0, false))
            }
            entries = page.entries
            entryOffset = page.nextOffset
            hasMore = page.hasMore
            loadingMore = false
            // 只有整目录一次读完才进缓存：大目录只缓存第一页，重开时
            // 会把「还有下一页」误判成到底。
            if (!page.hasMore) DirCache.put(key, page.entries)
        }
        // 重开抽屉：回到上次的首可见项。主动跳转（jumpTo）一律回顶部。
        val memo = if (firstPass) restored else null
        when {
            pendingTopReset -> {
                pendingTopReset = false
                runCatching { listState.scrollToItem(0) }
            }
            memo != null && memo.zone == zone.name && memo.path == path &&
                memo.index > 0 && memo.index < entries.size -> {
                runCatching { listState.scrollToItem(memo.index, memo.offsetPx) }
            }
        }
    }

    // 滚到接近底部时追加下一页。hasMore / entryOffset 通过状态委托读取，
    // 故意不放进 key，避免加载过程中重启协程导致状态更新被取消。
    LaunchedEffect(listState, path, zone, tick) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (!hasMore || loadingMore) return@collect
                val total = listState.layoutInfo.totalItemsCount
                if (last >= 0 && last >= total - 6) loadMore()
            }
    }

    // 「所有文件访问」是跳到系统设置里授权的，没有回调。用户切回来后再判一次，
    // 成功才真的切到 SD 卡（失败就保持原样，不会进去看到空目录）。
    LaunchedEffect(pendingSdOpen) {
        if (!pendingSdOpen) return@LaunchedEffect
        repeat(120) {
            kotlinx.coroutines.delay(500)
            if (hasAllFilesAccess()) {
                pendingSdOpen = false
                showAllFilesDialog = false
                jumpTo(Zone.SD, "/")
                return@LaunchedEffect
            }
        }
        pendingSdOpen = false
    }

    // 离开目录 / 关闭抽屉时记住 zone + path + 滚动位置，重开由 DrawerMemo 还原。
    DisposableEffect(uuid, path, zone) {
        val leavingZone = zone.name
        val leavingPath = path
        val leavingUuid = uuid
        onDispose {
            runCatching {
                DrawerMemo.put(
                    leavingUuid,
                    DrawerMemo.Snapshot(
                        zone = leavingZone,
                        path = leavingPath,
                        index = listState.firstVisibleItemIndex,
                        offsetPx = listState.firstVisibleItemScrollOffset
                    )
                )
            }
        }
    }

    // 当前目录 mtime 实时（300ms 去抖）
    DisposableEffect(path, zone) {
        val dir = runCatching { browser.hostOf(path) }.getOrDefault(File("/nonexistent"))
        var lastAt = 0L
        val observer = object : FileObserver(dir.absolutePath, OBSERVE_MASK) {
            override fun onEvent(event: Int, name: String?) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAt < 300L) return
                lastAt = now
                handler.postDelayed({ tick++ }, 300)
            }
        }
        runCatching { observer.startWatching() }
        onDispose { runCatching { observer.stopWatching() } }
    }

    DisposableEffect(Unit) {
        onDispose { handler.removeCallbacksAndMessages(null) }
    }

    // 完整路径弹窗的内容（null = 关闭）。路径行一律单行省略，
    // 长按 / 「⋯」→「查看完整路径」时才把全量路径放出来供选择与复制。
    // 完整路径不再单独弹窗：直接展示在各条目长按 /「⋯」菜单的顶部。
    // 内置快捷方式（容器根目录 / SD 卡）没有独立菜单状态，用路径本身驱动「⋯」菜单。
    var defaultMenuPath by remember { mutableStateOf<String?>(null) }
    // 内置快捷方式菜单对应的 guest 路径：容器根是容器内的 "/"，SD 卡是阶段 AH3 绑定到 guest 的 "/sdcard"。
    // 与 defaultMenuPath（宿主绝对路径）分开记录，避免在菜单里靠字符串比较猜是哪一项。
    var defaultMenuGuest by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        shortcuts = shortcutStore.all(uuid)
        // 主动刷新 / 增删改之后必须重读，缓存一律作废，否则会看到旧列表。
        DirCache.clearContainer(uuid)
        tick++
    }

    fun sdAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return hasAllFilesAccess()
        if (!hasStoragePermission(ctx)) return false
        return runCatching { sdRoot.isDirectory && sdRoot.canRead() }.getOrDefault(false)
    }

    fun enterSdCard() {
        // 不 tick++：zone/path 变化本身就是刷新条件，多打一次会白读一遍目录。
        jumpTo(Zone.SD, "/")
    }

    /** 弹「所有文件访问」说明框，同时登记回来后的自动重判。 */
    fun askAllFiles() {
        note = ctx.getString(R.string.zl_files_perm_all_files_msg)
        pendingSdOpen = true
        showAllFilesDialog = true
    }

    /**
     * 进入 SD 卡。权限判定顺序（每一步都先确认拿到权限才切区，
     * 否则用户进去只会看到一个空目录，误以为 SD 卡是空的）：
     *
     *  1. 已经有访问权（13+ = 「所有文件访问」；12- = 运行时权限 + 根目录可读）→ 直接进；
     *  2. Android 13+：READ_EXTERNAL_STORAGE 已不再授予，只能弹说明并跳
     *     「所有文件访问」设置页 —— 这个权限 requestPermissions 永远拿不到；
     *  3. Android 12 及以下：先走运行时权限；
     *  4. 拿到运行时权限但根目录仍读不到（11/12 的分区存储）→ 同样退到「所有文件访问」。
     */
    fun openSdCard() {
        if (sdAccessGranted()) {
            enterSdCard()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            askAllFiles()
            return
        }
        if (!hasStoragePermission(ctx)) {
            val storageMsg = ctx.getString(R.string.zl_files_perm_storage_msg)
            note = storageMsg
            Toast.makeText(ctx, storageMsg, Toast.LENGTH_SHORT).show()
            runCatching { (ctx as? Activity)?.requestPermissions(STORAGE_PERMS, 0x51) }
            return
        }
        askAllFiles()
    }

    /**
     * 侧栏不再提供删除文件/文件夹的能力（只允许长按删除「用户自己建的快捷方式」），
     * 因此原来的 deleteEntry(entry) 已整体移除，避免任何遗留调用路径。
     */

    val crumbs = remember(path) { crumbsOf(path) }
    // 上级目录：根目录没有上级，直接隐藏这一项。
    val parentPath = remember(path, zone) { browser.parentOf(path) }

    Column(
        modifier = modifier
            // 拖满整个抽屉容器，宽度由调用方（TerminalActivity）唯一决定。
            .fillMaxSize()
            // 背景贴边：只有左侧两个角是圆的，右侧与屏幕右沿齐平。
            // clip/background 在前，inset padding 在后 —— 背景满宽，内容避开
            // 状态栏与导航条（顺序交换的话背景会被 inset 撑出空隙）。
            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            .background(ZlSurface)
            .safeDrawingPadding()
    ) {
        // 顶栏副标题只放当前路径：快捷方式一律在下面的列表里，不在这里重复。
        ZlTopBar(
            title = label,
            subtitle = path,
            actions = {
                // 刷新：IconButton 提供 48dp 触控区，字形放大到 26sp。
                IconButton(onClick = { refresh() }) {
                    Text("\u21bb", fontSize = 26.sp, color = ZlMuted)
                }
                TextButton(onClick = {
                    copyText(ctx, path)
                    note = ctx.getString(R.string.copied)
                }) { Text(ctx.getString(R.string.files_copy_path), style = ZlMonoSmall, color = ZlMuted) }
            }
        )

        // 面包屑
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Text(" / ", style = ZlMonoSmall, color = ZlBorder)
                }
                Text(
                    text = crumb.second,
                    style = ZlMonoSmall,
                    color = if (index == crumbs.lastIndex) ZlAccent else ZlMuted,
                    modifier = Modifier.clickable { path = crumb.first }
                )
            }
        }

        pickSource?.let { source ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
                color = ZlPinned.copy(alpha = 0.12f)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        text = ctx.getString(R.string.files_pick_hint, source.name),
                        style = ZlMonoSmall,
                        color = ZlPinned
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZlGhostButton(ctx.getString(R.string.files_pick_here)) {
                            val target = path
                            val sourcePath = source.guestPath
                            val sourceName = source.name
                            val outcome = runCatching {
                                FileOps.createLink(containerBrowser, sourcePath, target, sourceName)
                            }.getOrElse { FileOps.Outcome(false, it.message ?: "error") }
                            if (outcome.ok) {
                                shortcutStore.add(
                                    ShortcutStore.Shortcut(
                                        id = shortcutStore.newId(),
                                        containerId = uuid,
                                        linkGuestPath = joinGuest(target, sourceName),
                                        targetGuestPath = sourcePath,
                                        name = sourceName,
                                        createdAt = System.currentTimeMillis()
                                    )
                                )
                                note = ctx.getString(R.string.files_shortcut_created)
                                pickSource = null
                                refresh()
                            } else {
                                note = outcome.error
                            }
                        }
                        ZlGhostButton(ctx.getString(R.string.action_cancel)) { pickSource = null }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            if (parentPath != null) {
                // 上级目录固定悬浮在列表顶部：滚动浏览深层目录时不用先滑回顶部，
                // 也不能被下面的条目顶走。背景必须不透明，否则滚动内容会透出来。
                stickyHeader(key = "shortcut-parent") {
                    Box(modifier = Modifier.fillMaxWidth().background(ZlSurface)) {
                        ShortcutRow(
                            iconRes = R.drawable.ic_arrow_up,
                            name = ctx.getString(R.string.files_parent),
                            subtitle = parentPath,
                            iconTint = ZlMuted,
                            iconBg = ZlSurfaceAlt,
                            onClick = { jumpTo(zone, parentPath) }
                        )
                    }
                }
            }
            item(key = "shortcuts-label") {
                Text(
                    text = ctx.getString(R.string.files_shortcuts),
                    style = ZlMonoSmall,
                    color = ZlMuted,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
                )
            }
            // 固定项 1：容器根目录（rootfs）。始终置顶、不可删除。
            item(key = "shortcut-container-root") {
                ShortcutRow(
                    iconRes = R.drawable.ic_folder,
                    name = ctx.getString(R.string.zl_files_container_root),
                    subtitle = containerBrowser.rootfs.absolutePath,
                    // 内置项配色：容器根=蓝（ZlPinned），与用户快捷方式的强调色区分开。
                    iconTint = ZlPinned,
                    iconBg = ZlPinned.copy(alpha = 0.16f),
                    onClick = { jumpTo(Zone.CONTAINER, "/") },
                    // 长按与「⋯」打开同一个菜单：顶部就是完整路径 + 复制。
                    // guest 路径固定为容器内的 "/"（rootfs.absolutePath 是宿主路径，容器内不存在）。
                    onLongClick = {
                        defaultMenuPath = containerBrowser.rootfs.absolutePath
                        defaultMenuGuest = "/"
                    },
                    onMenu = {
                        defaultMenuPath = containerBrowser.rootfs.absolutePath
                        defaultMenuGuest = "/"
                    }
                )
            }
            // 固定项 2：SD 卡。始终置顶、不可删除。
            item(key = "shortcut-sd") {
                ShortcutRow(
                    iconRes = R.drawable.ic_sd_card,
                    name = ctx.getString(R.string.files_sd_card),
                    subtitle = sdRoot.absolutePath,
                    // 内置项配色：SD 卡=绿（ZlRunning）。
                    iconTint = ZlRunning,
                    iconBg = ZlRunning.copy(alpha = 0.16f),
                    onClick = { openSdCard() },
                    // 长按与「⋯」打开同一个菜单：顶部就是完整路径 + 复制。
                    // guest 路径固定为 "/sdcard"（阶段 AH3 已把真实外部存储绑到这里）。
                    onLongClick = {
                        defaultMenuPath = sdRoot.absolutePath
                        defaultMenuGuest = "/sdcard"
                    },
                    onMenu = {
                        defaultMenuPath = sdRoot.absolutePath
                        defaultMenuGuest = "/sdcard"
                    }
                )
            }
            // 固定项 3：该容器的终端日志。没有删除，长按 / 「⋯」都只弹「打开」。
            item(key = "shortcut-log") {
                ShortcutRow(
                    iconRes = R.drawable.ic_folder,
                    name = "log",
                    subtitle = ctx.getString(R.string.files_log_desc),
                    // 内置项配色：log=橙（ZlUnread）。
                    iconTint = ZlUnread,
                    iconBg = ZlUnread.copy(alpha = 0.16f),
                    onClick = { jumpTo(Zone.LOG, "/") },
                    onLongClick = { menuLog = true },
                    longClickLabel = ctx.getString(R.string.action_open),
                    onMenu = { menuLog = true }
                )
            }
            items(shortcuts, key = { "shortcut-" + it.id }) { sc ->
                ShortcutRow(
                    iconRes = R.drawable.ic_folder,
                    name = sc.name,
                    subtitle = sc.targetGuestPath,
                    onClick = {
                        val guest = GuestPaths.normalizeGuest(sc.targetGuestPath)
                        val sdPath = GuestPaths.guestToSd(guest)
                        if (sdPath != null) jumpTo(Zone.SD, sdPath)
                        else jumpTo(Zone.CONTAINER, containerBrowser.normalize(guest))
                    },
                    onLongClick = { deleteShortcut = sc },
                    longClickLabel = ctx.getString(R.string.zl_files_shortcut_delete_title),
                    onMenu = { menuShortcut = sc }
                )
            }
            item(key = "shortcuts-divider") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .height(1.dp)
                        .background(ZlBorder)
                )
            }

            if (entries.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(ctx.getString(R.string.files_empty), style = ZlMonoSmall, color = ZlMuted)
                    }
                }
            } else {
                itemsIndexed(entries, key = { _, e -> e.guestPath }) { _, entry ->
                    FileRow(
                        ctx = ctx,
                        entry = entry,
                        onClick = {
                            if (entry.isLink) {
                                // 链接先解析成真实目标：目录直接进，文件跳到所在目录；
                                // 断链 / 成环 / 越根统一提示「链接已失效」。
                                val target = browser.resolveLink(entry.guestPath)
                                if (target == null) {
                                    note = ctx.getString(R.string.files_link_broken)
                                } else {
                                    val targetDir = runCatching {
                                        browser.hostOf(target).isDirectory
                                    }.getOrDefault(false)
                                    val dest = if (targetDir) target else browser.parentOf(target)
                                    when {
                                        dest == null ->
                                            note = ctx.getString(R.string.files_link_broken)
                                        // SD / 日志浏览器的 guest 路径以各自根为基准，保持在原区。
                                        zone == Zone.SD || zone == Zone.LOG -> jumpTo(zone, dest)
                                        dest == "/sdcard" || dest.startsWith("/sdcard/") -> {
                                            val sdPath = GuestPaths.guestToSd(dest)
                                            if (sdPath != null) jumpTo(Zone.SD, sdPath)
                                            else note = ctx.getString(R.string.files_link_broken)
                                        }
                                        else -> jumpTo(Zone.CONTAINER, dest)
                                    }
                                }
                            } else if (entry.isDir) {
                                path = entry.guestPath
                            } else {
                                note = ctx.getString(R.string.files_not_dir)
                            }
                        },
                        onLongClick = { sheetTarget = entry },
                        onMore = { sheetTarget = entry }
                    )
                }
            }
        }

        note?.let { text ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text, style = ZlMonoSmall, color = ZlMuted, modifier = Modifier.weight(1f))
            }
        }
    }

    sheetTarget?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { sheetTarget = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = ZlSurface
        ) {
            // 路径块在所有条目里都排第一：列表里只显示一行省略号，这里给全量路径。
            SheetPathBlock(ctx, entry.guestPath) {
                copyText(ctx, entry.guestPath)
                note = ctx.getString(R.string.copied)
                sheetTarget = null
            }
            SheetAction(ctx.getString(R.string.files_action_copy_name)) {
                copyText(ctx, entry.name)
                note = ctx.getString(R.string.copied)
                sheetTarget = null
            }
            SheetAction(ctx.getString(R.string.files_action_copy_path)) {
                copyText(ctx, entry.guestPath)
                note = ctx.getString(R.string.copied)
                sheetTarget = null
            }
            SheetAction(ctx.getString(R.string.files_action_copy_host)) {
                copyText(ctx, runCatching { browser.hostOf(entry.guestPath).absolutePath }.getOrDefault(""))
                note = ctx.getString(R.string.copied)
                sheetTarget = null
            }
            // 目录直接在终端里打开：写入 cd -- '<path>'，省去手打长路径。
            // SD 浏览器的 guestPath 是「相对 SD 根」的 /DCIM，容器里必须换成 /sdcard/DCIM。
            // 链接用 cd -P，让 shell 落到链接指向的物理目录。
            if (entry.isDir) {
                SheetAction(ctx.getString(R.string.files_action_open_terminal)) {
                    onOpenTerminal(
                        when (zone) {
                            // SD 浏览器的 guestPath 是「相对 SD 根」的 /DCIM，容器里必须换成 /sdcard/DCIM。
                            Zone.SD -> GuestPaths.sdToGuest(entry.guestPath)
                            // 日志浏览器同理：guestPath 相对日志根，换成 /sdcard/zerolinux/terminal/<容器>。
                            Zone.LOG -> GuestPaths.logToGuest(TerminalLogStore.safeName(label), entry.guestPath)
                            else -> entry.guestPath
                        },
                        entry.isLink
                    )
                    sheetTarget = null
                }
            }
            // 容器内建软链没问题；SD 卡只记一条指向 /sdcard/... 的快捷方式，
            // 绝不往宿主的真实 SD 卡里写软链。
            if (entry.isDir) {
                SheetAction(ctx.getString(R.string.files_action_shortcut)) {
                    if (zone == Zone.SD) {
                        shortcutStore.add(
                            ShortcutStore.Shortcut(
                                id = shortcutStore.newId(),
                                containerId = uuid,
                                linkGuestPath = "",
                                targetGuestPath = GuestPaths.sdToGuest(entry.guestPath),
                                name = entry.name,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        note = ctx.getString(R.string.files_shortcut_created)
                        shortcuts = shortcutStore.all(uuid)
                        sheetTarget = null
                    } else {
                        pickSource = entry
                        path = "/"
                        sheetTarget = null
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // 「所有文件访问」说明框：不是普通运行时权限，只能引导到系统设置页。
    if (showAllFilesDialog) {
        AlertDialog(
            onDismissRequest = { showAllFilesDialog = false },
            title = { Text(ctx.getString(R.string.zl_files_perm_all_files_title)) },
            text = {
                Column {
                    Text(ctx.getString(R.string.zl_files_perm_all_files_msg))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        ctx.getString(R.string.zl_files_perm_all_files_body),
                        style = ZlMonoSmall,
                        color = ZlMuted
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAllFilesDialog = false
                    openAllFilesSettings(ctx)
                }) { Text(ctx.getString(R.string.zl_files_perm_grant)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAllFilesDialog = false
                    pendingSdOpen = false
                }) { Text(ctx.getString(R.string.action_cancel)) }
            }
        )
    }

    // 删除快捷方式（唯一可删的东西）：长按用户快捷方式触发，二次确认。
    // 快捷方式右侧「⋯」：打开 / 删除，与长按触发的操作保持一致。
    menuShortcut?.let { sc ->
        ModalBottomSheet(
            onDismissRequest = { menuShortcut = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = ZlSurface
        ) {
            SheetPathBlock(ctx, sc.targetGuestPath) {
                copyText(ctx, sc.targetGuestPath)
                note = ctx.getString(R.string.copied)
                menuShortcut = null
            }
            // 快捷方式必定指向目录，可直接在终端里 cd 过去。
            SheetAction(ctx.getString(R.string.files_action_open_terminal)) {
                // 老数据可能是 /storage/emulated/0/...，归一成 /sdcard/... 再 cd。
                onOpenTerminal(GuestPaths.normalizeGuest(sc.targetGuestPath), false)
                menuShortcut = null
            }
            SheetAction(ctx.getString(R.string.action_open)) {
                menuShortcut = null
                val guest = GuestPaths.normalizeGuest(sc.targetGuestPath)
                val sdPath = GuestPaths.guestToSd(guest)
                if (sdPath != null) jumpTo(Zone.SD, sdPath)
                else jumpTo(Zone.CONTAINER, containerBrowser.normalize(guest))
            }
            SheetAction(ctx.getString(R.string.action_delete), color = ZlDanger) {
                menuShortcut = null
                deleteShortcut = sc
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // 默认 log 快捷方式：没有删除，长按与「⋯」都只给「打开」。
    if (menuLog) {
        ModalBottomSheet(
            onDismissRequest = { menuLog = false },
            sheetState = rememberModalBottomSheetState(),
            containerColor = ZlSurface
        ) {
            // 日志不是普通文件，但同样是一个真实目录（SD 卡上的 zerolinux/terminal/<容器>）。
            val logPath = TerminalLogStore.containerDir(label).absolutePath
            SheetPathBlock(ctx, logPath) {
                copyText(ctx, logPath)
                note = ctx.getString(R.string.copied)
                menuLog = false
            }
            // 终端里只认 guest 路径；logPath 是宿主绝对路径，容器内不存在。
            SheetAction(ctx.getString(R.string.files_action_open_terminal)) {
                menuLog = false
                onOpenTerminal(GuestPaths.logToGuest(TerminalLogStore.safeName(label), "/"), false)
            }
            SheetAction(ctx.getString(R.string.action_open)) {
                menuLog = false
                jumpTo(Zone.LOG, "/")
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    // 内置快捷方式（容器根目录 / sdcard）的菜单：完整路径 + 复制；这两项始终存在、不可删除。
    defaultMenuPath?.let { full ->
        ModalBottomSheet(
            onDismissRequest = {
                defaultMenuPath = null
                defaultMenuGuest = null
            },
            sheetState = rememberModalBottomSheetState(),
            containerColor = ZlSurface
        ) {
            SheetPathBlock(ctx, full) {
                copyText(ctx, full)
                note = ctx.getString(R.string.copied)
                defaultMenuPath = null
                defaultMenuGuest = null
            }
            // 内置项都是容器内可访问的目录，可直接 cd 过去；传 guest 路径而不是上面展示的宿主路径。
            defaultMenuGuest?.let { guest ->
                SheetAction(ctx.getString(R.string.files_action_open_terminal)) {
                    defaultMenuPath = null
                    defaultMenuGuest = null
                    onOpenTerminal(guest, false)
                }
            }
            Text(
                text = ctx.getString(R.string.zl_files_default_undeletable),
                style = ZlMonoSmall,
                color = ZlMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // 完整路径已在各菜单顶部直接展示（可选中、可复制），不再有单独弹窗。

    deleteShortcut?.let { sc ->
        AlertDialog(
            onDismissRequest = { deleteShortcut = null },
            title = { Text(ctx.getString(R.string.zl_files_shortcut_delete_title)) },
            text = { Text(ctx.getString(R.string.zl_files_shortcut_delete_confirm, sc.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = sc
                    deleteShortcut = null
                    shortcutStore.remove(target.id)
                    shortcuts = shortcutStore.all(uuid)
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.files_shortcut_removed),
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text(ctx.getString(R.string.action_delete), color = ZlDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteShortcut = null }) {
                    Text(ctx.getString(R.string.action_cancel))
                }
            }
        )
    }
}

/** 侧栏快捷方式的一行：图标 + 名称 + 目标路径。右侧不再有删除按钮，删除走长按。 */
@Composable
private fun ShortcutRow(
    iconRes: Int,
    name: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    onMenu: (() -> Unit)? = null,
    // 内置快捷方式各自配色：容器=蓝、SD=绿、log=橙；用户快捷方式走默认强调色。
    iconTint: androidx.compose.ui.graphics.Color = ZlAccent,
    iconBg: androidx.compose.ui.graphics.Color = ZlAccent.copy(alpha = 0.16f)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(iconBg, shape = RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = ZlMonoBody.copy(fontWeight = FontWeight.Medium),
                color = ZlFg,
                // 路径 / 名称只占一行：大字号下不再换行把整行撑高，超出显示省略号。
                // 完整路径在长按 /「⋯」菜单顶部直接展示。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = ZlMonoSmall,
                color = ZlMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onMenu != null) {
            IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
                Text("\u22ef", fontSize = 24.sp, color = ZlMuted)
            }
        }
    }
}

@Composable
private fun FileRow(
    ctx: Context,
    entry: RootfsBrowser.Entry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit
) {
    val tone = when {
        entry.isLink -> ZlPinned
        entry.isDir -> ZlAccent
        else -> ZlMuted
    }
    val iconRes = if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_file
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 32dp 圆角底 + 18dp 图标，文件夹用强调色，文件用 muted。
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (entry.isDir) ZlAccent.copy(alpha = 0.16f) else ZlSurfaceAlt,
                    shape = RoundedCornerShape(9.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = ZlMonoBody.copy(fontWeight = FontWeight.Medium),
                color = ZlFg,
                // 文件名可能很长：单行省略，完整路径在长按 /「⋯」菜单顶部看。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // 文件夹不显示大小（不递归统计，避免卡顿）；空文件是 "0 B"。
                text = if (entry.isDir) {
                    formatTime(entry.mtime)
                } else {
                    "${Formatter.formatShortFileSize(ctx, entry.size)}  ${formatTime(entry.mtime)}"
                },
                style = ZlMonoSmall,
                color = ZlMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.isLink) {
            Text(
                text = "link",
                style = ZlMonoSmall,
                color = ZlPinned
            )
        }
        // 长按之外再给一个显式的入口，避免用户找不到操作菜单。
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center
        ) {
            Text("\u22ef", fontSize = 20.sp, color = ZlMuted)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ZlBorder)
    )
}

/**
 * 长按 /「⋯」菜单顶部的路径块：完整路径直接展示（可选中），下面跟「复制路径」。
 * 文件、文件夹、快捷方式、log、容器根目录、sdcard 都走同一个菜单，所以都有这一块。
 */
@Composable
private fun SheetPathBlock(ctx: Context, fullPath: String, onCopy: () -> Unit) {
    if (fullPath.isBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
    ) {
        SelectionContainer {
            Text(
                text = fullPath,
                style = ZlMonoSmall,
                color = ZlMuted,
                // 太长的路径最多三行，再长就省略，不让菜单被顶出屏幕。
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ctx.getString(R.string.files_copy_path),
                style = ZlMonoSmall,
                color = ZlAccent
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .height(1.dp)
            .background(ZlBorder)
    )
}

@Composable
private fun SheetAction(text: String, color: Color = ZlFg, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = ZlMonoBody.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

private fun hasStoragePermission(ctx: Context): Boolean = runCatching {
    ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
}.getOrDefault(true)

/**
 * 是否已拿到「所有文件访问」（MANAGE_EXTERNAL_STORAGE）。
 * 低版本没有这个概念，恒为 true，调用方据此只会落到运行时权限分支。
 */
private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < 30 ||
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

/**
 * 跳系统设置授权页。这是特殊权限，只能用户手动开：
 * 先尝试本应用的授权页（部分 ROM 不支持），失败再退到全局列表。
 */
private fun openAllFilesSettings(ctx: Context) {
    val appPage = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${ctx.packageName}")
    )
    val opened = runCatching { ctx.startActivity(appPage) }.isSuccess
    if (opened) return
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

private fun copyText(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("zerolinux", text))
    }
}

private fun joinGuest(dir: String, name: String): String =
    if (dir == "/") "/$name" else "${dir.trimEnd('/')}/$name"

/** 返回 (guestPath, 显示名) 列表，根为 "/"。 */
private fun crumbsOf(path: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    out.add("/" to "/")
    if (path == "/") return out
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    var acc = ""
    parts.forEach { part ->
        acc = "$acc/$part"
        out.add(acc to part)
    }
    return out
}

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "-" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
