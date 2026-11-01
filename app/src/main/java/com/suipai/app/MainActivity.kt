package com.suipai.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.suipai.app.data.Favorites
import com.suipai.app.data.Hidden
import com.suipai.app.data.MediaRepository
import com.suipai.app.nav.AppNavHost
import com.suipai.app.state.AppState
import com.suipai.app.state.BatchActions
import com.suipai.app.state.ChromeState
import com.suipai.app.state.DeleteFlow
import com.suipai.app.state.NavState
import com.suipai.app.state.AppRoute
import com.suipai.app.state.SelectionState
import com.suipai.app.settings.OnboardingGate
import com.suipai.app.settings.SettingsStore
import com.suipai.app.data.Trash
import com.suipai.app.state.ShareImport
import com.suipai.app.ui.PermissionGate
import com.suipai.app.ui.theme.SuiPaiTheme

/**
 * 系统相机那条路的落点：`cacheDir/camera/system.jpg`（`res/xml/file_paths.xml` 声明过
 * `cache-path camera/`，所以推导安全）。返回 null 只可能是 FileProvider 配不上，
 * 调用点必须把这句话说到屏幕上，不许静默。
 */
internal fun systemCaptureTarget(context: android.content.Context): android.net.Uri? = runCatching {
    val file = java.io.File(context.cacheDir, "camera/system.jpg")
    file.parentFile?.mkdirs()
    androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
} .onFailure { android.util.Log.w("SuiPaiCamera", "推系统相机的 uri 失败", it) }.getOrNull()

/**
 * 应用唯一的 Activity。**职责只有七件事**（改前它自己是一个 1172 行的 composable，
 * 里面有 ~40 个状态、9 个批量动作、删除授权链与分享导入）：
 *
 * 1. 生命周期（`onCreate` / `onNewIntent`）；
 * 2. `setContent` + 主题；
 * 3. 权限门（[PermissionGate]）；
 * 4. 建 [AppState] 与它的协作者（repo / favorites / hidden / 导航 / 批量 / 删除 / 分享）；
 * 5. **两个 `ActivityResultLauncher`**（删除授权框 / 系统相机）—— 它们必须挂在 Activity 上，
 *    所以由这里持有、用回调把结果交给 [DeleteFlow] 与 [AppState]；
 * 6. 系统栏图标明暗跟 `darkUi`；
 * 7. intent 收集（分享 / 桌面快捷方式）。
 *
 * 别的都不在这里：数据状态在 `state/`，导航在 `nav/`（规则在纯函数的 `NavPolicy` 里）。
 * 新界面接入见 `docs/arch/APP-ARCH.md` 的 5 步模板 —— 不再需要动这个文件。
 */
class MainActivity : ComponentActivity() {
    /**
     * 分享链路只在可调试包里出声：URI 属于用户内容，不能进正式包的日志。
     * 必须 lazy —— Activity 的属性初始化跑在 attachBaseContext 之前，
     * 那时候 getApplicationInfo() 还是空的（踩过：直接 NPE 在启动上）。
     */
    private val verbose: Boolean by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /** 别的 App「分享 → 随拍」进来的图；onCreate 与 onNewIntent 都往这里放，组合作用域消费。 */
    private var shareInbox by mutableStateOf<List<Uri>>(emptyList())

    /** 桌面长按图标的三个快捷方式落在这里（suipai://camera|sync|yishun 的 host 段）。 */
    private var launchHop by mutableStateOf<String?>(null)

