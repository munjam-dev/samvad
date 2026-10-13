package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CategoryEntity
import com.example.data.model.DocumentEntity
import com.example.ui.components.CategoryIconHelper
import com.example.ui.components.TopBannerCarousel
import com.example.ui.theme.DeepCobaltBlue
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SanrakshaBorderLight
import com.example.ui.theme.SanrakshaSurfaceVariantLight
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: VaultViewModel,
    onNavigateToAddDocument: () -> Unit,
    onNavigateToCameraScan: () -> Unit,
    onNavigateToDocuments: (selectedCategory: String?) -> Unit,
    onNavigateToDocumentDetail: (documentId: String) -> Unit,
    onNavigateToReminders: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val documentCount by viewModel.documentCount.collectAsStateWithLifecycle()
    val expiringSoonCount by viewModel.expiringSoonCount.collectAsStateWithLifecycle()
    val recentDocs by viewModel.recentDocuments.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val reminders by viewModel.allReminders.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingActionButton(
                    onClick = onNavigateToCameraScan,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = ElectricBlue,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("home_fab_scan_document")
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Scan Document")
                }

                ExtendedFloatingActionButton(
                    onClick = onNavigateToAddDocument,
                    icon = { Icon(Icons.Filled.Add, contentDescription = "Add Document") },
                    text = { Text("Add Document", fontWeight = FontWeight.SemiBold) },
                    containerColor = ElectricBlue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.testTag("home_fab_add_document")
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("home_screen_scroll"),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // 1. Top Bar Header
            item {
                HomeHeader(
                    onSettingsClick = onNavigateToSettings,
                    onRemindersClick = onNavigateToReminders,
                    hasReminders = reminders.isNotEmpty()
                )
            }

            // 2. Promotional Top Banner Carousel
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TopBannerCarousel(
                        onAddDocumentClick = onNavigateToAddDocument,
                        onExploreSecurityClick = onNavigateToSettings,
                        onSetReminderClick = onNavigateToReminders,
                        onExploreVaultClick = { onNavigateToDocuments(null) },
                        onScanDocumentClick = onNavigateToCameraScan
                    )
                }
            }

            // 3. Vault Statistics Bar
            item {
                VaultOverviewCard(
                    totalDocs = documentCount,
                    expiringSoon = expiringSoonCount,
                    totalCategories = categories.size
                )
            }

            // 4. Quick Category Shortcuts
            item {
                SectionHeader(
                    title = "Quick Categories",
                    actionTitle = "See all",
                    onActionClick = { onNavigateToDocuments(null) }
                )
                CategoryShortcutsRow(
                    categories = categories,
                    onCategoryClick = { categoryId ->
                        onNavigateToDocuments(categoryId)
                    }
                )
            }

            // 5. Recent Documents Section or Empty State
            if (documentCount == 0) {
                item {
                    EmptyVaultHeroCard(
                        onAddDocumentClick = onNavigateToAddDocument,
                        onScanDocumentClick = onNavigateToCameraScan
                    )
                }
            } else {
                item {
                    SectionHeader(
                        title = "Recent Documents",
                        actionTitle = "View all ($documentCount)",
                        onActionClick = { onNavigateToDocuments(null) }
                    )
                }

                items(recentDocs, key = { it.id }) { doc ->
                    RecentDocumentItem(
                        document = doc,
                        categories = categories,
                        onClick = { onNavigateToDocumentDetail(doc.id) }
                    )
                }
            }

            // 6. Security & Privacy Guarantee Note
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SecurityGuaranteeCard(onLearnMore = onNavigateToSettings)
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onSettingsClick: () -> Unit,
    onRemindersClick: () -> Unit,
    hasReminders: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElectricBlue),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = "Sanraksha Shield",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Sanraksha",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "संरक्षा",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Normal,
                            color = ElectricBlue
                        )
                    )
                }
                Text(
                    text = "Your Documents. Your Control.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        Row {
            IconButton(
                onClick = onRemindersClick,
                modifier = Modifier.testTag("home_header_reminders")
            ) {
                Box {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = "Reminders",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                    if (hasReminders) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(WarningAmber)
                                .align(Alignment.TopEnd)
                        )
                    }
                }
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.testTag("home_header_settings")
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
private fun VaultOverviewCard(
    totalDocs: Int,
    expiringSoon: Int,
    totalCategories: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("vault_overview_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(label = "Vault Files", value = totalDocs.toString(), color = ElectricBlue)
            StatDivider()
            StatItem(label = "Expiring Soon", value = expiringSoon.toString(), color = if (expiringSoon > 0) WarningAmber else SuccessGreen)
            StatDivider()
            StatItem(label = "Categories", value = totalCategories.toString(), color = DeepCobaltBlue)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .height(28.dp)
            .width(1.dp)
            .background(SanrakshaBorderLight)
    )
}

@Composable
private fun SectionHeader(
    title: String,
    actionTitle: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        if (actionTitle != null && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(
                    text = actionTitle,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = ElectricBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }
    }
}

@Composable
private fun CategoryShortcutsRow(
    categories: List<CategoryEntity>,
    onCategoryClick: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(categories, key = { it.id }) { cat ->
            CategoryTile(category = cat, onClick = { onCategoryClick(cat.id) })
        }
    }
}

@Composable
private fun CategoryTile(
    category: CategoryEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(105.dp)
            .clickable(onClick = onClick)
            .testTag("category_tile_${category.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SanrakshaSurfaceVariantLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = CategoryIconHelper.getIconForCategory(category.iconKey),
                    contentDescription = category.name,
                    tint = ElectricBlue,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = category.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                ),
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RecentDocumentItem(
    document: DocumentEntity,
    categories: List<CategoryEntity>,
    onClick: () -> Unit
) {
    val categoryName = categories.find { it.id == document.categoryId }?.name ?: "General"
    val isPdf = document.mimeType.contains("pdf", ignoreCase = true)
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val dateStr = dateFormat.format(Date(document.createdAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick)
            .testTag("recent_doc_${document.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isPdf) Color(0xFFFEE2E2) else Color(0xFFDBEAFE)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.Description,
                    contentDescription = if (isPdf) "PDF" else "Document",
                    tint = if (isPdf) ErrorRed else ElectricBlue,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = categoryName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ElectricBlue,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    )
                    Text(
                        text = " • $dateStr",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    )
                }
            }

            // Expiry indicator pill if set
            if (document.expiryDate != null) {
                val isExpired = document.expiryDate < System.currentTimeMillis()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isExpired) Color(0xFFFEE2E2) else Color(0xFFFEF3C7))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isExpired) "Expired" else "Exp: ${SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(document.expiryDate))}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = if (isExpired) ErrorRed else WarningAmber,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultHeroCard(
    onAddDocumentClick: () -> Unit,
    onScanDocumentClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("empty_vault_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(SanrakshaSurfaceVariantLight),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = "Empty Vault",
                    tint = ElectricBlue,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Your vault starts here.",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Add your first document to keep everything organized, encrypted, and ready when you need it.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onScanDocumentClick,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("empty_state_scan_button")
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp), tint = ElectricBlue)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan Document", fontWeight = FontWeight.SemiBold, color = ElectricBlue)
                }

                androidx.compose.material3.Button(
                    onClick = onAddDocumentClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                    modifier = Modifier.testTag("empty_state_add_button")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import File", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SecurityGuaranteeCard(onLearnMore: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onLearnMore),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Hardware Security",
                tint = ElectricBlue,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hardware-Backed AES-256-GCM Encryption",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Files are encrypted at rest with Android Keystore. 100% offline & private.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
