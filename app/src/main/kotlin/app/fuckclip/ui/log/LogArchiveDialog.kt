package app.fuckclip.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.fuckclip.R
import app.fuckclip.core.model.HookLog
import app.fuckclip.data.ArchivedLogPage
import java.text.SimpleDateFormat
import kotlinx.coroutines.CancellationException

@Composable
internal fun LogArchiveDialog(viewModel: LogViewModel, dateFormat: SimpleDateFormat, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cursors by remember { mutableStateOf(listOf<Long?>(null)) }
    var page by remember { mutableStateOf<ArchivedLogPage?>(null) }
    var error by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var selectedLog by remember { mutableStateOf<HookLog?>(null) }

    LaunchedEffect(cursors, attempt) {
        page = null
        error = false
        try {
            page = viewModel.readArchive(cursors.last())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_archive_title)) },
        text = {
            val loaded = page
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    error -> {
                        Text(stringResource(R.string.log_archive_load_failed))
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.log_archive_retry)) }
                    }
                    loaded == null -> CircularProgressIndicator()
                    loaded.logs.isEmpty() -> Text(stringResource(R.string.log_archive_empty))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(loaded.logs, key = { it.id }) { log ->
                            LogRecordCard(log, context, dateFormat, onClick = { selectedLog = log })
                        }
                    }
                }
                if (cursors.size > 1 || loaded?.nextBefore != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = cursors.size > 1,
                            onClick = { cursors = cursors.dropLast(1) }
                        ) { Text(stringResource(R.string.log_archive_newer)) }
                        TextButton(
                            enabled = loaded?.nextBefore != null,
                            onClick = { loaded?.nextBefore?.let { cursors = cursors + it } }
                        ) { Text(stringResource(R.string.log_archive_older)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )

    selectedLog?.let { log ->
        ClipboardContentDetailDialog(log, context, onDismiss = { selectedLog = null })
    }
}
