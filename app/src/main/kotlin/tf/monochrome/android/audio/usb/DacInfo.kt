package tf.monochrome.android.audio.usb

import android.hardware.usb.UsbDevice

data class DacInfo(
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceId: Int,

    val usbVersion: String?,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
) {
    val displayName: String
        get() = listOf(manufacturer?.takeIf { it.isNotBlank() },
                       product?.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(" ")
            .ifBlank { "USB DAC $idHex" }

    val idHex: String
        get() = "%04x:%04x".format(vendorId, productId)

    val descriptorLine: String
        get() = buildString {
            append(idHex)
            usbVersion?.takeIf { it.isNotBlank() }?.let {
                append(" · USB ")
                append(it)
            }
            append(" · class ")
            append(deviceClass)
            append("/")
            append(deviceSubClass)
            append("/")
            append(deviceProtocol)
        }

    companion object {
        fun fromDevice(device: UsbDevice): DacInfo? {
            if (device == null) return null
            val serial: String? = try {
                device.serialNumber
            } catch (_: SecurityException) {
                null
            }
            return DacInfo(
                manufacturer = runCatching { device.manufacturerName }.getOrNull(),
                product = runCatching { device.productName }.getOrNull(),
                serialNumber = serial,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceId = device.deviceId,
                usbVersion = runCatching { device.version }.getOrNull(),
                deviceClass = device.deviceClass,
                deviceSubClass = device.deviceSubclass,
                deviceProtocol = device.deviceProtocol,
            )
        }
    }
}
