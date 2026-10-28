// Ported from OpenStrap analytics/lib/src/onehz/device.dart — device-family calibration.
package app.circlet.core

enum class StrapGeneration(val model: String, val maxHeartRate: Int) {
    GEN4("WHOOP 4.0", 230),
    GEN5("WHOOP 5.0 / MG", 210),
}

enum class SkinTemperatureUnit { ADC_COUNTS, CENTI_CELSIUS }
