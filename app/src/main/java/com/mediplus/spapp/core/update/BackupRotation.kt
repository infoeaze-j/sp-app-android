package com.mediplus.spapp.core.update

/**
 * The pure retention rule for rollback backups (self-update design): keep exactly the previous
 * version once an update has succeeded, and never delete anything on a failure path.
 *
 * The rule is derived entirely from file names and the running versionCode, so it is idempotent
 * and crash-safe with no persisted marker: delete every backup below the running version EXCEPT
 * the highest such (that one IS the rollback); keep anything at or above the running version and
 * anything unparseable.
 */
object BackupRotation {

    private const val PREFIX = "spapp-backup-v"
    private const val SUFFIX = ".apk"
    private val NAME = Regex("""spapp-backup-v(\d+)\.apk""")

    fun backupFileName(versionCode: Int): String = "$PREFIX$versionCode$SUFFIX"

    /** Backup file names that should be deleted, given what exists and the running versionCode. */
    fun staleBackups(fileNames: List<String>, currentVersionCode: Int): List<String> {
        val belowCurrent = fileNames
            .mapNotNull { name -> versionCodeOf(name)?.let { code -> name to code } }
            .filter { (_, code) -> code < currentVersionCode }
        val rollback = belowCurrent.maxByOrNull { (_, code) -> code }?.first
        return belowCurrent.map { (name, _) -> name }.filterNot { it == rollback }
    }

    private fun versionCodeOf(fileName: String): Int? =
        NAME.matchEntire(fileName)?.groupValues?.get(1)?.toIntOrNull()
}
