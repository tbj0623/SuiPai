package com.suipai.app.ui.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suipai.app.ui.gallery.GalleryFlag
import com.suipai.app.ui.yishun.YishunFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 相机那三张表（画幅、网格、开关）的钉子。
 *
 * 它们全都是「把原来手写的级联改成声明式」的产物，所以最要紧的不变量是
 * **改完之后行为与老版本逐字对应**：循环顺序、落盘 key、grid_mode 的取值范围。
 * 顺序错了用户会觉得设置被偷改，key 撞了两个界面的开关会一起跳。
 */
@RunWith(AndroidJUnit4::class)
class CameraTablesTest {
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("suipai_camera_tables_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun state(): CameraUiState =
        CameraUiState(ApplicationProvider.getApplicationContext(), prefs)

    @Test
    fun ratioCycleKeepsTheOldHandWrittenOrder() {
        // 老代码是一段 120 行的 when 级联，顺序就是这个列表；改表的时候不能动它。
        assertEquals(
            listOf("16:9", "1:1", "3:2", "9:16", "4:5", "2.39", "1.85", "5:3", "8:5", "4:3"),
            CamRatio.values().map { it.label },
        )
    }

    @Test
    fun everyCroppingRatioSetsExactlyOneCropFlag() {
        CamRatio.values().forEach { r ->
            val flags = listOf(r.square, r.r32, r.r916, r.r45, r.r239, r.r185, r.r53, r.r85).count { it }
            if (r.cropAspect > 0f) {
                assertEquals("$r 要裁切就该只有一个裁切标记", 1, flags)
            } else {
                assertEquals("$r 不裁切就不该有任何裁切标记", 0, flags)
            }
        }
    }

    /**
     * 走一遍循环，检查"这一档要裁成什么、要不要画压暗框"两件事跟着走。
     *
     * 这里原来测的是九个 `st.ratioXX` 派生布尔——那九个已经随热路径一起删了
     * （`postProcessCapture` 现在只看 `cropAspect` 一个数，见 ANDROID-VERIFY §13），
     * 留着旧测试就是它编译不过的元凶：**release 门里不编 androidTest，所以它烂了一整轮没人看见**。
     */
    @Test
    fun ratioCycleDrivesCropAspectAndGuideJudgement() {
        val st = state()
        repeat(CamRatio.values().size) {
            val r = st.ratio
            assertEquals(r.cropAspect, st.cropAspect, 0f)
            // 还不知道传感器那一帧（0）：按这一档声明的算。
            st.sensorAspect = 0f
            assertEquals("$r：不知道原生帧时该按声明走", r.cropAspect > 0f, st.cropsNow)
            st.cycleRatio()
        }
        // 关键的一条：目标比例与**实际那一帧**差 ≤2% 就不画框，哪怕这一档声明了比例。
        st.ratioIndex = CamRatio.R169.ordinal
        st.sensorAspect = 16f / 9f
        assertFalse("帧本来就是 16:9 还压暗，等于凭空在预览上下各画一条带", st.cropsNow)
        st.sensorAspect = 4f / 3f
        assertTrue("对着 4:3 的原生帧，16:9 这一档该画框", st.cropsNow)
        st.ratioIndex = CamRatio.R43.ordinal
        st.sensorAspect = 1.19f
        assertFalse("4:3 这一档永远不裁（2.76 MP 变 1.56 MP 那次撤回的），画了就是白压暗", st.cropsNow)
    }

    @Test
    fun cycleRatioWritesNewAndLegacyKeysTogether() {
        val st = state()
        val next = st.cycleRatio()
        assertEquals(next.ordinal, prefs.getInt("ratio_idx", -1))
        assertEquals(next.sensor43, prefs.getBoolean("ratio_43", false))
        assertEquals(next.square, prefs.getBoolean("ratio_square", false))
        assertEquals(next.r85, prefs.getBoolean("ratio_85", false))
    }

    @Test
    fun freshInstallDefaultsToFourThreeLikeBefore() {
        assertEquals(CamRatio.R43, state().ratio)
    }

    @Test
    fun legacyBooleansStillSeedTheIndexWhenItIsAbsent() {
        prefs.edit()
            .putBoolean("ratio_85", true)
            .putBoolean("ratio_43", false)
            .apply()
        assertEquals(CamRatio.R85, CamRatio.fromPrefs(prefs))
    }

    @Test
    fun storedIndexWinsOverStaleLegacyBooleans() {
        prefs.edit()
            .putInt("ratio_idx", CamRatio.R185.ordinal)
            .putBoolean("ratio_85", true)
            .apply()
        assertEquals(CamRatio.R185, CamRatio.fromPrefs(prefs))
    }

    @Test
    fun gridTableKeepsTheOldModulusAndRange() {
        // grid_mode 是按整数存的：老界面 % 24 循环，存过的最大序号是 23。
        // 这张表**只许变长**（就地换内容或往后追加），少一格或重排都会让存过的设备读错线。
        assertEquals(25, CamGrid.values().size)
        CamGrid.values().forEachIndexed { i, g -> assertEquals(i, g.ordinal) }
        assertTrue(CamGrid.OFF.parts.isEmpty())
        CamGrid.values().drop(1).forEach { assertFalse("${it.label} 不该是空的", it.parts.isEmpty()) }
        // 越界（比如从更新的版本降级回来）不许把 composition 炸掉：
        assertEquals(CamGrid.values().last(), CamGrid.at(999))
        assertEquals(CamGrid.OFF, CamGrid.at(-3))
    }

    @Test
    fun gridCycleWrapsAtTheEndOfTheTable() {
        val st = state()
        st.gridMode = 0
        repeat(CamGrid.values().size) { st.cycleGrid() }
        assertEquals(0, st.gridMode)
        assertEquals("网格 关", st.gridLabel)
        // 关掉网格要顺手把老的 show_grid 也写成 false：别的界面还会读它。
        assertFalse(prefs.getBoolean("show_grid", true))
    }

    @Test
    fun cameraFlagKeysAreUniqueAndDoNotCollideWithTheOtherScreens() {
        val mine = CameraFlag.values().map { it.key }
        assertEquals(mine.size, mine.toSet().size)
        mine.forEach { assertTrue(it.isNotEmpty()) }
        val others = (YishunFlag.values().map { it.prefKey } + GalleryFlag.values().map { it.prefKey }).toSet()
        // 三个界面共用一个 prefs 文件：撞 key 的两个开关会一起跳，而且几乎查不出来。
        assertTrue("撞 key: ${mine.filter { it in others }}", mine.none { it in others })
    }

    @Test
    fun everyFlagHasTwoUsableLabels() {
        CameraFlag.values().forEach {
            assertTrue("${it.name} 缺状态文案", it.labelOff.isNotBlank() && it.labelOn.isNotBlank())
        }
    }

    @Test
    fun togglePersistsAndSurvivesARecreatedState() {
        CameraFlag.values().forEach { f ->
            val before = f.get(state())
            state().toggle(f)
            val after = f.get(state())
            assertEquals(!before, after)
            assertEquals(after, f.get(state()))
            state().toggle(f)
        }
    }

    @Test
    fun groupListingCoversEveryFlag() {
        val all = CameraFlagGroup.values().flatMap { CameraFlag.inGroup(it) }
        assertEquals(CameraFlag.values().toList().sortedBy { it.name }, all.sortedBy { it.name })
    }
}
