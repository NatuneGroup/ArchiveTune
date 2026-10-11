

package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

enum class FilterType {
    PEAKING, LOWSHELF, HIGHSHELF
}

@Serializable
data class FrequencyPoint(
    val freq: Float,
    val gain: Float
)

@Serializable
data class EqBand(
    val id: Int,
    val type: FilterType = FilterType.PEAKING,
    val freq: Float,
    val gain: Float,
    val q: Float = 1.0f,
    val enabled: Boolean = true
)

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val bands: List<EqBand> = emptyList(),

    val bandsR: List<EqBand>? = null,
    val preamp: Float = 0f,
    val targetId: String = "",
    val targetName: String = "",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    val isCorrupted: Boolean = false
)

@Serializable
data class EqTarget(
    val id: String,
    val label: String,
    val data: List<FrequencyPoint> = emptyList(),
    val filename: String = ""
)

@Serializable
data class Headphone(
    val id: String,
    val name: String,
    val type: String = "over-ear",
    val data: List<FrequencyPoint> = emptyList(),
    val measurements: List<AutoEqMeasurement> = emptyList()
)

data class AutoEqEntry(
    val name: String,
    val type: String,
    val measurements: List<AutoEqMeasurement> = emptyList()
)

@Serializable
data class AutoEqMeasurement(
    val source: String,
    val target: String,
    val path: String,
    val fileName: String,
    val rig: MeasurementRig = MeasurementRig.UNKNOWN,

    val host: String = ""
)

@Serializable
enum class MeasurementRig(val label: String) {
    UPLOADED("Uploaded"),
    BK_5128("B&K 5128"),
    BK_4620("B&K 4620"),
    HMS_II_3("HMS II.3"),
    GRAS_43AG_7("GRAS 43AG-7"),
    GRAS_43AC_10("GRAS 43AC-10"),
    GRAS_45CA_10("GRAS 45CA-10"),
    GRAS_RA0045("GRAS RA0045"),
    IEC_711_CLONE("IEC 711 clone"),
    MINIDSP_EARS("MiniDSP EARS"),
    UNKNOWN("Unknown")
}
