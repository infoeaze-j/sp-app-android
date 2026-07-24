package com.mediplus.faceverify.core.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Retention rule (self-update design): on every launch, delete every backup below the running
 * version EXCEPT the highest such — that one IS the rollback. Anything at or above the running
 * version and anything unparseable is never touched, so a failed update (still running the old
 * build) can never lose its own rollback chain.
 */
class BackupRotationTest {

    @Test
    fun `backup file names are self-describing`() {
        assertEquals("faceverify-backup-v7.apk", BackupRotation.backupFileName(7))
    }

    @Test
    fun `after a successful update the older backup is pruned, the previous version kept`() {
        val stale = BackupRotation.staleBackups(
            listOf("faceverify-backup-v1.apk", "faceverify-backup-v2.apk"),
            currentVersionCode = 3,
        )

        assertEquals(listOf("faceverify-backup-v1.apk"), stale)
    }

    @Test
    fun `several stale generations are all pruned at once`() {
        val stale = BackupRotation.staleBackups(
            listOf("faceverify-backup-v1.apk", "faceverify-backup-v2.apk", "faceverify-backup-v3.apk"),
            currentVersionCode = 4,
        )

        assertEquals(listOf("faceverify-backup-v1.apk", "faceverify-backup-v2.apk"), stale)
    }

    @Test
    fun `a failed update deletes nothing`() {
        // Still running v2 after a failed v3 attempt: v2's own backup (v1) must survive.
        val stale = BackupRotation.staleBackups(
            listOf("faceverify-backup-v1.apk", "faceverify-backup-v2.apk"),
            currentVersionCode = 2,
        )

        assertEquals(emptyList<String>(), stale)
    }

    @Test
    fun `backups at or above the running version are never pruned`() {
        val stale = BackupRotation.staleBackups(
            listOf("faceverify-backup-v5.apk", "faceverify-backup-v9.apk"),
            currentVersionCode = 5,
        )

        assertEquals(emptyList<String>(), stale)
    }

    @Test
    fun `unparseable names are never pruned`() {
        val stale = BackupRotation.staleBackups(
            listOf(
                "faceverify-backup-vX.apk",
                "random.apk",
                "faceverify-backup-v1.apk",
                "faceverify-backup-v2.apk",
            ),
            currentVersionCode = 3,
        )

        assertEquals(listOf("faceverify-backup-v1.apk"), stale)
    }

    @Test
    fun `an empty directory is a no-op`() {
        assertEquals(emptyList<String>(), BackupRotation.staleBackups(emptyList(), currentVersionCode = 3))
    }
}
