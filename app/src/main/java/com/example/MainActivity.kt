package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.security.PreferencesManager
import com.example.ui.navigation.Screen
import com.example.ui.screens.AddDocumentScreen
import com.example.ui.screens.CameraScanScreen
import com.example.ui.screens.DocumentCropScreen
import com.example.ui.screens.DocumentDetailScreen
import com.example.ui.screens.DocumentLibraryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LockScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.RemindersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.SanrakshaBorderLight
import com.example.ui.theme.SanrakshaTheme
import com.example.ui.viewmodel.VaultViewModel

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val preferencesManager = PreferencesManager(this)

        setContent {
            val themeMode by preferencesManager.themeMode.collectAsStateWithLifecycle()
            val isDarkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            SanrakshaTheme(darkTheme = isDarkTheme) {
                SanrakshaApp(preferencesManager = preferencesManager)
            }
        }
    }
}

@Composable
fun SanrakshaApp(
    preferencesManager: PreferencesManager,
    viewModel: VaultViewModel = viewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val onboardingCompleted by preferencesManager.onboardingCompleted.collectAsStateWithLifecycle()
    val isAppLockEnabled by preferencesManager.isAppLockEnabled.collectAsStateWithLifecycle()

    var isVaultUnlocked by remember { mutableStateOf(!isAppLockEnabled) }

    val startDestination = when {
        !onboardingCompleted -> Screen.Onboarding.route
        isAppLockEnabled && !isVaultUnlocked -> Screen.Lock.route
        else -> Screen.Home.route
    }

    val showBottomNav = currentRoute in listOf(
        Screen.Home.route,
        Screen.Documents.route,
        Screen.Reminders.route,
        Screen.Settings.route
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomNav) {
                NavigationBar(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .testTag("bottom_nav_bar"),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    Screen.bottomNavItems.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                val icon = if (isSelected) screen.selectedIcon else screen.unselectedIcon
                                if (icon != null) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = screen.title
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp
                                    )
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricBlue,
                                selectedTextColor = ElectricBlue,
                                indicatorColor = Color(0xFFEFF6FF),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag("nav_item_${screen.route}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Onboarding.route) {
                OnboardingScreen(
                    onFinishOnboarding = {
                        preferencesManager.setOnboardingCompleted(true)
                        if (isAppLockEnabled && !isVaultUnlocked) {
                            navController.navigate(Screen.Lock.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        } else {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(Screen.Lock.route) {
                LockScreen(
                    preferencesManager = preferencesManager,
                    onUnlocked = {
                        isVaultUnlocked = true
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Lock.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToAddDocument = {
                        viewModel.clearImportStatus()
                        navController.navigate(Screen.AddDocument.route)
                    },
                    onNavigateToCameraScan = {
                        navController.navigate(Screen.CameraScan.route)
                    },
                    onNavigateToDocuments = { categoryId ->
                        viewModel.selectedCategoryFilter.value = categoryId
                        navController.navigate(Screen.Documents.route)
                    },
                    onNavigateToDocumentDetail = { docId ->
                        navController.navigate(Screen.DocumentDetail.createRoute(docId))
                    },
                    onNavigateToReminders = {
                        navController.navigate(Screen.Reminders.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            composable(Screen.Documents.route) {
                DocumentLibraryScreen(
                    viewModel = viewModel,
                    onNavigateToAddDocument = {
                        viewModel.clearImportStatus()
                        navController.navigate(Screen.AddDocument.route)
                    },
                    onNavigateToDocumentDetail = { docId ->
                        navController.navigate(Screen.DocumentDetail.createRoute(docId))
                    }
                )
            }

            composable(Screen.AddDocument.route) {
                AddDocumentScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToCameraScan = {
                        navController.navigate(Screen.CameraScan.route)
                    }
                )
            }

            composable(Screen.CameraScan.route) {
                CameraScanScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onPhotoCaptured = { uri ->
                        viewModel.setScannedImageForCrop(uri)
                        navController.navigate(Screen.DocumentCrop.route)
                    }
                )
            }

            composable(Screen.DocumentCrop.route) {
                val cropUri by viewModel.scannedImageUriForCrop.collectAsStateWithLifecycle()
                if (cropUri != null) {
                    DocumentCropScreen(
                        imageUri = cropUri!!,
                        onNavigateBack = { navController.popBackStack() },
                        onCropCompleted = { croppedUri ->
                            viewModel.setReadyCroppedDoc(croppedUri)
                            navController.navigate(Screen.AddDocument.route) {
                                popUpTo(Screen.Home.route) { inclusive = false }
                            }
                        }
                    )
                } else {
                    navController.popBackStack()
                }
            }

            composable(
                route = Screen.DocumentDetail.route,
                arguments = listOf(navArgument("documentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val documentId = backStackEntry.arguments?.getString("documentId") ?: ""
                DocumentDetailScreen(
                    documentId = documentId,
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Reminders.route) {
                RemindersScreen(
                    viewModel = viewModel,
                    onNavigateToDocumentDetail = { docId ->
                        navController.navigate(Screen.DocumentDetail.createRoute(docId))
                    },
                    onNavigateToAddDocument = {
                        navController.navigate(Screen.AddDocument.route)
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    preferencesManager = preferencesManager
                )
            }
        }
    }
}
