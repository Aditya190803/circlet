package app.circlet.ui.portability

import app.circlet.core.BackupSettings
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRulesTest {

    private fun settings(
        enabled: Boolean = true,
        interval: Duration = Duration.ofDays(1),
        folderUri: String? = "content://tree/circlet",
    ) = BackupSettings(
        enabled = enabled,
        interval = interval,
        folderUri = folderUri,
        lastBackup = Instant.parse("2026-10-09T08:00:00Z"),
    )

    @Test
    fun enabledWithNoFolderNeedsAFolder() {
        assertEquals(BackupProblem.FOLDER_NOT_CHOSEN, validateBackup(settings(folderUri = null)))
    }

    @Test
    fun enabledWithBlankFolderNeedsAFolder() {
        assertEquals(BackupProblem.FOLDER_NOT_CHOSEN, validateBackup(settings(folderUri = "   ")))
    }

    @Test
    fun zeroIntervalIsRejected() {
        assertEquals(BackupProblem.INTERVAL_NOT_POSITIVE, validateBackup(settings(interval = Duration.ZERO)))
    }

    @Test
    fun negativeIntervalIsRejected() {
        assertEquals(
            BackupProblem.INTERVAL_NOT_POSITIVE,
            validateBackup(settings(interval = Duration.ofDays(-1))),
        )
    }

    @Test
    fun validEnabledSettingsPass() {
        assertNull(validateBackup(settings()))
    }

    @Test
    fun disabledWithNoFolderIsFine() {
        assertNull(validateBackup(settings(enabled = false, folderUri = null)))
    }

    @Test
    fun folderProblemIsCheckedBeforeInterval() {
        assertEquals(
            BackupProblem.FOLDER_NOT_CHOSEN,
            validateBackup(settings(interval = Duration.ZERO, folderUri = null)),
        )
    }

    @Test
    fun everyIntervalOfferedIsPositive() {
        BACKUP_INTERVALS.forEach { assertTrue(it > Duration.ZERO) }
        assertEquals(BACKUP_INTERVALS.size, BACKUP_INTERVAL_LABELS.size)
    }
}
