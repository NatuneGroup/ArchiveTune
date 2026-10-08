/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Opening animation shape-to-slot geometry — ported from YumaPlayer (github.com/MuwMx/YumaPlayer),
 * ui/component/splash/SplashSlots.kt (GPL-3.0).
 */



package moe.rukamori.archivetune.ui.component.splash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import kotlin.math.min

object SplashSlots {
    const val RD = 0.5483f
    const val CROSS_T = 0.075f
    const val SQRT1_2 = 0.70710677f
    val SLOT_COUNT: Int get() = SplashConfig.getSlotCount(SHAPE_LOGO)
    const val SHAPE_BOLT = "bolt"
    const val SHAPE_CROSS = "cross"
    const val SHAPE_LOGO = "logo"
    const val SHAPE_YUMA = "yuma"

    var customVectorPath: android.graphics.Path? = null
    var vectorVersion: Int by mutableStateOf(0)

    // ArchiveTune's own icon outline (res/drawable/about_splash.xml). SplashVectorLoader
    // supplies the live path a frame later; this is what the engine falls back to on the very
    // first build, and it must stay our own mark rather than the upstream project's.
    const val LOGO_PATH =
        "M 136.5,42.5 C 139.572,42.1826 142.572,42.5159 145.5,43.5C 147.696,46.5596 149.363,49.893 " +
            "150.5,53.5C 141.188,70.7896 131.355,87.7896 121,104.5C 115.248,101.04 109.415,97.7069 103.5,94.5C " +
            "108.706,84.5868 114.04,74.7535 119.5,65C 93.5133,65.9659 67.5133,67.1326 41.5,68.5C 41.5,61.1667 " +
            "41.5,53.8333 41.5,46.5C 73.3416,45.5964 105.008,44.2631 136.5,42.5 Z M 77.5,75.5 C 84.8406,76.1159 " +
            "92.174,76.7826 99.5,77.5C 97.8365,97.8139 94.3365,117.814 89,137.5C 87.8225,142.225 85.9892,146.559 " +
            "83.5,150.5C 76.3087,148.954 69.3087,146.954 62.5,144.5C 69.9692,122.011 74.9692,99.011 77.5,75.5 Z"

    data class ShapeSlots(
        val slots: List<Offset>,
        val loops: List<IntRange>,
        val tips: List<Offset>,
        val outlinePath: android.graphics.Path
    )

    fun center(width: Float, height: Float): Offset =
        Offset(width / 2f, height / 2f)

    fun ldBox(width: Float, height: Float): Float =
        min(height * 0.35f, width * 0.52f)

    fun boxSize(width: Float, height: Float): Float =
        min(height * 0.35f, (width * 0.62f) / RD)

    fun boxSize(shape: String, width: Float, height: Float, density: Float = 1f): Float =
        when (shape) {
            SHAPE_CROSS -> ldBox(width, height)
            SHAPE_LOGO, SHAPE_YUMA ->
                minOf(SplashConfig.Effects.LOGO_TARGET_SIZE_DP * density, minOf(width, height) * 0.42f)
            else -> boxSize(width, height)
        }

    fun boxFrame(shape: String, width: Float, height: Float, density: Float = 1f): Pair<Offset, Float> =
        Pair(center(width, height), boxSize(shape, width, height, density))

    fun pbBolt(cx: Float, cy: Float, size: Float): List<Offset> {
        val w = size * RD
        val left = cx - w / 2f
        val top = cy - size / 2f

        val raw = listOf(
            Offset(0.72f, 0.00f),
            Offset(0.24f, 0.52f),
            Offset(0.54f, 0.52f),
            Offset(0.28f, 1.00f),
            Offset(0.76f, 0.48f),
            Offset(0.46f, 0.48f)
        )
        return raw.map { Offset(left + it.x * w, top + it.y * size) }
    }

    fun ldCross(cx: Float, cy: Float, size: Float): List<Offset> {
        val t = CROSS_T
        val raw = listOf(
            Offset(0.5f - t, 0f), Offset(0.5f + t, 0f), Offset(0.5f + t, 0.5f - t),
            Offset(1f, 0.5f - t), Offset(1f, 0.5f + t), Offset(0.5f + t, 0.5f + t),
            Offset(0.5f + t, 1f), Offset(0.5f - t, 1f), Offset(0.5f - t, 0.5f + t),
            Offset(0f, 0.5f + t), Offset(0f, 0.5f - t), Offset(0.5f - t, 0.5f - t)
        )
        val cos45 = SQRT1_2
        return raw.map { (rx, ry) ->
            val px = rx - 0.5f
            val py = ry - 0.5f
            Offset(
                cx + ((px - py) * cos45 * cos45) * size,
                cy + ((px + py) * cos45 * cos45) * size
            )
        }
    }

