package com.suipai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaWriteTransactionTest {
    @Test
    fun successPublishesWithoutDeletingOriginal() {
        val calls = mutableListOf<String>()
        val result = insertWritePublishOrRollback(
            insert = { calls += "insert"; "content://photo/1" },
            write = { calls += "write"; true },
            publish = { calls += "publish"; true },
            rollback = { calls += "delete" },
        )
        assertEquals("content://photo/1", result)
        assertEquals(listOf("insert", "write", "publish"), calls)
    }

    @Test
    fun nullInsertDoesNotCallWriteOrDelete() {
        val calls = mutableListOf<String>()
        val result = insertWritePublishOrRollback<String>(
            insert = { null },
            write = { calls += "write"; true },
            publish = { calls += "publish"; true },
            rollback = { calls += "delete" },
        )
        assertNull(result)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun nullOutputStreamRollsBackBeforePublish() {
        val calls = mutableListOf<String>()
        val result = insertWritePublishOrRollback(
            insert = { "item" },
            write = { calls += "write"; false },
            publish = { calls += "publish"; true },
            rollback = { calls += "delete" },
        )
        assertNull(result)
        assertEquals(listOf("write", "delete"), calls)
    }

    @Test
    fun failedPublishRollsBackCompletedBytes() {
        val calls = mutableListOf<String>()
        val result = insertWritePublishOrRollback(
            insert = { "item" },
            write = { calls += "write"; true },
            publish = { calls += "publish"; false },
            rollback = { calls += "delete" },
        )
        assertNull(result)
        assertEquals(listOf("write", "publish", "delete"), calls)
    }

    @Test
    fun thrownWriteStillRollsBack() {
        val failures = mutableListOf<Throwable>()
        var deletes = 0
        val result = insertWritePublishOrRollback(
            insert = { "item" },
            write = { throw IllegalStateException("disk full") },
            publish = { true },
            rollback = { deletes++ },
            onFailure = { failures += it },
        )
        assertNull(result)
        assertEquals(1, deletes)
        assertEquals("disk full", failures.single().message)
    }

    @Test
    fun failedRollbackDoesNotMaskPrimaryError() {
        val failures = mutableListOf<String?>()
        val result = insertWritePublishOrRollback(
            insert = { "item" },
            write = { false },
            publish = { true },
            rollback = { throw IllegalStateException("delete denied") },
            onFailure = { failures += it.message },
        )
        assertNull(result)
        assertEquals(listOf("delete denied"), failures)
    }

    @Test
    fun failedInsertDoesNotAttemptRollback() {
        var rollbackCalled = false
        val result = insertWritePublishOrRollback(
            insert = { throw IllegalStateException("provider offline") },
            write = { true },
            publish = { true },
            rollback = { rollbackCalled = true },
        )
        assertNull(result)
        assertEquals(false, rollbackCalled)
    }
}
