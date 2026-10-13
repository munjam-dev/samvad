package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector? = null,
    val unselectedIcon: ImageVector? = null
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    data object Documents : Screen("documents", "Documents", Icons.Filled.Description, Icons.Outlined.Description)
    data object Reminders : Screen("reminders", "Reminders", Icons.Filled.Notifications, Icons.Outlined.Notifications)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)

    data object Onboarding : Screen("onboarding", "Onboarding")
    data object Lock : Screen("lock", "Lock")
    data object AddDocument : Screen("add_document", "Add Document")
    data object CameraScan : Screen("camera_scan", "Scan Document")
    data object DocumentCrop : Screen("document_crop", "Crop Document")
    data object Categories : Screen("categories", "Categories")
    data object PrivacyPolicy : Screen("privacy_policy", "Privacy Policy")
    data object DocumentDetail : Screen("document_detail/{documentId}", "Document Details") {
        fun createRoute(documentId: String) = "document_detail/$documentId"
    }

    companion object {
        val bottomNavItems: List<Screen>
            get() = listOf(Home, Documents, Reminders, Settings)
    }
}
