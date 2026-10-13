package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DeepCobaltBlue
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.SanrakshaBorderLight
import com.example.ui.theme.SoftBlueTint
import kotlinx.coroutines.delay

import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R

data class CarouselSlide(
    val id: Int,
    val headline: String,
    val subtitle: String,
    val ctaText: String,
    val bgStartColor: Color,
    val bgEndColor: Color,
    val accentColor: Color,
    val imageResId: Int
)

@Composable
fun TopBannerCarousel(
    onAddDocumentClick: () -> Unit,
    onExploreSecurityClick: () -> Unit,
    onSetReminderClick: () -> Unit,
    onExploreVaultClick: () -> Unit,
    onScanDocumentClick: () -> Unit = onAddDocumentClick,
    modifier: Modifier = Modifier
) {
    val slides = listOf(
        CarouselSlide(
            id = 1,
            headline = "All your important documents. One place.",
            subtitle = "Stay organized. Find everything faster.",
            ctaText = "Add Document",
            bgStartColor = Color(0xFFFFFFFF),
            bgEndColor = Color(0xFFEFF6FF),
            accentColor = ElectricBlue,
            imageResId = R.drawable.illustration_docs_vault
        ),
        CarouselSlide(
            id = 2,
            headline = "Your privacy comes first.",
            subtitle = "Keep your documents under your control.",
            ctaText = "Explore Security",
            bgStartColor = Color(0xFFFFFFFF),
            bgEndColor = Color(0xFFEFF6FF),
            accentColor = DeepCobaltBlue,
            imageResId = R.drawable.illustration_privacy_shield
        ),
        CarouselSlide(
            id = 3,
            headline = "Never miss an important date.",
            subtitle = "Get reminders before documents expire.",
            ctaText = "Set Reminder",
            bgStartColor = Color(0xFFFFFFFF),
            bgEndColor = Color(0xFFF0FDF4),
            accentColor = Color(0xFF15803D),
            imageResId = R.drawable.illustration_reminders_calendar
        ),
        CarouselSlide(
            id = 4,
            headline = "Scan, save, and organize.",
            subtitle = "Bring your PDFs and images together.",
            ctaText = "Scan Document",
            bgStartColor = Color(0xFFFFFFFF),
            bgEndColor = Color(0xFFEFF6FF),
            accentColor = ElectricBlue,
            imageResId = R.drawable.illustration_scan_organize
        ),
        CarouselSlide(
            id = 5,
            headline = "Your documents. Your control.",
            subtitle = "A simpler way to manage your personal records.",
            ctaText = "Explore Sanraksha",
            bgStartColor = Color(0xFFFFFFFF),
            bgEndColor = Color(0xFFFAF5FF),
            accentColor = Color(0xFF7C3AED),
            imageResId = R.drawable.illustration_vault_identity
        )
    )

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()

    // Auto-advance every 5 seconds while not being dragged
    LaunchedEffect(isDragged) {
        if (!isDragged) {
            while (true) {
                delay(5000)
                val nextPage = (pagerState.currentPage + 1) % slides.size
                pagerState.animateScrollToPage(
                    nextPage,
                    animationSpec = tween(durationMillis = 600)
                )
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .testTag("top_banner_carousel"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, SanrakshaBorderLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val slide = slides[pageIndex]
                BannerSlideItem(
                    slide = slide,
                    onCtaClick = {
                        when (slide.id) {
                            1 -> onAddDocumentClick()
                            2 -> onExploreSecurityClick()
                            3 -> onSetReminderClick()
                            4 -> onScanDocumentClick()
                            5 -> onExploreVaultClick()
                        }
                    }
                )
            }

            // Pagination Dots Indicator
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(slides.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (isSelected) 22.dp else 6.dp,
                        animationSpec = tween(durationMillis = 300),
                        label = "dot_width"
                    )
                    Box(
                        modifier = Modifier
                            .height(6.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) ElectricBlue else Color(0xFFCBD5E1)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerSlideItem(
    slide: CarouselSlide,
    onCtaClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(slide.bgStartColor, slide.bgEndColor)
                )
            )
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left content: Headline, Subtitle, CTA
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = slide.headline,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        lineHeight = 22.sp
                    ),
                    color = Color(0xFF101114),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = slide.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        color = Color(0xFF626B78)
                    ),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onCtaClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = slide.accentColor),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 8.dp
                    ),
                    modifier = Modifier
                        .height(38.dp)
                        .testTag("carousel_cta_${slide.id}")
                ) {
                    Text(
                        text = slide.ctaText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                }
            }

            // Right content: Sophisticated 3D Composition Graphic
            Box(
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                BannerIllustration(slide = slide)
            }
        }
    }
}

/**
 * High-fidelity 3D WebP illustration matching the slide theme.
 */
@Composable
private fun BannerIllustration(
    slide: CarouselSlide
) {
    Box(
        modifier = Modifier.size(110.dp),
        contentAlignment = Alignment.Center
    ) {
        // Soft atmospheric radial glowing ring in the background
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(slide.accentColor.copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )

        // Bundled 3D Illustration WebP Asset
        Image(
            painter = painterResource(id = slide.imageResId),
            contentDescription = slide.headline,
            modifier = Modifier
                .size(94.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Fit
        )
    }
}
