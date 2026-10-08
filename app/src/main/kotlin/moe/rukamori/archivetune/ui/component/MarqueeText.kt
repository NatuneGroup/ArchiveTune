/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */



package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.ui.utils.fadingEdge

/**
 * One line of text that scrolls sideways when it does not fit, with a soft fade at both edges while
 * it is scrolling.
 */
@Composable
fun MarqueeText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    fadeWidth: Dp = 24.dp,
    animationMode: MarqueeAnimationMode = MarqueeAnimationMode.Immediately,
) {
    var contentWidth by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val overflows = viewportWidth > 0 && contentWidth > viewportWidth

    Box(
        modifier =
            (if (overflows) modifier.fadingEdge(horizontal = fadeWidth) else modifier)
                .clipToBounds()
                .onSizeChanged { viewportWidth = it.width },
    ) {
        Text(
            text = text,
            inlineContent = inlineContent,
            style = style,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = 1,
            // Reached only in the frame before the marquee has measured; after that the marquee
            // owns the overflow.
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { contentWidth = it.size.width },
            modifier =
                Modifier.fillMaxWidth().basicMarquee(
                    iterations = Int.MAX_VALUE,
                    animationMode = animationMode,
                ),
        )
    }
}

/** [MarqueeText] for plain text, which is most of them. */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    fadeWidth: Dp = 24.dp,
    animationMode: MarqueeAnimationMode = MarqueeAnimationMode.Immediately,
) {
    MarqueeText(
        text = remember(text) { AnnotatedString(text) },
        modifier = modifier,
        style = style,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        fadeWidth = fadeWidth,
        animationMode = animationMode,
    )
}
