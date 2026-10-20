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

/** Small original TongCut looks composed only from the editor's built-in caption controls. */
enum class CaptionLook(
    val title: String,
    private val tone: CaptionTone,
    private val size: Float,
    private val bold: Boolean,
    private val font: CaptionFont,
    private val background: Boolean,
    private val shadow: Boolean
) {
    CLEAN_CUT("Clean Cut", CaptionTone.PEARL, 1f, true, CaptionFont.SANS, false, false),
    PEARL_CLASSIC("Pearl Classic", CaptionTone.PEARL, 1f, true, CaptionFont.SANS, false, true),
    AQUA_GLOW("Aqua Glow", CaptionTone.AQUA, 1.08f, true, CaptionFont.SANS, false, true),
    FILM_CARD("Film Card", CaptionTone.PEARL, 0.86f, false, CaptionFont.SANS, true, false),
    GOLDEN_SERIF("Golden Serif", CaptionTone.SUNLIGHT, 1.18f, true, CaptionFont.SERIF, false, true),
    LILAC_NOTE("Lilac Note", CaptionTone.LILAC, 1f, false, CaptionFont.SERIF, true, false);

    fun applyTo(style: CaptionStyle): CaptionStyle = style.copy(
        tone = tone,
        size = size,
        bold = bold,
        font = font,
        background = background,
        shadow = shadow
    )

    fun matches(style: CaptionStyle): Boolean = style.tone == tone && style.size == size &&
        style.bold == bold && style.font == font && style.background == background &&
        style.shadow == shadow
}

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
    val entrance: CaptionEntrance = CaptionEntrance.NONE,
    val shadow: Boolean = false
) {
    init {
        require(size.isFinite() && size in 0.5f..3f) {
            "Caption size must be 50–300%"
        }
    }
}
