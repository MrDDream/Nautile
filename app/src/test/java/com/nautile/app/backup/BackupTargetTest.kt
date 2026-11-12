package com.nautile.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTargetTest {
    @Test fun everyKindRoundTrips() {
        listOf(
            BackupTarget("a", BackupDestination.Document("content://x/doc", "Google Drive › nautile.backup"), auto = true, frequency = BackupFrequency.DAY, time = 90, last = 42L),
            BackupTarget("b", BackupDestination.WebDav("https://cloud/remote.php/dav/files/me/", "me"), network = BackupNetwork.ANY, error = "boom"),
            BackupTarget("c", BackupDestination.S3("s3.example.com", "eu", "bucket", "dir/nautile.backup", "AK"), charging = true, weekday = 3),
            BackupTarget("d", BackupDestination.Folder("content://tree/x", "Téléphone › Sync")),
        ).forEach { t -> assertEquals(t, BackupPrefs.fromJson(BackupPrefs.toJson(t))) }
    }
}
