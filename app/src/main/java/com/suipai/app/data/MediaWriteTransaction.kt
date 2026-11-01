package com.suipai.app.data

/**
 * MediaStore 写入的最小事务边界。
 *
 * insert 成功后，无论写字节失败、发布失败还是抛异常，都尝试删掉那条半成品。
 * rollback 自己失败不能盖掉原始故障；调用方负责记录异常。
 *
 * 这里只依赖 Kotlin，单元测试可用假 URI 把所有失败分支跑一遍。
 */
internal fun <T : Any> insertWritePublishOrRollback(
    insert: () -> T?,
    write: (T) -> Boolean,
    publish: (T) -> Boolean,
    rollback: (T) -> Unit,
    onFailure: (Throwable) -> Unit = {},
): T? {
    val target = try {
        insert()
    } catch (e: Exception) {
        onFailure(e)
        null
    } ?: return null

    var committed = false
    return try {
        if (!write(target) || !publish(target)) null
        else {
            committed = true
            target
        }
    } catch (e: Exception) {
        onFailure(e)
        null
    } finally {
        if (!committed) {
            try {
                rollback(target)
            } catch (e: Exception) {
                onFailure(e)
            }
        }
    }
}
