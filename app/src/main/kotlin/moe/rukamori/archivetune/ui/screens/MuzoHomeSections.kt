/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.R

private val MuzoGutter = 20.dp

@Composable
fun HomeAtmosphereBackground(
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    if (dark && !subtle) {
        return
    }
    val base = MaterialTheme.colorScheme.surface

    val glow = if (subtle && dark) 0.20f else 0.12f
    val topWashAlpha = when {
        subtle && dark -> 0.13f
        subtle -> 0.16f
        else -> 0.5f
    }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(base)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val violet = Color(0xFF7B4DFF)
                    val teal = Color(0xFF00B8A9)
                    val blue = Color(0xFF2E6BFF)
                    val topWash =
                        Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = topWashAlpha), Color.Transparent),
                            startY = 0f,
                            endY = if (dark) h * 0.22f else h * 0.16f,
                        )
                    val violetBrush =
                        Brush.radialGradient(
                            colors = listOf(violet.copy(alpha = glow), Color.Transparent),
                            center = Offset(w * 0.10f, h * 0.12f),
                            radius = if (subtle && dark) w * 0.90f else w * 0.62f,
                        )
                    val tealBrush =
                        Brush.radialGradient(
                            colors = listOf(teal.copy(alpha = glow * 0.8f), Color.Transparent),
                            center = Offset(w * 0.98f, h * 0.32f),
                            radius = if (subtle && dark) w * 0.80f else w * 0.55f,
                        )
                    val blueBrush =
                        Brush.radialGradient(
                            colors = listOf(blue.copy(alpha = glow * 0.85f), Color.Transparent),
                            center = Offset(w * 0.15f, h * 0.90f),
                            radius = if (subtle && dark) w * 0.90f else w * 0.70f,
                        )
                    val bottomShade =
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(
                                    alpha = when {
                                        subtle && dark -> 0.28f
                                        dark -> 0.30f
                                        else -> 0.05f
                                    },
                                ),
                            ),
                            startY = h * 0.50f,
                            endY = h,
                        )
                    onDrawBehind {
                        drawRect(violetBrush)
                        drawRect(tealBrush)
                        drawRect(blueBrush)
                        drawRect(bottomShade)
                        drawRect(topWash)
                    }
                },
    )
}

@Composable
fun HomeWelcomeHeader(
    accountName: String,
    modifier: Modifier = Modifier,
) {
    val small = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val headline = MaterialTheme.colorScheme.onSurface
    val displayName = accountName.trim()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = MuzoGutter)
                .padding(top = 10.dp, bottom = 16.dp),
    ) {
        Text(
            text =
                if (displayName.isNotBlank()) {
                    stringResource(R.string.home_welcome_line, displayName)
                } else {
                    stringResource(R.string.home_welcome_line_anonymous)
                },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = small,
        )
        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.home_headline_line1),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
        Text(
            text = stringResource(R.string.home_headline_line2),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
            color = headline,
        )
    }
}
