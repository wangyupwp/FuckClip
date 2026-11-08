package app.fuckclip.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.fuckclip.R
import org.koin.androidx.compose.koinViewModel

/**
 * Settings and About screen containing app preferences, auto update toggles, throttle controls, and project credits.
 *
 * 设置与关于界面，整合全局偏好、自动更新检查、毒瘤限流配置以及开源项目信息。
 */
@Composable
fun SettingsScreen(
    onNavigateToTheme: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = run {
        val owner = (LocalContext.current as? ComponentActivity)
            ?: LocalViewModelStoreOwner.current
        if (owner != null) koinViewModel(viewModelStoreOwner = owner) else koinViewModel()
    }
) {
    val context = LocalContext.current
    val masterEnabled by viewModel.masterEnabled.collectAsStateWithLifecycle()
    val rogueThrottleEnabled by viewModel.rogueThrottleEnabled.collectAsStateWithLifecycle()
    val isAutoUpdateCheckEnabled by viewModel.isAutoUpdateCheckEnabled.collectAsStateWithLifecycle()
    val showSystemApps by viewModel.showSystemApps.collectAsStateWithLifecycle()
    val selectedLanguageTag by viewModel.selectedLanguageTag.collectAsStateWithLifecycle()

    val denyCooldownSeconds by viewModel.denyCooldownSeconds.collectAsStateWithLifecycle()
    val grantBurstSeconds by viewModel.grantBurstSeconds.collectAsStateWithLifecycle()
    val maxReadFrequency by viewModel.maxReadFrequency.collectAsStateWithLifecycle()
    val readTolerance by viewModel.readToleranceCount.collectAsStateWithLifecycle()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showThrottleConfigDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportRulesToFile(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importRulesFromFile(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header Banner
        HeroHeaderCard(
            versionName = viewModel.versionName,
            versionCode = viewModel.versionCode,
            masterEnabled = masterEnabled
        )

        // General Settings Section
        SettingsSectionTitle(title = stringResource(R.string.title_settings))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column {
                SettingsSwitchRow(
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.settings_master_switch),
                    subtitle = stringResource(R.string.settings_master_switch_summary),
                    checked = masterEnabled,
                    onCheckedChange = { viewModel.setMasterEnabled(it) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionRow(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.title_theme),
                    subtitle = stringResource(R.string.settings_theme_summary),
                    onClick = onNavigateToTheme
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionRow(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.settings_language),
                    subtitle = viewModel.supportedLanguages.firstOrNull { it.first == selectedLanguageTag }?.second
                        ?: stringResource(R.string.language_follow_system),
                    onClick = { showLanguageDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsSwitchRow(
                    icon = Icons.Default.Visibility,
                    title = stringResource(R.string.options_show_system),
                    subtitle = stringResource(R.string.settings_show_system_summary),
                    checked = showSystemApps,
                    onCheckedChange = { viewModel.setShowSystemApps(it) }
                )
            }
        }

        // Interception & Protection Section
        SettingsSectionTitle(title = stringResource(R.string.settings_section_protection))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column {
                SettingsSwitchRow(
                    icon = Icons.Default.Speed,
                    title = stringResource(R.string.home_rogue_throttle_title),
                    subtitle = stringResource(R.string.home_rogue_throttle_subtitle),
                    checked = rogueThrottleEnabled,
                    onCheckedChange = { viewModel.setRogueThrottleEnabled(it) }
                )
                if (rogueThrottleEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsActionRow(
                        icon = Icons.Default.Speed,
                        title = stringResource(R.string.home_rogue_throttle_config_title),
                        subtitle = stringResource(
                            R.string.home_rogue_throttle_status,
                            denyCooldownSeconds,
                            readTolerance,
                            maxReadFrequency
                        ),
                        onClick = { showThrottleConfigDialog = true }
                    )
                }
            }
        }

        // Updates Section
        SettingsSectionTitle(title = stringResource(R.string.settings_section_update))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column {
                SettingsSwitchRow(
                    icon = Icons.Default.SystemUpdate,
                    title = stringResource(R.string.settings_auto_update),
                    subtitle = stringResource(R.string.settings_auto_update_summary),
                    checked = isAutoUpdateCheckEnabled,
                    onCheckedChange = { viewModel.setAutoUpdateCheckEnabled(it) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionRow(
                    icon = Icons.Default.Refresh,
                    title = stringResource(R.string.about_check_update),
                    subtitle = stringResource(
                        R.string.about_check_update_desc,
                        viewModel.versionName,
                        viewModel.versionCode
                    ),
                    onClick = { viewModel.checkForUpdates(manual = true) }
                )
            }
        }

        // Data Management Section
        SettingsSectionTitle(title = stringResource(R.string.settings_section_data))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column {
                SettingsActionRow(
                    icon = Icons.Default.FileUpload,
                    title = stringResource(R.string.settings_export_rules),
                    subtitle = stringResource(R.string.settings_export_rules_summary),
                    onClick = { exportLauncher.launch("fuckclip_rules_backup.json") }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionRow(
                    icon = Icons.Default.FileDownload,
                    title = stringResource(R.string.settings_import_rules),
                    subtitle = stringResource(R.string.settings_import_rules_summary),
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                LogKeepAliveSetting()
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionRow(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.options_clear_history),
                    subtitle = stringResource(R.string.settings_clear_history_summary),
                    onClick = { showClearHistoryDialog = true }
                )
            }
        }

        // About & Project Info Section
        SettingsSectionTitle(title = stringResource(R.string.about_section_project))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column {
                SettingsActionRow(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.about_github),
                    subtitle = stringResource(R.string.about_github_url),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.repoUrl))
                        context.startActivity(intent)
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsActionRow(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.about_license),
                    subtitle = stringResource(R.string.about_license_desc),
                    onClick = { showLicenseDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsCreditRow(
                    title = stringResource(R.string.about_dev_original),
                    name = stringResource(R.string.about_dev_original_name)
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsCreditRow(
                    title = stringResource(R.string.about_dev_author),
                    name = stringResource(R.string.about_dev_author_name)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Language Dialog
    if (showLanguageDialog) {
        LanguageSelectionDialog(
            supportedLanguages = viewModel.supportedLanguages,
            selectedTag = selectedLanguageTag,
            onSelect = {
                viewModel.selectLanguage(it)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    // Clear History Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text(stringResource(R.string.options_clear_history)) },
            text = { Text(stringResource(R.string.log_clear_including_archive)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearHistoryDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // License Dialog
    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text(stringResource(R.string.about_license)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ui_license_summary),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.licenseUrl))
                        context.startActivity(intent)
                    }
                ) {
                    Text(stringResource(R.string.ui_license_view_full))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text(stringResource(R.string.ui_close))
                }
            }
        )
    }

    // Frequent Read Throttle Config Dialog
    if (showThrottleConfigDialog) {
        ThrottleConfigDialog(
            initialCooldown = denyCooldownSeconds,
            initialBurst = grantBurstSeconds,
            initialFrequency = maxReadFrequency,
            initialTolerance = readTolerance,
            onSave = { cooldown, burst, frequency, tolerance ->
                viewModel.setDenyCooldownSeconds(cooldown)
                viewModel.setGrantBurstSeconds(burst)
                viewModel.setMaxReadFrequency(frequency)
                viewModel.setReadToleranceCount(tolerance)
                showThrottleConfigDialog = false
            },
            onDismiss = { showThrottleConfigDialog = false }
        )
    }
}
