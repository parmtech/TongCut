package au.parmtech.tongcut

/**
 * TongCut-original caption styling, independent of any vendor template pack.
 * The same values are used by the Compose monitor and exported Media3 text.
 */
enum class CaptionTone(val title: String, val argb: Int) {
    PEARL("Pearl", 0xFFFFFFFF.toInt()),
    AQUA("Aqua", 0xFF83E5E3.toInt()),
    SUNLIGHT("Sunlight", 0xFFFFD780.toInt()),
    CORAL("Coral", 0xFFFF9698.toInt()),
    LILAC("Lilac", 0xFFCDB7FF.toInt())
}

data class CaptionStyle(
    val tone: CaptionTone = CaptionTone.PEARL,
    val size: Float = 1f,
    val bold: Boolean = true
) {
    init {
        require(size.isFinite() && size in 0.5f..3f) {
            "Caption size must be 50–300%"
        }
    }
}
