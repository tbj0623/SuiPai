package com.suipai.app.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/**
 * 备份来源的安装标识，只用于避免跨设备把 MediaStore 数字 id 指向错误的照片。
 *
 * 放在 noBackupFilesDir：Android 系统的自动换机/云备份不能把这个标识也迁过去。
 * 它不是账号、不是加密凭据，不能用于鉴别不可信的备份文件。
 */
internal object BackupInstallIdentity {
    private const val FILE_NAME = "backup-origin-id"
    private val lock = Any()

    fun current(context: Context): String? = synchronized(lock) {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val atomic = AtomicFile(file)
        val saved = runCatching {
            atomic.openRead().use { it.reader(Charsets.UTF_8).readText().trim() }
        }.getOrNull()
        if (saved != null && runCatching { UUID.fromString(saved).toString() == saved }.getOrDefault(false)) {
            return@synchronized saved
        }

        val fresh = UUID.randomUUID().toString()
        val output = runCatching { atomic.startWrite() }.getOrNull() ?: return@synchronized null
        try {
            output.write(fresh.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            fresh
        } catch (_: Exception) {
            runCatching { atomic.failWrite(output) }
            null
        }
    }
}
