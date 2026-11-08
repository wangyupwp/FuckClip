package app.fuckclip.ui.log

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fuckclip.R
import app.fuckclip.core.model.HookLog
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Screen displaying the history of clipboard read and write access records.
 *
 * 显示剪贴板读取与写入访问使用记录界面的 Composable 组件。
 */
@Composable
fun LogScreen(
    viewModel: LogViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    val allLogs by viewModel.logs.collectAsStateWithLifecycle()
    val logs by viewModel.displayedLogs.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filterMode by viewModel.filterMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dateFormat = rememberDateFormat()

    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showArchive by remember { mutableStateOf(false) }
    var selectedLogForDetail by remember { mutableStateOf<HookLog?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top Bar Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.title_history),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.ui_record_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showArchive = true }) {
                    Text(stringResource(R.string.log_archive_title))
                }
                if (allLogs.isNotEmpty()) {
                    IconButton(onClick = { showClearConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.options_clear_history),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (allLogs.isNotEmpty()) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text(stringResource(R.string.options_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Direction and outcome filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogFilterMode.entries.forEach { mode ->
                    FilterChip(
                        selected = filterMode == mode,
                        onClick = { viewModel.setFilterMode(mode) },
                        label = { Text(stringResource(mode.labelRes())) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        if (allLogs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.empty_record_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.empty_record_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.log_filter_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs, key = { it.id }, contentType = { "log" }) { log ->
                    LogRecordCard(
                        log = log,
                        context = context,
                        dateFormat = dateFormat,
                        onClick = { selectedLogForDetail = log }
                    )
                }
            }
        }
    }

    if (showArchive) {
        LogArchiveDialog(viewModel, dateFormat, onDismiss = { showArchive = false })
    }

    // Detail Dialog for Full Content View
    selectedLogForDetail?.let { log ->
        ClipboardContentDetailDialog(
            log = log,
            context = context,
            onDismiss = { selectedLogForDetail = null }
        )
    }

    // Clear History Confirmation Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text(stringResource(R.string.settings_clear_history_confirm)) },
            text = { Text(stringResource(R.string.log_clear_including_archive)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearLogs()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(android.R.string.ok),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun rememberDateFormat(): SimpleDateFormat {
    return remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
}

/**
 * Localized chip label resource for a history filter mode.
 *
 * 使用记录筛选模式对应的本地化标签资源。
 */
@StringRes
private fun LogFilterMode.labelRes(): Int = when (this) {
    LogFilterMode.ALL -> R.string.whitelist_filter_all
    LogFilterMode.READ -> R.string.ui_rule_read
    LogFilterMode.WRITE -> R.string.ui_rule_write
    LogFilterMode.ALLOWED -> R.string.ui_action_allowed
    LogFilterMode.DENIED -> R.string.ui_action_denied
}
