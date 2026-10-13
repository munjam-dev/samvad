package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.security.PreferencesManager
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SanrakshaBorderLight
import com.example.ui.viewmodel.VaultViewModel

@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    preferencesManager: PreferencesManager,
    modifier: Modifier = Modifier
) {
    val isAppLockEnabled by preferencesManager.isAppLockEnabled.collectAsStateWithLifecycle()
    val isBiometricEnabled by preferencesManager.isBiometricEnabled.collectAsStateWithLifecycle()
    val themeMode by preferencesManager.themeMode.collectAsStateWithLifecycle()
    val notificationsEnabled by preferencesManager.notificationsEnabled.collectAsStateWithLifecycle()

    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    var showWipeConfirm by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Privacy & Settings",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Control your vault protection, theme, and data",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Security & App Lock
            SettingsSectionHeader(title = "Security & App Lock")

            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Filled.Lock,
                    title = "App Lock Protection",
                    subtitle = "Require PIN or biometrics when opening Sanraksha",
                    checked = isAppLockEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !preferencesManager.hasPin()) {
                            showPinDialog = true
                        } else {
                            preferencesManager.setAppLockEnabled(enabled)
                        }
                    },
                    testTag = "switch_app_lock"
                )

                if (isAppLockEnabled) {
                    SettingsDivider()
                    SettingsSwitchRow(
                        icon = Icons.Filled.Fingerprint,
                        title = "Biometric Authentication",
                        subtitle = "Use fingerprint or face unlock where supported",
                        checked = isBiometricEnabled,
                        onCheckedChange = { preferencesManager.setBiometricEnabled(it) },
                        testTag = "switch_biometric"
                    )

                    SettingsDivider()
                    SettingsActionRow(
                        icon = Icons.Filled.Pin,
                        title = "Change 4-Digit Security PIN",
                        subtitle = "Fallback security code",
                        onClick = { showPinDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Preferences
            SettingsSectionHeader(title = "Preferences")

            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Filled.Notifications,
                    title = "Expiry Notifications",
                    subtitle = "Proactive offline alerts at 30, 7, and 1 day marks",
                    checked = notificationsEnabled,
                    onCheckedChange = { preferencesManager.setNotificationsEnabled(it) },
                    testTag = "switch_notifications"
                )

                SettingsDivider()

                SettingsActionRow(
                    icon = Icons.Filled.BrightnessMedium,
                    title = "Appearance Theme",
                    subtitle = when (themeMode) {
                        "light" -> "Always Light"
                        "dark" -> "Always Dark"
                        else -> "System Default"
                    },
                    onClick = {
                        val next = when (themeMode) {
                            "system" -> "light"
                            "light" -> "dark"
                            else -> "system"
                        }
                        preferencesManager.setThemeMode(next)
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: Vault & Data Management
            SettingsSectionHeader(title = "Data Management")

            SettingsCard {
                SettingsActionRow(
                    icon = Icons.Filled.DeleteForever,
                    title = "Wipe All Vault Data",
                    subtitle = "Permanently delete all encrypted documents and metadata",
                    titleColor = ErrorRed,
                    onClick = { showWipeConfirm = true }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Section: About & Privacy
            SettingsSectionHeader(title = "Trust & Privacy")

            SettingsCard {
                SettingsActionRow(
                    icon = Icons.Filled.PrivacyTip,
                    title = "Privacy Policy & Architecture",
                    subtitle = "Zero telemetry, zero trackers, offline-first design",
                    onClick = { showPrivacyPolicy = true }
                )

                SettingsDivider()

                SettingsActionRow(
                    icon = Icons.Filled.HelpOutline,
                    title = "Help & User Guide",
                    subtitle = "Supported formats, key backup, and FAQs",
                    onClick = { showHelpDialog = true }
                )

                SettingsDivider()

                SettingsActionRow(
                    icon = Icons.Filled.Info,
                    title = "About Sanraksha",
                    subtitle = "Version 1.0 • Your Documents. Your Control.",
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    // Set PIN Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                enteredPin = ""
                pinError = null
            },
            title = { Text("Set 4-Digit Vault PIN") },
            text = {
                Column {
                    Text(
                        text = "This PIN acts as your secure offline master password for unlocking Sanraksha.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            if (it.length <= 6) {
                                enteredPin = it
                                pinError = null
                            }
                        },
                        label = { Text("4-6 Digit PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pinError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_pin_field")
                    )
                    if (pinError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(pinError!!, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (enteredPin.length < 4) {
                            pinError = "PIN must be at least 4 digits"
                        } else {
                            preferencesManager.setPin(enteredPin)
                            preferencesManager.setAppLockEnabled(true)
                            showPinDialog = false
                            enteredPin = ""
                        }
                    }
                ) {
                    Text("Save & Enable", fontWeight = FontWeight.Bold, color = ElectricBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Wipe All Data Dialog
    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("Wipe All Vault Data?") },
            text = {
                Text(
                    "Warning: This will permanently delete ALL imported files, encrypted documents, categories, and reminders from this device. This operation cannot be undone.",
                    color = ErrorRed
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllDocuments {
                            showWipeConfirm = false
                        }
                    }
                ) {
                    Text("Erase Everything", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Privacy Policy Dialog
    if (showPrivacyPolicy) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicy = false },
            title = { Text("Sanraksha Privacy Policy") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "1. Zero Cloud Transmission\nSanraksha operates 100% on your device. None of your document files, titles, or notes are uploaded to any external server.\n\n" +
                                "2. Hardware-Backed Encryption\nStored documents are encrypted using AES-256-GCM. Cryptographic keys are generated and isolated inside Android's hardware Keystore.\n\n" +
                                "3. Offline Expiry Reminders\nReminders are scheduled purely via the Android system AlarmManager locally on your phone without external notification push servers.\n\n" +
                                "4. No Analytics or Advertising\nSanraksha contains no tracking SDKs, no behavioral cookies, and no advertisements.",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicy = false }) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Help Dialog
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Help & User Guide") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "• Supported Files: PDFs and all standard image formats (JPEG, PNG, WebP).\n\n" +
                                "• Importing: Tap Add Document (+) and choose any file from your device. Give it a title and select a category.\n\n" +
                                "• Expiry Tracking: Choose an optional expiry date to receive automatic alerts 30, 7, and 1 day before expiration.\n\n" +
                                "• Sharing: Tap the share button in Document Details to send a decrypted copy safely to any app via Android Sharesheet.",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Understood", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            color = ElectricBlue
        ),
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricBlue),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    titleColor: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = if (titleColor != Color.Unspecified) titleColor else ElectricBlue, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = titleColor)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(SanrakshaBorderLight)
    )
}