    private fun collectHop(intent: Intent?) {
        launchHop = intent?.data?.host?.takeIf { it == "camera" || it == "sync" || it == "yishun" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        collectShare(intent)
        collectHop(intent)
        setContent {
            // prefs 句柄要 remember：`getSharedPreferences` 里带一个同步 Map，
            // 首帧那一次还是主线程上的磁盘读；不 remember 就是**每次根作用域重组**都再来一遍。
            val rootPrefs = remember { getSharedPreferences("suipai", android.content.Context.MODE_PRIVATE) }
            val chrome = remember { ChromeState(rootPrefs) }
            // 系统栏已经全透明，图标明暗只能自己跟着 App 内的暗房/日光开关：
            // 不跟着设的话，日光（相纸白）页面上会是一排白色状态栏图标，直接看不见。
            LaunchedEffect(chrome.darkUi) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !chrome.darkUi
                controller.isAppearanceLightNavigationBars = !chrome.darkUi
            }
            SuiPaiTheme(dark = chrome.darkUi) {
                PermissionGate {
                    val context = LocalContext.current
                    val repo = remember { MediaRepository(context) }
                    val favorites = remember { Favorites(context) }
                    val hidden = remember { Hidden(context) }
                    val scope = rememberCoroutineScope()
                    // 导航层的状态原来全是普通 `remember`：Manifest 只接管 orientation|screenSize|
                    // keyboardHidden|fontScale，于是进分屏、折叠屏换外屏、切系统深色一律重建 MainActivity，
                    // 用户就被静默扔回照片网格 —— 正在编辑的一瞬、打开的查看器、选中的一批格子全丢。
                    // 现在整条返回栈走一个 `List<String>` 进 Bundle（不用 Parcelable），
                    // 坏数据一律降级回照片页（见 `BackStack.restore`）—— 静默丢状态不许变成崩溃。
                    val nav = rememberSaveable(saver = NavState.Saver) { NavState() }
                    // 选中集是 `AppState` 里**唯一**能进 Bundle 的一格：`AppState` 整体是普通
                    // `remember`（它要持有 Context 与 CoroutineScope），所以重建后 photos /
                    // 查看器 / 相册内页都会重来，只有"选中的那一批格子"值得、也放得下。
                    // 恢复后靠已有的 `ON_RESUME → state.refresh()` 把 photos 读回来 ——
                    // Bundle 里**不存照片**（几个 MB 的数据，装不下也没必要）。
                    // 代价：读到 photos 之前选择条会闪一帧「已选 N 张 · 0 B」，接受它。
                    val selection = rememberSaveable(saver = SelectionState.Saver) { SelectionState() }
                    // ⚠️ 这个 `remember` **不要** key `selection`：`SelectionState` 是
                    // `rememberSaveable` 的产物，重建时它会是同一格状态里的新实例；
                    // 一旦在这里 key 上它，`AppState` 就会跟着重建，整个状态层被换掉，
                    // 导航、票据、批量进度全丢 —— 比"选择没保住"严重得多。
                    val state = remember {
                        AppState(
                            context = context,
                            repo = repo,
                            favorites = favorites,
                            hidden = hidden,
                            scope = scope,
                            nav = nav,
                            selection = selection,
                        )
                    }
                    val share = remember { ShareImport(state, scope).also { it.verbose = verbose } }
                    // 删除授权框：必须由 Activity 的 launcher 发起，所以这里用回调把
                    // 发起动作交给 `DeleteFlow`，其余（查库、清 id、说结论）全在它里面。
                    // 不能写成 `remember(launcher) { DeleteFlow(...) }`：launcher 在重建时会换身份，
                    // 那样 DeleteFlow 跟着重建、而票据在 `rememberSaveable` 里 —— 两者分家就会
                    // 出现"票还在、没人消费"（正是这条链最容易静默失败的地方）。
                    // 所以只建一次，回调里通过可变引用取当前 launcher。
                    val consentLauncher =
                        remember { mutableStateOf<(androidx.activity.result.IntentSenderRequest) -> Unit>({}) }
                    val deleteFlow = remember { DeleteFlow(state) { consentLauncher.value(it) } }
                    val deleteConsent = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartIntentSenderForResult(),
                    ) {
                        deleteFlow.onConsentResult()
                    }
                    consentLauncher.value = { deleteConsent.launch(it) }
                    val batch = remember { BatchActions(state, deleteFlow) }

                    // ── 启动时的三件事（① 各跑一次，都在 IO 上）─────────────
                    // ① 回收站过期清理（30 天）。失败静默 —— 它只是"顺手清",
                    //    失败了不该在用户面前报错；但 logcat 里要留证据（否则和没跑一样）。
                    // ② 首启引导：只在没看过时**自动**进；`OnboardingGate` 是纯判定，
                    //    读一份 SettingsSnapshot。这里只负责把它变成一次路由。
                    // ③ 桌面入口：动态快捷方式重发一遍 + 请系统刷新小组件。
                    LaunchedEffect(Unit) {
                        withContext(Dispatchers.IO) {
                            // 走 `Trash.of`（进程级单例）：它的 `lock` 是实例字段，
                            // 与 AppState 的删除搬家各持一个实例就会丢账单条目。
                            runCatching { Trash.of(context).purgeExpired(System.currentTimeMillis()) }
                                .onFailure { android.util.Log.w("SuiPaiTrash", "启动清理失败", it) }
                        }
                        // ── ③ 桌面小组件与动态快捷方式 ────────────────────────
                        //
                        // 两件事都是**幂等**的，所以调用点不用互相避让（见 `DynamicShortcuts.refresh`
                        // 的注释）：`refresh` 每次都是"把动态那几条设成当前集合"，
                        // `requestRefresh` 只是发一条广播请系统重画。
                        //
                        // 为什么放在**这里**（启动副作用），而不是在照片增删之后各调一次：
                        //  - `AppState.refresh()` 是照片增删之后唯一的汇合点，但它也被 `ON_RESUME`
                        //    触发 —— 挂在那里等于每次回到前台都发一条广播、重发一遍快捷方式；
                        //  - 而"照片增删"在这台设备上还可能是**别的应用**干的（相机、微信、截图），
                        //    我们根本收不到那个时刻。所以真正的完整性来自"每次启动重画一次"，
                        //    加上运行期那一处确实知道自己在改库的地方主动叫一声 ——
                        //    见 `AppNavHost` 顶部按 `state.photos.size` 变化触发的那一段。
                        //
                        // 两个都不许让启动变慢或变崩：
                        //  - `DynamicShortcuts.refresh` 内部整段 `runCatching`，且自己会查一次库
                        //    （只取 `_ID` 一列）；放到 IO 上是因为它真的会碰 ContentResolver。
                        //  - `requestRefresh` 只发一条广播，几十微秒。
                        withContext(Dispatchers.IO) {
                            runCatching { com.suipai.app.widget.DynamicShortcuts.refresh(context) }
                                .onFailure { android.util.Log.w(WIDGET_TAG, "启动时重发快捷方式没成", it) }
                        }
                        com.suipai.app.widget.RecentWidgetProvider.requestRefresh(context)
                        val store = SettingsStore.of(context)
                        if (OnboardingGate.shouldShow(store.load())) {
                            nav.push(AppRoute.Onboarding)
                        }
                    }

                    val systemCamera = rememberLauncherForActivityResult(
                        ActivityResultContracts.TakePicture(),
                    ) { ok ->
                        // 原来这里读的是 `systemCaptureUri` 那个 remember 状态：跳去外部相机那几分钟里
                        // 任何一次配置变化（分屏、字级、深色）都会重建 Activity 把它清空，于是
                        // `ok && uri != null` 不成立 —— 拍完那张静静躺在缓存里，屏幕一句都没有，
                        // 也不崩，所以验证套件只会记成"没问题"。这个 uri 本来就是常量路径推出来的，
                        // 没必要当状态存：在回调里现推，丢不了。
                        if (!ok) return@rememberLauncherForActivityResult   // 用户在相机里按了取消
                        val uri = systemCaptureTarget(context)
                        if (uri == null) {
                            state.batchNote = "系统相机那张没接住（缓存里的照片读不出来）"
                        } else {
                            nav.push(com.suipai.app.state.AppRoute.Yishun(uri.toString()))
                            state.refresh()
                        }
                    }

                    // ── intent 收集：分享 / 快捷方式 ──────────────────────
                    LaunchedEffect(shareInbox) {
                        share.receive(shareInbox)
                        // 交给 ShareImport 之后立刻清 Activity 这一份：
                        // 不清就会在下次重建时重复导入同一批（改前就是靠 removeExtra 防这个）。
                        shareInbox = emptyList()
                    }
                    // 桌面长按图标的三个快捷方式。camera/sync 只是切页；yishun 要挑「最近一张」，
                    // 相册还没加载完时无从下手，所以 key 上带 photos.size，到位前每次重组都再查一遍。
                    LaunchedEffect(launchHop, state.photos.size) {
                        val hop = launchHop ?: return@LaunchedEffect
                        when (hop) {
                            "camera" -> {
                                // 改前 `showCamera = true` 是一层**盖在**一瞬下面的相机
                                nav.insertBelowTop(com.suipai.app.state.AppRoute.Camera)
                                launchHop = null
                            }
                            "sync" -> {
                                // 改前 `tab = 2` 只换底座，屏幕不变（返回键退掉上层才落到互通）
                                nav.rebaseTo(com.suipai.app.state.AppRoute.Sync)
                                launchHop = null
                            }
                            else -> {
                                val newest = state.photos.firstOrNull() ?: return@LaunchedEffect
                                nav.removeFamilies("camera", "viewer")
                                state.clearSelection()
                                nav.push(com.suipai.app.state.AppRoute.Yishun(newest.uri.toString()))
                                launchHop = null
                            }
                        }
                    }

                    AppNavHost(
                        state = state,
                        nav = nav,
                        chrome = chrome,
                        batch = batch,
                        delete = deleteFlow,
                        share = share,
                        onSystemCamera = {
                            val uri = systemCaptureTarget(context)
                            if (uri == null) {
                                state.batchNote = "系统相机打不开（缓存目录写不出来）"
                            } else {
                                systemCamera.launch(uri)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        collectShare(intent)
        collectHop(intent)
    }

    /**
     * 从分享 intent 里取图。读完立刻把 action 复位并摘掉 extra：
     * singleTask 的任务会被系统恢复，不复位的话每次回到前台都要再导一遍同一张。
     */
    private fun collectShare(intent: Intent) {
        val uris: List<Uri?> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOf(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> emptyList()
        }
        val images = uris.filterNotNull().filter { it.scheme == "content" || it.scheme == "file" }
        if (verbose) {
            android.util.Log.d(
                SHARE_TAG,
                "action=${intent.action} 收到 ${uris.size} 条，可用 ${images.size} 条",
            )
        }
        if (images.isEmpty()) return
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.action = Intent.ACTION_MAIN
        shareInbox = images
    }

    private companion object {
        const val SHARE_TAG = "SuiPaiShare"
        const val WIDGET_TAG = "SuiPaiWidget"
    }
}
