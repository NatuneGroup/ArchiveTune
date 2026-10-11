

package tf.monochrome.android.performance

data class PerformanceProfile(
    val spectrumFps: Int = 60,
) {
    companion object {
        val HIGH = PerformanceProfile(spectrumFps = 60)
    }
}
