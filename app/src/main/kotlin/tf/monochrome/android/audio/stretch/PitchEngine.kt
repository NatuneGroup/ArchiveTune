package tf.monochrome.android.audio.stretch

enum class PitchEngine(val nativeId: Int, val label: String, val summary: String) {
    VOCODER(0, "Smooth", "Best on sustained, melodic material. Softens attacks."),

    WSOLA(1, "Punchy", "Keeps drum attacks intact. Can sound phasey on pads."),
    ;

    companion object {
        fun fromName(name: String?): PitchEngine =
            entries.firstOrNull { it.name == name } ?: VOCODER
    }
}

enum class PitchQuality(
    val nativeId: Int,
    val label: String,

    val bassFloorHz: Int,

    val vocoderErrorHz: String,
) {
    FAST(0, "Fast", 375, "1.40 Hz"),
    BALANCED(1, "Balanced", 188, "0.42 Hz"),
    HIGH(2, "High", 94, "0.18 Hz"),
    ;

    companion object {
        fun fromName(name: String?): PitchQuality =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
