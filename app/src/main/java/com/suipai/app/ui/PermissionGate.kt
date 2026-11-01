package com.suipai.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.suipai.app.ui.common.openAppSettings
import com.suipai.app.ui.common.photoAccessSettingsReachable
import com.suipai.app.ui.common.AppBarCell
import com.suipai.app.ui.common.AppBarPrimary
import com.suipai.app.ui.theme.SuiPaiSheet
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.pm.PackageManager

fun requiredPermissions(): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 33) {
        list += Manifest.permission.READ_MEDIA_IMAGES
        if (Build.VERSION.SDK_INT >= 34) {
            list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
    } else {
        list += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return list.toTypedArray()
}

fun hasMediaAccess(context: android.content.Context): Boolean = readMediaAccess(context).allowed

/** Android 14 起系统才允许「只给一部分照片」，这个门限以下那条权限压根不存在。 */
private const val PARTIAL_ACCESS_SDK = 34

private fun grantedHere(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * 一次媒体权限的检查结果，**只有布尔和整数**。
 *
 * 为什么判据不收 `Context`：`ContextCompat.checkSelfPermission` 在 JVM 单测里是一个会抛
 * `Stub!` 的桩，真值表就没法穷举（这一族一共四行状态，逐行喂参数是唯一的办法）。
 * 读权限留在 [readMediaAccess] 这一处，判据留在纯函数，界面上谁也抄不走。
 *
 * 哪一格算"部分"见 [isPartialPhotoAccess] 那张真值表（四行全部钉在 `PartialAccessTest`）。
 */
@Immutable
internal data class MediaAccess(
    val sdk: Int,
    val imagesGranted: Boolean,
    val userSelectedGranted: Boolean,
) {
    /**
     * 有没有"能读到照片"的授权 —— 部分授权也算，系统本来就只放行用户选中的那些。
     *
     * `userSelectedGranted` 要先过一遍系统门限：13 及以下压根没有这条权限，
     * 一个从别处喂进来的 true 不许换来访问（原来那份判据就是这么写的，逐格对齐）。
     */
    val allowed: Boolean get() = imagesGranted || isPartialPhotoAccess(this)
}

/**
 * 读一次当前授权状态。
 *
 * `READ_MEDIA_IMAGES`（Android 13+）与它下面的 `READ_EXTERNAL_STORAGE`（12 及以下）
 * 是同一个位置上的两个名字，所以都落到 `imagesGranted` 这一格：下面所有判据只看一份布尔，
 * 不必为三代系统各写一遍。
 */
internal fun readMediaAccess(context: android.content.Context): MediaAccess {
    val images = if (Build.VERSION.SDK_INT >= 33) {
        grantedHere(context, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        grantedHere(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val selected = Build.VERSION.SDK_INT >= PARTIAL_ACCESS_SDK &&
        grantedHere(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    return MediaAccess(Build.VERSION.SDK_INT, images, selected)
}

/**
 * 是不是「只授权了部分照片」。真值表（Android 14 的媒体权限那一页 + 本机装机量到的那一条）：
 *
 * | 用户怎么选 | READ_MEDIA_IMAGES | READ_MEDIA_VISUAL_USER_SELECTED | 判 |
 * | --- | --- | --- | --- |
 * | 允许全部 | 授予 | **不**授予 | 不是部分 |
 * | 只选一部分 | 不授予 | 授予 | **部分**（装机量到 85 → 73 张，就是这一行） |
 * | 两个都在（选完又允许全部，机型/版本差异） | 授予 | 授予 | **部分**（宁可多说一句，也不能少说） |
 * | 拒绝 | 不授予 | 不授予 | 不是部分（这时候压根没有访问，走授权门） |
 *
 * 中间那两行是这个判据存在的理由：`hasMediaAccess` 对"部分"和"全部"都返回 true，
 * 于是时间线读到的张数比用户以为的少一半，而屏幕上一个字都不解释 ——
 * 用户只会认为 App 坏了。
 *
 * 「两个都授予」那一行是**故意往多说**的：文档只把 `IMAGES=N, SELECTED=Y` 写成部分，
 * 但真机上"先在弹框里选一部分、后又去设置里允许全部"会不会把 selected 留着，
 * 这台机器上量不到（要改的是系统的授予状态，不是界面）。判成部分最多多说一句人话，
 * 判成全部则是接着撒谎。
 */
internal fun isPartialPhotoAccess(a: MediaAccess): Boolean =
    a.sdk >= PARTIAL_ACCESS_SDK && a.userSelectedGranted

/**
 * 「只授权了部分照片」那一句要说的话；不是部分授权就返回 null（界面一个字都不加）。
 *
 * 三条不撒谎的规矩，都是这一族以前栽过的形状：
 * 1. **数字宁可不报**：`visibleCount` 为 null 表示"这个数不是系统放行的全部"
 *    （分页还没读到底、或者一张都没有），报了就是第二个假数。
 * 2. **全库条数今天拿不到**：部分授权下被排除的那些行对本应用根本不可见，
 *    所以 `libraryCount` 只能由别处（别的 lane 若加一次全库计数）给；
 *    给了但**不比可见数大**时也不许说"少了多少" —— 两处不同源时退回不带数字那一句。
 * 3. **格子灰着的时候不许教用户去点它**：`settingsReachable = false` 那一句改口说手动改。
 */
internal fun partialPhotoNotice(
    a: MediaAccess,
    visibleCount: Int? = null,
    libraryCount: Int? = null,
    settingsReachable: Boolean = true,
): String? {
    if (!isPartialPhotoAccess(a)) return null
    val count = visibleCount?.takeIf { it > 0 }
    val said = when {
        libraryCount != null && count != null && libraryCount > count ->
            "只授权了部分照片：相册里有 $libraryCount 张，系统只放行其中 $count 张"
        count != null ->
            "只授权了部分照片：这里能读到 $count 张，没被选中的那些系统不给读，所以这个数不是相册的全部"
        else ->
            "只授权了部分照片：没被选中的那些系统不给读，所以这里的张数不等于相册的全部"
    }
    val how = if (settingsReachable) {
        "点「补授权」可以改。"
    } else {
        "这台机器上没有能打开的授权设置，要到系统设置里手动改。"
    }
    return "$said。$how"
}

/**
 * 授权状态 + 这台机器有没有"改授权的地方可去"的一次探测结果。
 *
 * `settingsReachable` 与授权状态是两个问题，所以这里存的是探测结果而不是判据；
 * 判据全在纯函数里（[isPartialPhotoAccess] / [partialPhotoNotice]）。
 */
@Immutable
internal data class PhotoAccessProbe(
    val access: MediaAccess,
    /** 两条出口（系统的「选择照片」设置页 / 本应用设置页）都解析不到时，「补授权」那颗就是灰的。 */
    val settingsReachable: Boolean,
)

/**
 * 读一次授权、探一次设置页。
 *
 * 两个都是 binder 调用，所以只许在"授权可能变了"的三个时刻调（首帧、系统弹窗回来、ON_RESUME），
 * 不许出现在点击路径或重组里 —— 这一条本项目被守卫钉过两次（13 号、17 号那一族）。
 */
internal fun probePhotoAccess(context: android.content.Context): PhotoAccessProbe =
    PhotoAccessProbe(readMediaAccess(context), photoAccessSettingsReachable(context))

/**
 * 授权状态的缓存，给时间线那一屏用。
 *
 * 为什么不就地读：`GalleryScreen` 自己没有"权限可能变了"的时机（它甚至不知道用户什么时候
 * 从系统设置页回来），而 `PermissionGate` 是所有界面的共同祖先、本来就在那三个时刻重读权限。
 * 写只有那一处，读的地方只拿一份结论，两边都不会各抄一遍判据。
 */
internal val photoAccessProbe = mutableStateOf<PhotoAccessProbe?>(null)

/** Unwraps the Activity behind a (possibly wrapped) Context, or null. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted(): Boolean = hasMediaAccess(context)
    // 顺手把这一份状态读给时间线（「只授权了部分照片」那一族的话都说在那一头）。
    // 探测要问 PackageManager，所以只跟着"授权可能变了"的这几个时刻跑，不跟着重组跑。
    fun reprobe() { photoAccessProbe.value = probePhotoAccess(context) }
    var ok by remember { mutableStateOf(granted()).also { reprobe() } }
    // True once a permission request has come back from the system. Combined
    // with the rationale check it distinguishes "denied once" (rationale still
    // shown -> ask again) from "denied and don't ask again" (no rationale ->
    // send the user to system settings).
    var asked by remember { mutableStateOf(false) }
    val activity = remember(context) { context.findActivity() }
    // 「去设置」跳不出去时的那句说明；与相机页那一格同一套做法。
    var settingsHint by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        asked = true
        ok = granted()
        reprobe()
    }

    // Re-check on every resume so returning from the system settings page
    // refreshes the gate without a manual action.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ok = granted()
                // 「补授权」那颗格子也靠这一句刷新：用户从系统设置里改完回来，
                // 灰着的那颗要自己变亮，不用重启应用。
                reprobe()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!ok) launcher.launch(requiredPermissions())
    }

    if (ok) {
        // testTag 会映射成 android:id，设备验证才能按 tag 找控件；只靠中文文案定位的话，
        // 改一个字所有 UI 自动化就断一片。挂在这里是因为 PermissionGate 是所有界面的共同祖先。
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            content()
        }
        return
    }

    val permanentlyDenied = asked && activity != null &&
        requiredPermissions().none {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 这是用户看到的第一屏，所以它说同一套视觉语言：重磅页名 + 等宽副记 + 直角格，
        // 而不是 Material 默认的胶囊按钮（原来就是两个 Button，和后面所有界面都不像同一个 app）。
        //
        // 品牌两行钉在**最上面**而不是跟着内容居中：首次启动时系统权限框是盖在屏幕正中的
        // （实测 y780..1650 / 2400），整屏居中的话说明文字与按钮全落在弹窗后面，
        // 用户第一眼看到的就是一屏纯黑 —— 看起来像崩了。
        Text(
            "随拍",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
        )
        Text(
            "SUIPAI · CONTACT SHEET",
            style = SuiPaiSheet.type.frameNo,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "随拍需要相册权限，照片只留在这台手机上。拍照时再开相机。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            if (permanentlyDenied) {
                Text(
                    "权限已被永久拒绝，系统不会再弹窗，只能到设置里手动打开。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            settingsHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 22.dp),
            ) {
                if (permanentlyDenied) {
                    AppBarCell(
                        label = "去设置",
                        on = false,
                        onClick = {
                            // 原来这里裸调 context.startActivity：这台设备要是没有能接的设置页，
                            // 用户点「去设置」就是**什么都没发生**。授权门是用户看到的第一屏，
                            // 最不该静默 —— 走 sendOrTell，失败就把原因写在屏幕上。
                            openAppSettings(context) { settingsHint = it }
                        },
                    )
                } else {
                    AppBarCell(
                        label = "再问一次",
                        on = false,
                        onClick = { launcher.launch(requiredPermissions()) },
                    )
                }
                AppBarPrimary(
                    label = "授权相册",
                    onClick = {
                        if (permanentlyDenied) {
                            openAppSettings(context) { settingsHint = it }
                        } else {
                            launcher.launch(requiredPermissions())
                        }
                    },
                )
            }
        }
    }
}