    fun fromSvgPath(
        pathData: String,
        count: Int = SLOT_COUNT,
        cx: Float,
        cy: Float,
        targetSize: Float
    ): List<Offset> {
        if (pathData.isEmpty() || count <= 0 || targetSize <= 0f) return emptyList()
        return try {
            val androidPath = androidx.core.graphics.PathParser.createPathFromPathData(pathData)
            val bounds = android.graphics.RectF()
            androidPath.computeBounds(bounds, true)
            if (bounds.width() <= 0f || bounds.height() <= 0f) return emptyList()
            val scale = targetSize / maxOf(bounds.width(), bounds.height())
            val matrix = android.graphics.Matrix().apply {
                postTranslate(-bounds.centerX(), -bounds.centerY())
                postScale(scale, scale)
                postTranslate(cx, cy)
            }
            androidPath.transform(matrix)
            val measure = android.graphics.PathMeasure(androidPath, false)
            val length = measure.length
            if (length <= 0f) return emptyList()
            val step = length / count
            val pos = FloatArray(2)
            List(count) { i ->
                measure.getPosTan(i * step, pos, null)
                Offset(pos[0], pos[1])
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun tips(shape: String, width: Float, height: Float, density: Float = 1f): List<Offset> {
        val (c, size) = boxFrame(shape, width, height, density)
        return if (shape == SHAPE_CROSS) {
            val offset = size * 0.45f
            listOf(
                Offset(c.x, c.y - offset),
                Offset(c.x + offset, c.y),
                Offset(c.x, c.y + offset),
                Offset(c.x - offset, c.y)
            )
        } else {
            val w = size * RD
            listOf(
                Offset(c.x + w * 0.22f, c.y - size * 0.5f),
                Offset(c.x - w * 0.22f, c.y + size * 0.5f)
            )
        }
    }

    fun contour(shape: String, cx: Float, cy: Float, size: Float): List<Offset> =
        if (shape == SHAPE_CROSS) ldCross(cx, cy, size) else pbBolt(cx, cy, size)

    fun ndResample(pts: List<Offset>, count: Int = SLOT_COUNT): List<Offset> {
        if (pts.isEmpty() || count <= 0) return emptyList()
        val n = pts.size
        val segLengths = FloatArray(n) { i ->
            val p1 = pts[i]
            val p2 = pts[(i + 1) % n]
            hypot(p2.x - p1.x, p2.y - p1.y)
        }
        val perimeter = segLengths.sum()
        if (perimeter <= 0f) return List(count) { pts.first() }

        val step = perimeter / count
        val res = ArrayList<Offset>(count)
        var segIdx = 0
        var segStartDist = 0f

        for (i in 0 until count) {
            val targetDist = i * step
            while (segIdx < n - 1 && targetDist >= segStartDist + segLengths[segIdx]) {
                segStartDist += segLengths[segIdx]
                segIdx++
            }
            val segLen = segLengths[segIdx]
            val t = if (segLen > 0f) ((targetDist - segStartDist) / segLen).coerceIn(0f, 1f) else 0f
            val p1 = pts[segIdx]
            val p2 = pts[(segIdx + 1) % n]
            res.add(
                Offset(
                    x = p1.x + (p2.x - p1.x) * t,
                    y = p1.y + (p2.y - p1.y) * t
                )
            )
        }
        return res
    }

    fun parseContourPath(path: android.graphics.Path, totalSlots: Int = SLOT_COUNT): ShapeSlots {
        val contourLengths = ArrayList<Float>()
        val measure = android.graphics.PathMeasure(path, false)
        do {
            val len = measure.length
            if (len > 0f) {
                contourLengths.add(len)
            }
        } while (measure.nextContour())

        if (contourLengths.isEmpty() || totalSlots <= 0) {
            return ShapeSlots(emptyList(), listOf(0 until totalSlots), emptyList(), path)
        }

        val totalLength = contourLengths.sum()
        if (totalLength <= 0f) {
            return ShapeSlots(emptyList(), listOf(0 until totalSlots), emptyList(), path)
        }

        val counts = contourLengths.map { len ->
            ((len / totalLength * totalSlots).toInt()).coerceAtLeast(3)
        }.toMutableList()

        val longestIndex = contourLengths.indices.maxByOrNull { contourLengths[it] } ?: 0
        val diff = totalSlots - counts.sum()
        counts[longestIndex] += diff

        val samplingMeasure = android.graphics.PathMeasure(path, false)
        val slots = ArrayList<Offset>(totalSlots)
        val loops = ArrayList<IntRange>(contourLengths.size)
        var contourIdx = 0
        val pos = FloatArray(2)

        do {
            val len = samplingMeasure.length
            if (len <= 0f) continue
            if (contourIdx >= counts.size) break
            val count = counts[contourIdx]
            val startIndex = slots.size
            if (count > 0) {
                val step = len / count
                for (i in 0 until count) {
                    samplingMeasure.getPosTan(i * step, pos, null)
                    slots.add(Offset(pos[0], pos[1]))
                }
            }
            loops.add(startIndex until slots.size)
            contourIdx++
        } while (samplingMeasure.nextContour())

        val centroid = if (slots.isNotEmpty()) {
            var sumX = 0f
            var sumY = 0f
            for (s in slots) {
                sumX += s.x
                sumY += s.y
            }
            Offset(sumX / slots.size, sumY / slots.size)
        } else {
            Offset.Zero
        }

        val tips = slots.sortedByDescending {
            (it.x - centroid.x) * (it.x - centroid.x) + (it.y - centroid.y) * (it.y - centroid.y)
        }.take(3)

        return ShapeSlots(
            slots = slots,
            loops = loops,
            tips = tips,
            outlinePath = path
        )
    }

    fun build(shape: String, width: Float, height: Float, density: Float = 1f): ShapeSlots {
        val totalSlots = SplashConfig.getSlotCount(shape)
        if (width <= 0f || height <= 0f) {
            return ShapeSlots(
                emptyList(),
                listOf(0 until totalSlots),
                emptyList(),
                android.graphics.Path(),
            )
        }
        val (c, size) = boxFrame(shape, width, height, density)
        if (shape == SHAPE_LOGO || shape == SHAPE_YUMA) {
            val sourcePath = customVectorPath ?: try {
                androidx.core.graphics.PathParser.createPathFromPathData(LOGO_PATH)
            } catch (_: Exception) {
                null
            }
            if (sourcePath != null) {
                val p = android.graphics.Path(sourcePath)
                val bounds = android.graphics.RectF()
                p.computeBounds(bounds, true)
                if (bounds.width() > 0f && bounds.height() > 0f) {
                    val scale = size / maxOf(bounds.width(), bounds.height())
                    val matrix = android.graphics.Matrix().apply {
                        postTranslate(-bounds.centerX(), -bounds.centerY())
                        postScale(scale, scale)
                        postTranslate(c.x, c.y)
                    }
                    p.transform(matrix)
                    return parseContourPath(p, totalSlots)
                }
            }
        }
        val pts = ndResample(contour(shape, c.x, c.y, size), totalSlots)
        val raw = contour(shape, c.x, c.y, size)
        val outline = android.graphics.Path().apply {
            if (raw.isNotEmpty()) {
                moveTo(raw[0].x, raw[0].y)
                for (k in 1 until raw.size) lineTo(raw[k].x, raw[k].y)
                close()
            }
        }
        return ShapeSlots(pts, listOf(0 until totalSlots), tips(shape, width, height, density), outline)
    }

    private data class PairDist(val member: Int, val slot: Int, val d: Float)

    fun zdGreedyCompile(members: List<Offset>, slots: List<Offset>): List<Int> {
        if (members.isEmpty() || slots.isEmpty()) return List(members.size) { -1 }

        val pairs = ArrayList<PairDist>(members.size * slots.size)
        for (i in members.indices) {
            val m = members[i]
            for (j in slots.indices) {
                val s = slots[j]
                val d = hypot(m.x - s.x, m.y - s.y)
                pairs.add(PairDist(i, j, d))
            }
        }
        pairs.sortBy { it.d }

        val usedSlots = BooleanArray(slots.size)
        val assignedMembers = BooleanArray(members.size)
        val assign = IntArray(members.size) { -1 }
        var assignedCount = 0
        val targetCount = min(members.size, slots.size)

        for (p in pairs) {
            if (!assignedMembers[p.member] && !usedSlots[p.slot]) {
                assignedMembers[p.member] = true
                usedSlots[p.slot] = true
                assign[p.member] = p.slot
                assignedCount++
                if (assignedCount == targetCount) break
            }
        }
        return assign.toList()
    }
}
