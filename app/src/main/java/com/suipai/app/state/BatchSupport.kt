package com.suipai.app.state

/**
 * 分享导入（"别的 App → 分享到随拍"）的一句结论。
 *
 * 纯函数：改前这段逻辑写在 `LaunchedEffect(shareInbox)` 里，结论直接赋值给 `batchNote`，
 * 三个分支（全成 / 部分成 / 全失败）的分界只写在 if 里，测试无处落脚。
 *
 * 语义（与改前逐字一致）：
 *  - 一张都没读进来：**必须说话**。改前这里只是清空收件箱，于是"分享到随拍"点完
 *    界面上一个字都没有，看起来像入口是死的（装机实测）。
 *  - 部分读进来：报出没读进来的张数。
 *  - 全部读进来：不说话（直接进一瞬，用户看得见照片）。
 */
internal object ShareImportOutcome {

    /** 返回要写进结论位的那句话；`null` = 不需要说话。 */
    fun note(total: Int, imported: Int): String? = when {
        total <= 0 -> null
        imported <= 0 -> "分享进来的 $total 张都没读出来（可能已被对方清掉）"
        imported < total -> "分享进来 $total 张，${total - imported} 张没读出来"
        else -> null
    }
}

/**
 * 删除授权链的票据（"删除 → 要授权 → 系统框 → 回来看还在不在"）。
 *
 * 为什么单独一个类：改前这三个值是三个独立的 `rememberSaveable` 变量
 * （`consentIds` / `consentDirect` / `consentFromViewer`），它们只在"弹框之前"
 * 与"回调之后"之间传送，而**授权框还挂着时重建 Activity**（分屏、折叠屏换外屏）
 * 会让普通 `remember` 版本全部丢失 → 回调照常投递但 `asked=0` → 提前 return
 * → 既不查还在不在、也不清理 id：那批死 id 永久留在 fav_ids/hide_ids 里。
 * 三个值必须一起存、一起取 —— 一个 data class 天然保证这一点，三个独立变量不保证。
 */
internal data class ConsentTicket(
    /** 要问用户的那几张的 id（按弹框时的顺序）。 */
    val ids: List<Long> = emptyList(),
    /** 弹框之前就已经直接删掉的张数（结论里要带上）。 */
    val direct: Int = 0,
    /** 这一趟是不是从查看器里发起的（决定删除后要不要顺手修查看器那一页）。 */
    val fromViewer: Boolean = false,
) {

    /** 有没有真的在等一个授权框回来。 */
    val pending: Boolean get() = ids.isNotEmpty()

    /**
     * 回调进来时先取快照、再作废票据。
     *
     * `direct` 的清零规则是改前逐字的：**只有 `ids` 为空（没有要问的）时才清零**。
     * 留着它的话，下一次弹框的结论会凭空多出这几张（"另删了 3 张"）。
     */
    fun consumed(): ConsentTicket = if (ids.isEmpty()) copy(direct = 0) else ConsentTicket()

    companion object {
        val EMPTY = ConsentTicket()

        /**
         * [encode] 的逆。脏数据返回 [EMPTY]，不许抛：
         * 这张票在 `rememberSaveable` 里，版本回退或半截 Bundle 不许让应用起不来。
         */
        fun decode(encoded: List<String>): ConsentTicket {
            if (encoded.size < 3) return EMPTY
            val ids = encoded[0].split(',').mapNotNull { it.toLongOrNull() }
            val direct = encoded[1].toIntOrNull()?.coerceAtLeast(0) ?: 0
            return ConsentTicket(ids, direct, encoded[2] == "1")
        }
    }

    /** 进 Bundle 的 `List<String>` 形状（与 [decode] 互逆）。 */
    fun encode(): List<String> = listOf(
        ids.joinToString(","),
        direct.toString(),
        if (fromViewer) "1" else "0",
    )
}
