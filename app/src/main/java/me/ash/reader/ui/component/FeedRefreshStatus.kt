package me.ash.reader.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import me.ash.reader.R
import me.ash.reader.domain.data.SyncProgress

@Composable
fun FeedRefreshStatus(progress: SyncProgress, modifier: Modifier = Modifier) {
    var showFailure by remember(progress.startedAt, progress.isRunning) { mutableStateOf(true) }
    LaunchedEffect(progress.startedAt, progress.isRunning) {
        if (!progress.isRunning) {
            delay(8_000)
            showFailure = false
        }
    }
    if (progress.total == 0 || (!progress.isRunning && (!showFailure || progress.failed == 0))) return
    val text = if (progress.isRunning) {
        stringResource(R.string.feed_refresh_progress, progress.completed, progress.total)
    } else {
        stringResource(R.string.feed_refresh_failed, progress.failed, progress.total)
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = if (progress.isRunning) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
    )
}
