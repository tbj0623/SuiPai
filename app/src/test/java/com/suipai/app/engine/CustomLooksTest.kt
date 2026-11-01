package com.suipai.app.engine

import com.suipai.app.data.CustomLook
import com.suipai.app.data.CustomLooks
import com.suipai.app.data.asLook
import com.suipai.app.data.fromRecipe
import com.suipai.core.engine.CurvePoint
import com.suipai.core.engine.HslBand
import com.suipai.core.engine.HslBands
import com.suipai.core.engine.LinearMask
import com.suipai.core.engine.LocalAdjust
import com.suipai.core.engine.LocalMask
import com.suipai.core.engine.RadialMask
import com.suipai.core.engine.RecipeMask
import com.suipai.core.engine.ToneCurve
import com.suipai.core.engine.ToneCurves
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 自定义配方：落盘、CRUD、导入导出。
 *
 * 这份仓库扛着的风险与 `IdLists` 不同：那边是"写丢了要重来"，这边是
 * **"读到半截文件会把整库静默丢光"**。所以每条判据都配了一个坏例子：
 * 半截 JSON、别的模块的前缀、被截断的导入文本 —— 它们必须退化成
 * "读不到 / 导入失败"，而不是抛异常或清空磁盘。
 *
 * 注意用的是 `org.junit.Assert`：它的 `assertX(message, actual)` 参数顺序
 * 与 `kotlin.test` 的 `assertX(actual, message)` **相反**，两种混用会编不过
 * （这一条自己就踩过，写在这里省下一个人二十分钟）。
 */
class CustomLooksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, CustomLooks.FILE_NAME)

    private fun repo() = CustomLooks(file())

    private val curves = ToneCurves(
        master = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(0.4f, 0.33f), CurvePoint(1f, 1f))),
    )

    private val hsl = HslBands(orange = HslBand(hue = 0.15f, lum = 0.2f))

    private val masks = listOf(
        LocalMask(RadialMask(cx = 0.3f, cy = 0.4f, feather = 0.5f), LocalAdjust(exposure = 0.5f)),
    )

    private fun sample(
        id: String = CustomLook.ID_PREFIX + "1000",
        name: String = "黄昏",
        modified: Long = 1000L,
    ) = CustomLook.from(
        name = name,
        baseLookId = "daylight",
        intensity = 0.66f,
        shifts = Shifts(
            temp = 0.08f,
            contrast = -0.03f,
            sat = 0.12f,
            shadow = 0.02f,
            fade = 0.01f,
            highlight = 0.04f,
            soft = 0.05f,
        ),
        curves = curves,
        hsl = hsl,
        masks = masks,
        id = id,
        created = modified,
        modified = modified,
        now = modified,
    )

    /** 带非空断言的取值：`assertNotNull` 在 JUnit 里返回 Unit，不能直接当表达式用。 */
    private fun requirePresent(look: CustomLook?): CustomLook {
        assertNotNull("期望解析出一份配方，实际是 null", look)
        return look!!
    }

    // ---- 落盘 -----------------------------------------------------------------

    @Test
    fun saveThenLoadSurvivesEveryField() {
        val r = repo()
        r.save(sample())
        val loaded = r.all().single()
        assertEquals("黄昏", loaded.name)
        assertEquals("daylight", loaded.baseLookId)
        assertEquals(0.66f, loaded.intensity, 0f)
        assertEquals(0.08f, loaded.temp, 1e-6f)
        assertEquals(-0.03f, loaded.contrast, 1e-6f)
        assertEquals(0.12f, loaded.sat, 1e-6f)
        assertEquals(0.02f, loaded.shadow, 1e-6f)
        assertEquals(0.01f, loaded.fade, 1e-6f)
        assertEquals(0.04f, loaded.highlight, 1e-6f)
        assertEquals(0.05f, loaded.soft, 1e-6f)
        assertEquals(3, loaded.curves.master!!.points.size)
        assertEquals(0.15f, loaded.bands.orange.hue, 1e-6f)
        assertEquals(1, loaded.localMasks.size)
        assertTrue("id 前缀丢了：${loaded.id}", loaded.id.startsWith(CustomLook.ID_PREFIX))

        // 自检：把落盘文件删掉，上面读到的内容必须消失（证明它真的来自磁盘）
        file().delete()
        assertTrue("文件删了还能读出配方", r.all().isEmpty())
    }

    @Test
    fun fileLivesAtFilesDirAndIsNotPreferences() {
        // 需求：落盘 filesDir/custom_looks.json，不进 SharedPreferences。
        assertEquals("custom_looks.json", CustomLooks.FILE_NAME)
        val r = repo()
        assertTrue("空目录不该有配方", r.all().isEmpty())
        r.save(sample())
        val f = file()
        assertTrue("custom_looks.json 没被写出来", f.isFile)
        assertTrue("文件里没有配方内容", f.readText().contains("黄昏"))
        // 自检：临时文件不许残留（写到一半被杀要能恢复）
        assertFalse("临时文件残留了", File(tmp.root, CustomLooks.FILE_NAME + ".tmp").isFile)
    }

    @Test
    fun corruptedFileReadsAsEmptyInsteadOfCrashing() {
        val f = file()
        f.writeText("{\"items\":[{\"id\":\"user_1\",\"name\":")
        val r = CustomLooks(f)
        assertTrue("半截 JSON 应该读成空表", r.all().isEmpty())
        // 自检：同一路径写一份合法内容必须读得出来，证明上面的"空"不是路径写错
        repo().save(sample())
        assertEquals(1, CustomLooks(f).all().size)
    }

    @Test
    fun entriesWithoutUserPrefixAreIgnored() {
        // 内置库的 id 不许出现在自定义仓库里：asLooks() 会把它和内置配方混在一起。
        // 这个过滤只有"文件被手改/被别的版本写过"时才触发，所以直接写盘构造，
        // 不走 save()（save 会贴心地补上前缀，那样就绕开了要测的分支）。
        val f = file()
        f.writeText(
            "{\"items\":[{\"id\":\"daylight\",\"name\":\"内置\",\"baseLookId\":\"daylight\"}," +
                "{\"id\":\"user_7\",\"name\":\"我的\",\"baseLookId\":\"daylight\"}]}",
        )
        val all = CustomLooks(f).all()
        assertEquals("没有 user_ 前缀的条目应被忽略", 1, all.size)
        assertEquals("user_7", all.single().id)

        // 自检：save() 收到没有前缀的 id 时必须自己补上，而不是把内置 id 原样写进去。
        // 用独立文件，否则会跟上面那份带 user_7 的文件混在一起（这里踩过一次）。
        val fresh = CustomLooks(File(tmp.root, "selfcheck.json"))
        fresh.save(sample(id = "daylight"))
        assertEquals(1, fresh.all().size)
        assertTrue(
            "save 没有给裸 id 补 user_ 前缀：${fresh.all().single().id}",
            fresh.all().single().id.startsWith(CustomLook.ID_PREFIX),
        )
    }

    // ---- CRUD -----------------------------------------------------------------

    @Test
    fun saveIsUpdateNotInsertForTheSameId() {
        val r = repo()
        r.save(sample(id = CustomLook.ID_PREFIX + "1", name = "初版", modified = 10L))
        r.save(sample(id = CustomLook.ID_PREFIX + "1", name = "改过", modified = 20L))
        val all = r.all()
        assertEquals("同 id 保存变成了两条", 1, all.size)
        assertEquals("改过", all[0].name)
        assertEquals("覆盖时创建时间被刷掉了", 10L, all[0].created)
        assertEquals(20L, all[0].modified)
        // 自检：换一个 id 必须变成两条
        r.save(sample(id = CustomLook.ID_PREFIX + "2", modified = 30L))
        assertEquals(2, r.all().size)
    }

    @Test
    fun newIdUsesTimestampAndPrefix() {
        assertEquals("user_1730000000000", CustomLook.newId(1730000000000L))
        // 自检：不同时间戳必须不同 id
        assertNotEquals(CustomLook.newId(1730000000000L), CustomLook.newId(1730000000001L))
    }

    /**
     * `newId` 是纯函数（同一毫秒给同一个 id），`freshId` 是**发号器**（同一毫秒给两个）。
     *
     * 这两个语义必须分开钉住，否则以后有人把 `freshId` "简化"成 `newId`，
     * 上面那条 `importedItemGetsFreshIdAndTimestamps` 会变成偶发红（取决于两次导入
     * 是否落进同一毫秒）—— 偶发红比恒红更难查。
     *
     * 这条同时是真源码判据：用**同一个 now** 连发 100 次，必须 100 个不同 id。
     */
    @Test
    fun freshIdNeverRepeatsEvenWithinTheSameMillisecond() {
        val same = 1_730_000_000_000L
        val ids = (1..100).map { CustomLook.freshId(same) }
        assertEquals("同一毫秒连发 100 次出现了重复 id", 100, ids.distinct().size)
        assertTrue("freshId 丢了 user_ 前缀：${ids.first()}", ids.all { it.startsWith(CustomLook.ID_PREFIX) })

        // 自检：确实是在"同一毫秒"上测的 —— `newId` 在同样输入下必须只给出 1 个值。
        // 少了这一条，上面那句"100 个不同"可能只是因为 now 一直在变（假绿）。
        assertEquals(1, (1..100).map { CustomLook.newId(same) }.distinct().size)

        // 自检：注入更早的 now 也不许倒退（否则 id 会与已发出的撞车）
        val later = CustomLook.freshId(same)
        val earlier = CustomLook.freshId(same - 5_000L)
        assertNotEquals("注入更早的时间戳时 id 没有前移", later, earlier)
    }

    /**
     * `fromRecipe`（把照片里的 EXIF 配方存成我的）与 `from` 一样是"新建一条"，
     * 同一毫秒连存两条必须是两条。这是 `freshId` 上线前同一类撞号的下游。
     */
    @Test
    fun fromRecipeGeneratesDistinctIdsForNearSimultaneousSaves() {
        val recipe = sample().asRecipe()
        val same = 1_730_000_000_000L
        val a = CustomLook.fromRecipe("甲", recipe, now = same)
        val b = CustomLook.fromRecipe("乙", recipe, now = same)
        assertNotEquals("同一毫秒两条 fromRecipe 拿到了同一个 id", a.id, b.id)
        // 自检：注入不同时间戳时两者本来就不同（证明上面那句不是恒真）
        val c = CustomLook.fromRecipe("丙", recipe, now = same + 1)
        assertNotEquals(a.id, c.id)
    }

    @Test
    fun deleteAndRenameOnlyTouchTheTarget() {
        val r = repo()
        r.save(sample(id = CustomLook.ID_PREFIX + "1", name = "甲", modified = 10L))
        r.save(sample(id = CustomLook.ID_PREFIX + "2", name = "乙", modified = 20L))
        r.rename(CustomLook.ID_PREFIX + "1", "甲改")
        assertEquals("甲改", r.byId(CustomLook.ID_PREFIX + "1")!!.name)
        assertEquals("改名波及了别的配方", "乙", r.byId(CustomLook.ID_PREFIX + "2")!!.name)
        r.delete(CustomLook.ID_PREFIX + "1")
        assertEquals(1, r.all().size)
        assertNull(r.byId(CustomLook.ID_PREFIX + "1"))
        // 自检：删不存在的 id 不许抛
        r.delete(CustomLook.ID_PREFIX + "nope")
        assertEquals(1, r.all().size)
    }

    @Test
    fun orderIsMostRecentlyModifiedFirst() {
        val r = repo()
        r.save(sample(id = CustomLook.ID_PREFIX + "1", modified = 10L))
        r.save(sample(id = CustomLook.ID_PREFIX + "2", modified = 30L))
        r.save(sample(id = CustomLook.ID_PREFIX + "3", modified = 20L))
        assertEquals(listOf("user_2", "user_3", "user_1"), r.all().map { it.id })
        // 自检：把最老的那条改成最新，顺序必须跟着变（不是碰巧按插入顺序）
        r.save(sample(id = CustomLook.ID_PREFIX + "1", modified = 99L))
        assertEquals("user_1", r.all().first().id)
    }

    // ---- 导入导出 -------------------------------------------------------------

    @Test
    fun exportImportTextRoundTrips() {
        val r = repo()
        val original = sample()
        val text = r.exportText(original)
        assertTrue("导出文本缺前缀：${text.take(20)}", text.startsWith(CustomLooks.MARKER))
        // 无 padding、无 +/，才能安全塞进任何消息/剪贴板
        val payload = text.removePrefix(CustomLooks.MARKER)
        assertTrue(
            "payload 不是 URL-safe 无 padding",
            payload.none { it == '+' || it == '/' || it == '=' || it.isWhitespace() },
        )

        val imported = requirePresent(r.importText(text))
        assertEquals(original.name, imported.name)
        assertEquals(original.baseLookId, imported.baseLookId)
        assertEquals(original.intensity, imported.intensity, 0f)
        assertEquals(original.temp, imported.temp, 1e-6f)
        assertEquals(original.soft, imported.soft, 1e-6f)
        assertEquals(3, imported.curves.master!!.points.size)
        assertEquals(0.15f, imported.bands.orange.hue, 1e-6f)
        assertEquals(1, imported.localMasks.size)

        // 自检：名字必须是原样带回来的，不是回落到默认名
        assertEquals("黄昏", imported.name)
        assertNotEquals("导入配方", imported.name)
    }

    @Test
    fun importedItemGetsFreshIdAndTimestamps() {
        // 同一条导入两次要得到两条（否则第二次会静默覆盖本机那条）。
        val r = repo()
        val text = r.exportText(sample(id = CustomLook.ID_PREFIX + "10"))
        val a = requirePresent(r.importText(text))
        val b = requirePresent(r.importText(text))
        assertNotEquals("两次导入拿到了同一个 id", a.id, b.id)
        assertTrue(a.id.startsWith(CustomLook.ID_PREFIX))
        assertTrue("时间戳没写", a.created > 0L && a.modified > 0L)
        assertEquals(a.created, a.modified)
        // 自检：原 id 不许被沿用
        assertNotEquals("user_10", a.id)
    }

    @Test
    fun importFindsPayloadInsideChatter() {
        // 实际用法是"从聊天记录里粘一段话，里面夹着导出文本"。
        val r = repo()
        val text = "给你我的配方：" + r.exportText(sample()) + "\n你试试看～"
        assertEquals("黄昏", requirePresent(r.importText(text)).name)
        // 自检：把前缀去掉必须失败
        assertNull("没有前缀也导入成功了", r.importText(text.replace(CustomLooks.MARKER, "")))
    }

    @Test
    fun corruptedImportTextReturnsNullAndNeverThrows() {
        val r = repo()
        // 每一种都是"剪贴板里真会遇到"的形状
        val bad = listOf(
            "",
            "随便一句话",
            CustomLooks.MARKER,
            CustomLooks.MARKER + "!!!not-base64!!!",
            CustomLooks.MARKER + "eyJpZCI6InVzZXJfMSIsIm5hbWUi", // 截断的 JSON
            "SPRECIPE1:eyJ2IjozfQ", // 别的模块的前缀，不是本仓库的
        )
        for (text in bad) {
            assertNull("坏文本被当成配方导入了：$text", r.importText(text))
        }
        // 自检：合法 payload 换个前缀也必须失败，证明上面的失败来自前缀与解析，
        // 而不是"所有输入都失败"
        val valid = r.exportText(sample()).removePrefix(CustomLooks.MARKER)
        assertNull(r.importText("SPLOOK2:$valid"))
        assertNotNull(r.importText(CustomLooks.MARKER + valid))
    }

    @Test
    fun blankNameGetsReadableDefault() {
        val r = repo()
        // 名字为空的导入要有一个可读的默认名，而不是在列表里显示成空行
        val blank = sample().copy(name = "   ")
        assertEquals("导入配方", requirePresent(r.importText(r.exportText(blank))).name)
        // 自检：正常名字不许被默认名覆盖
        assertEquals("黄昏", requirePresent(r.importText(r.exportText(sample()))).name)
    }

    // ---- asLooks --------------------------------------------------------------

    @Test
    fun asLooksUsesUserPrefixAndBaseDefaults() {
        val r = repo()
        r.save(sample(id = CustomLook.ID_PREFIX + "5", name = "我的黄昏"))
        val looks = r.asLooks()
        assertEquals(1, looks.size)
        val look = looks.single()
        assertTrue("id 前缀丢了：${look.id}", look.id.startsWith(CustomLook.ID_PREFIX))
        assertEquals("我的黄昏", look.name)
        assertEquals(0.66f, look.defaultIntensity, 1e-6f)
        assertTrue("摘要没标明来源：${look.summary}", look.summary.contains("我的配方"))
        // 基础配方不认得时也不能抛，回落到 daylight
        val orphan = sample(id = CustomLook.ID_PREFIX + "6").copy(baseLookId = "no_such_look")
        assertTrue(orphan.asLook().id.startsWith(CustomLook.ID_PREFIX))
        // 自检：内置配方 id 本身不该被判成用户配方
        assertFalse("daylight".startsWith(CustomLook.ID_PREFIX))
    }

    @Test
    fun asRecipeCarriesProOperators() {
        // 自定义配方要能导出成照片 EXIF 里那条 v3 配方，否则"存成我的"之后发出去会丢曲线。
        val recipe = sample().asRecipe()
        assertEquals(3, recipe.v)
        assertNotNull("曲线没进配方", recipe.curve)
        assertNotNull("分色没进配方", recipe.hsl)
        assertNotNull("蒙版没进配方", recipe.masks)
        assertEquals("daylight", recipe.lookId)
        assertEquals(0.08f, recipe.tempShift, 1e-6f)
        assertEquals(0.05f, recipe.softShift, 1e-6f)
        // 自检：没有专业算子时不许写出这三个键（保持 v2 形状）
        val plain = sample().copy(curve = null, hsl = null, masks = null).asRecipe()
        assertNull("中性配方多写了 curve", plain.curve)
        assertNull(plain.hsl)
        assertNull(plain.masks)
    }

    @Test
    fun masksAreCappedAtFour() {
        // 产品上限 4 个：存与读都不能放过第 5 个。
        val many = (0 until 8).map {
            LocalMask(RadialMask(cx = 0.1f * it, cy = 0.5f), LocalAdjust(exposure = 0.1f))
        }
        val look = CustomLook.from(
            name = "多蒙版",
            baseLookId = "daylight",
            intensity = 0.5f,
            shifts = Shifts.None,
            masks = many,
            now = 1L,
        )
        assertEquals(4, look.localMasks.size)
        assertEquals(4, look.masks!!.size)
        // 自检：3 个以内不许被削
        val three = CustomLook.from(
            name = "三蒙版",
            baseLookId = "daylight",
            intensity = 0.5f,
            shifts = Shifts.None,
            masks = many.take(3),
            now = 1L,
        )
        assertEquals(3, three.localMasks.size)
    }

    @Test
    fun linearMaskSurvivesStorage() {
        // 两种形状都要能存回来：只测径向会漏掉 kind 分派。
        val r = repo()
        r.save(
            sample().copy(
                masks = listOf(
                    RecipeMask.from(
                        LocalMask(
                            LinearMask(angle = 42f, start = 0.1f, end = 0.9f),
                            LocalAdjust(temperature = -0.4f),
                        ),
                    ),
                ),
            ),
        )
        val back = r.all().single().localMasks.single().shape as LinearMask
        assertEquals(42f, back.angle, 1e-6f)
        assertEquals(0.9f, back.end, 1e-6f)
        // 自检：参数丢了的话会退化成默认 0.3/0.7
        assertFalse(
            "线性蒙版参数退化成默认值了",
            back.start == 0.3f && back.end == 0.7f,
        )
    }
}
