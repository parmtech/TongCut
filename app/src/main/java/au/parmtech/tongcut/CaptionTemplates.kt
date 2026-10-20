package au.parmtech.tongcut

/** One original, editable TongCut text element inside a reusable offline title layout. */
internal data class TongCutCaptionTemplateLine(
    val placeholder: String,
    val style: CaptionStyle
) {
    init { require(placeholder.isNotBlank() && placeholder.length <= 300) }
}

/** Original text-only layout; every line becomes an ordinary editable TEXT timeline layer. */
internal data class TongCutCaptionTemplate(
    val id: String,
    val title: String,
    val lines: List<TongCutCaptionTemplateLine>
) {
    init {
        require(id.matches(Regex("[a-z0-9-]{3,48}")))
        require(title.isNotBlank() && title.length <= 40)
        require(lines.isNotEmpty() && lines.size <= 4)
    }
}

internal object TongCutCaptionTemplates {
    private fun style(
        tone: CaptionTone,
        size: Float,
        bold: Boolean,
        font: CaptionFont = CaptionFont.SANS,
        background: Boolean = false,
        shadow: Boolean = true,
        placement: CaptionPlacement = CaptionPlacement.CENTER,
        entrance: CaptionEntrance = CaptionEntrance.NONE
    ) = CaptionStyle(
        tone = tone,
        size = size,
        bold = bold,
        font = font,
        background = background,
        shadow = shadow,
        placement = placement,
        entrance = entrance
    )

    /** These are original built-in layouts: no network lookup or third-party template assets. */
    val all: List<TongCutCaptionTemplate> = listOf(
        TongCutCaptionTemplate(
            "dawn-title", "Dawn Title", listOf(
                TongCutCaptionTemplateLine(
                    "YOUR STORY",
                    style(CaptionTone.SUNLIGHT, 1.3f, true, CaptionFont.SERIF,
                        entrance = CaptionEntrance.RISE)
                ),
                TongCutCaptionTemplateLine(
                    "A MOMENT TO REMEMBER",
                    style(CaptionTone.PEARL, 0.72f, false, placement = CaptionPlacement.BOTTOM)
                )
            )
        ),
        TongCutCaptionTemplate(
            "aqua-punch", "Aqua Punch", listOf(
                TongCutCaptionTemplateLine(
                    "MAKE IT MOVE",
                    style(CaptionTone.AQUA, 1.24f, true, entrance = CaptionEntrance.POP)
                ),
                TongCutCaptionTemplateLine(
                    "YOUR WORDS GO HERE",
                    style(CaptionTone.PEARL, 0.7f, true, placement = CaptionPlacement.BOTTOM)
                )
            )
        ),
        TongCutCaptionTemplate(
            "film-card", "Film Card", listOf(
                TongCutCaptionTemplateLine(
                    "SCENE 01",
                    style(CaptionTone.SUNLIGHT, 1.05f, true, CaptionFont.MONOSPACE,
                        placement = CaptionPlacement.TOP, entrance = CaptionEntrance.FADE)
                ),
                TongCutCaptionTemplateLine(
                    "A SHORT FILM TITLE",
                    style(CaptionTone.PEARL, 1.2f, false, CaptionFont.SERIF,
                        background = true)
                ),
                TongCutCaptionTemplateLine(
                    "WRITTEN BY YOU",
                    style(CaptionTone.PEARL, 0.66f, false, placement = CaptionPlacement.BOTTOM)
                )
            )
        ),
        TongCutCaptionTemplate(
            "lilac-note", "Lilac Note", listOf(
                TongCutCaptionTemplateLine(
                    "A LITTLE MOMENT",
                    style(CaptionTone.LILAC, 1.08f, false, CaptionFont.SERIF,
                        background = true, entrance = CaptionEntrance.FADE)
                ),
                TongCutCaptionTemplateLine(
                    "KEEP THE GOOD PARTS",
                    style(CaptionTone.PEARL, 0.68f, false, placement = CaptionPlacement.BOTTOM)
                )
            )
        ),
        TongCutCaptionTemplate(
            "step-by-step", "Step by Step", listOf(
                TongCutCaptionTemplateLine(
                    "01  /  THE HOOK",
                    style(CaptionTone.SUNLIGHT, 0.88f, true, CaptionFont.MONOSPACE,
                        placement = CaptionPlacement.TOP, entrance = CaptionEntrance.POP)
                ),
                TongCutCaptionTemplateLine(
                    "Explain your first step",
                    style(CaptionTone.PEARL, 1.05f, true,
                        background = true, placement = CaptionPlacement.BOTTOM)
                )
            )
        ),
        TongCutCaptionTemplate(
            "clean-label", "Clean Label", listOf(
                TongCutCaptionTemplateLine(
                    "TYPE YOUR CAPTION",
                    style(CaptionTone.PEARL, 0.82f, true,
                        background = true, shadow = false, placement = CaptionPlacement.BOTTOM)
                )
            )
        )
    )

    /**
     * Adds the whole layout as one draft update. The resulting text clips use the existing
     * caption codec, edit history, preview, SRT and Media3 export paths without schema changes.
     */
    fun insert(
        draft: EditorDraft,
        playheadMs: Long,
        template: TongCutCaptionTemplate
    ): EditorDraft {
        var updated = draft
        template.lines.forEach { line ->
            val existingIds = updated.layers.layers.mapTo(mutableSetOf()) { it.id }
            val withText = LayerEdits.addText(updated, playheadMs, line.placeholder)
            val created = withText.layers.layers.firstOrNull {
                it.kind == LayerKind.TEXT && it.id !in existingIds
            } ?: throw IllegalStateException("Text template did not create an editable layer")
            updated = LayerEdits.changeCaptionStyle(withText, created.id, line.style)
        }
        return updated
    }
}
