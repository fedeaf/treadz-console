package com.treadz.console.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.treadz.console.R
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode

/** Translated text for a synchronisation error. The service's own messages are never shown. */
@Composable
fun syncErrorText(error: SyncError): String {
    if (error.code == SyncErrorCode.RateLimited) {
        val minutes = error.retryAfterMillis?.let { ((it + 59_999L) / 60_000L).toInt().coerceAtLeast(1) }
        if (minutes != null) return stringResource(R.string.sync_error_rate_limited_minutes, minutes)
    }
    return stringResource(
        when (error.code) {
            SyncErrorCode.NoNetwork -> R.string.sync_error_no_network
            SyncErrorCode.Unauthorized -> R.string.sync_error_unauthorized
            SyncErrorCode.NoPrivateRepository -> R.string.sync_error_no_private_repository
            SyncErrorCode.RepositoryNotFound -> R.string.sync_error_repository_not_found
            SyncErrorCode.NoWriteAccess -> R.string.sync_error_no_write_access
            SyncErrorCode.RateLimited -> R.string.sync_error_rate_limited
            SyncErrorCode.ServerError -> R.string.sync_error_server
            SyncErrorCode.Conflict -> R.string.sync_error_conflict
            SyncErrorCode.RepositoryTooLarge -> R.string.sync_error_too_large
            SyncErrorCode.InvalidResponse -> R.string.sync_error_invalid_response
            SyncErrorCode.Unknown -> R.string.sync_error_unknown
        },
    )
}
