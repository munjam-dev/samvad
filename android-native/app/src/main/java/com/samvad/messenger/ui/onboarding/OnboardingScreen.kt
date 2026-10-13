package com.samvad.messenger.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import com.samvad.messenger.ui.theme.LocalReducedMotion
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samvad.messenger.R
import com.samvad.messenger.ui.components.PrimaryButton
import com.samvad.messenger.ui.components.QuietButton
import com.samvad.messenger.ui.components.SamvadMark
import com.samvad.messenger.ui.theme.SamvadTheme
import kotlinx.coroutines.launch

private val pages = listOf(
  R.string.onboarding_1_title to R.string.onboarding_1_body,
  R.string.onboarding_2_title to R.string.onboarding_2_body,
  R.string.onboarding_3_title to R.string.onboarding_3_body,
)

/** Three short value pages; no permission prompts here. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
  val colors = SamvadTheme.colors
  val pager = rememberPagerState { pages.size }
  val scope = rememberCoroutineScope()
  val last = pager.currentPage == pages.lastIndex

  Column(
    Modifier
      .fillMaxSize()
      .background(Brush.verticalGradient(listOf(colors.background, colors.primaryContainer)))
      .systemBarsPadding(),
  ) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
      QuietButton(stringResource(R.string.onboarding_skip), onDone)
    }
    HorizontalPager(pager, Modifier.weight(1f)) { page ->
      val (title, body) = pages[page]
      // Parallax: the mark turns and the copy drifts with the swipe, so pages feel layered.
      val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
      val still = LocalReducedMotion.current
      Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).graphicsLayer {
          if (!still) {
            alpha = 1f - kotlin.math.abs(offset).coerceIn(0f, 1f) * 0.6f
          }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Box(
          Modifier
            .size(196.dp)
            .graphicsLayer {
              if (!still) {
                val s = 1f - kotlin.math.abs(offset).coerceIn(0f, 1f) * 0.15f
                scaleX = s
                scaleY = s
              }
            }
            .shadow(24.dp, CircleShape, clip = false)
            .background(colors.surface, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          SamvadMark(120.dp, Modifier.graphicsLayer { if (!still) rotationZ = (page + offset) * -51.43f }, describe = page == 0)
        }
        Spacer(Modifier.height(28.dp))
        if (page == 0) {
          Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
          Text(stringResource(R.string.brand_devanagari), style = MaterialTheme.typography.titleLarge, color = colors.primary)
          Spacer(Modifier.height(20.dp))
        }
        Text(
          stringResource(title),
          modifier = Modifier.graphicsLayer { if (!still) translationX = offset * size.width * 0.25f },
          style = MaterialTheme.typography.headlineSmall,
          color = colors.onSurface,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
          stringResource(body),
          style = MaterialTheme.typography.bodyLarge,
          color = colors.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
    val pageLabel = stringResource(R.string.onboarding_page, pager.currentPage + 1, pages.size)
    Row(
      Modifier.fillMaxWidth().padding(vertical = 20.dp).semantics { contentDescription = pageLabel },
      horizontalArrangement = Arrangement.Center,
    ) {
      repeat(pages.size) { i ->
        val width = animateDpAsState(if (i == pager.currentPage) 24.dp else 8.dp, label = "dot").value
        Box(
          Modifier
            .padding(horizontal = 4.dp)
            .height(8.dp)
            .width(width)
            .background(if (i == pager.currentPage) colors.primary else colors.outlineVariant, RoundedCornerShape(4.dp)),
        )
      }
    }
    PrimaryButton(
      text = stringResource(if (last) R.string.onboarding_get_started else R.string.onboarding_next),
      onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
      modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
    )
  }
}
