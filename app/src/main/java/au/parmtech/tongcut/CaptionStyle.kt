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

enum class CaptionFont(val title: String, val androidFamily: String) {
    SANS("Sans", "sans-serif"),
    SERIF("Serif", "serif"),
    MONOSPACE("Mono", "monospace")
}

enum class CaptionAlignment(val title: String) { LEFT("Left"), CENTER("Center"), RIGHT("Right") }
enum class CaptionPlacement(val title: String) { TOP("Top"), CENTER("Center"), BOTTOM("Bottom") }
enum class CaptionEntrance(val title: String) { NONE("None"), FADE("Fade"), POP("Pop"), RISE("Rise") }

internal const val CAPTION_ALIGNMENT_BOX_HALF_WIDTH = 0.88f

data class CaptionStyle(
    val tone: CaptionTone = CaptionTone.PEARL,
    val size: Float = 1f,
    val bold: Boolean = true,
    val font: CaptionFont = CaptionFont.SANS,
    val alignment: CaptionAlignment = CaptionAlignment.CENTER,
    /** Original TongCut translucent charcoal caption backing. */
    val background: Boolean = false,
    val placement: CaptionPlacement = CaptionPlacement.CENTER,
    val entrance: CaptionEntrance = CaptionEntrance.NONE
) {
    init {
        require(size.isFinite() && size in 0.5f..3f) {
            "Caption size must be 50–300%"
        }
    }
}
