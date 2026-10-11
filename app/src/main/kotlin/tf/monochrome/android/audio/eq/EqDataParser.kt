package tf.monochrome.android.audio.eq

import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import java.util.Locale

object EqDataParser {
    fun parseRawData(rawData: String): List<FrequencyPoint> {
        if (rawData.isEmpty()) return emptyList()

        val lines = rawData.trim().split('\n')
        if (lines.isEmpty()) return emptyList()

        val firstLine = lines[0].trim()

        val delimiter = when {
            firstLine.contains(';') -> ";"
            firstLine.contains(',') -> ","
            firstLine.contains('\t') -> "\t"
            else -> "\\s+"
        }

        var freqIdx = 0
        var gainIdx = 1

        val hasHeader = firstLine.contains(Regex("[a-zA-Z]"))
        if (hasHeader) {
            val headerPattern = if (delimiter == "\\s+") Regex("\\s+") else Regex(Regex.escape(delimiter))
            val headers = firstLine.split(headerPattern).map { h ->
                h.trim().lowercase().replace(Regex("['\"]"), "")
            }

            val fIdx = headers.indexOfFirst { it.contains("freq") || it == "f" }
            if (fIdx >= 0) freqIdx = fIdx

            val rIdx = headers.indexOfFirst { it == "raw" }
            if (rIdx >= 0) {
                gainIdx = rIdx
            } else {
                val splIdx = headers.indexOfFirst {
                    it.contains("spl") || it.contains("gain") || it.contains("db") || it.contains("mag")
                }
                if (splIdx >= 0 && splIdx != freqIdx) gainIdx = splIdx
            }
        }

        val points = mutableListOf<FrequencyPoint>()
        val dataLines = if (hasHeader) lines.drop(1) else lines

        for (line in dataLines) {
            val cleanLine = line.trim()
            if (cleanLine.isEmpty() || !cleanLine[0].isDigit() && cleanLine[0] != '-' && cleanLine[0] != '.') {
                continue
            }

            val parts = if (delimiter == "\\s+") {
                cleanLine.split(Regex("\\s+"))
            } else {
                cleanLine.split(delimiter)
            }

            if (parts.size <= maxOf(freqIdx, gainIdx)) continue

            var freqStr = parts[freqIdx].trim()
            var gainStr = parts[gainIdx].trim()

            if (delimiter != ",") {
                if (freqStr.contains(',')) freqStr = freqStr.replace(',', '.')
                if (gainStr.contains(',')) gainStr = gainStr.replace(',', '.')
            }

            val freq = freqStr.toFloatOrNull()
            val gain = gainStr.toFloatOrNull()

            if (freq != null && gain != null) {
                points.add(FrequencyPoint(freq, gain))
            }
        }

        return points.sortedBy { it.freq }
    }

    fun parseParametricEQ(text: String): Pair<List<EqBand>, Float> {
        val lines = text.split('\n')
        var preamp = 0f
        val bands = mutableListOf<EqBand>()
        var idCounter = 0

        for (line in lines) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith('#')) continue

            if (l.lowercase().startsWith("preamp:")) {
                val match = Regex("""Preamp:\s*([-\d.]+)\s*dB""", RegexOption.IGNORE_CASE).find(l)
                if (match != null) {
                    preamp = match.groupValues[1].toFloatOrNull() ?: 0f
                }
                continue
            }

            if (l.lowercase().startsWith("filter")) {
                val pattern = Regex(
                    """Filter\s+\d+:\s+(ON|OFF)\s+(PK|LS|HS)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+([-\d.]+)\s+dB\s+Q\s+([\d.]+)""",
                    RegexOption.IGNORE_CASE
                )
                val match = pattern.find(l)
                if (match != null) {
                    val enabled = match.groupValues[1].uppercase() == "ON"
                    val typeCode = match.groupValues[2].uppercase()
                    val freq = match.groupValues[3].toFloatOrNull() ?: continue
                    val gain = match.groupValues[4].toFloatOrNull() ?: 0f
                    val q = match.groupValues[5].toFloatOrNull() ?: 1f

                    val type = when (typeCode) {
                        "LS" -> FilterType.LOWSHELF
                        "HS" -> FilterType.HIGHSHELF
                        else -> FilterType.PEAKING
                    }

                    bands.add(
                        EqBand(
                            id = idCounter++,
                            type = type,
                            freq = freq,
                            gain = gain,
                            q = q,
                            enabled = enabled
                        )
                    )
                }
            }
        }

        return Pair(bands, preamp)
    }

    fun applySmoothing(data: List<FrequencyPoint>, strength: Int): List<FrequencyPoint> {
        if (data.size < 3 || strength <= 0) return data

        val windowSize = maxOf(1, strength / 3)

        return data.indices.map { i ->
            var sumGain = 0f
            var totalWeight = 0f

            for (j in -windowSize..windowSize) {
                val idx = i + j
                if (idx in data.indices) {
                    val weight = 1.0f - (kotlin.math.abs(j.toFloat()) / (windowSize + 1))
                    sumGain += data[idx].gain * weight
                    totalWeight += weight
                }
            }

            FrequencyPoint(
                freq = data[i].freq,
                gain = sumGain / totalWeight
            )
        }
    }

    fun bandToParametricEQ(bands: List<EqBand>, preamp: Float = 0f): String {
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "Preamp: %.1f dB\n", preamp))

        bands.forEachIndexed { idx, band ->
            val status = if (band.enabled) "ON" else "OFF"
            val typeCode = when (band.type) {
                FilterType.LOWSHELF -> "LS"
                FilterType.HIGHSHELF -> "HS"
                else -> "PK"
            }
            sb.append(
                String.format(
                    Locale.US,
                    "Filter %d: %s %s Fc %.0f Hz Gain %.1f dB Q %.2f\n",
                    idx + 1, status, typeCode, band.freq, band.gain, band.q
                )
            )
        }

        return sb.toString()
    }
}
