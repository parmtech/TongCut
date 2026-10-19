# Screenshot-based UI implementation reference (19 Oct 2026)

Screenshots supplied by project owner from their CapCut Android application, treated as **visual/layout and behavior references**, not distributable software assets. No original images, CapCut branding, project thumbnails, templates, fonts or proprietary icons from the screenshots are committed to TongCut.

## Reference 1 — Home / Edit tab
- Bright turquoise/blue promotional header gradient fading to white surface.
- Top subscription/banner/search; AI Lab marketing visual; two prominent rounded action cards **New video** and **Edit photo**.
- Horizontally scrollable recent-project thumbnails.
- 3-column feature launcher grid (AutoCut / AI clipper / Generate media / Marketing tools / Retouch / AI generator / Photo tools / Product photos / Auto enhance), with appropriate implemented vs pending distinctions.
- Fixed light bottom navigation **Edit, Templates, AI Lab, Library, Me**.
- Distinct typography, crisp icons, 48dp targets; no stroked fonts.

## Reference 2 — Media picker
- Dark fullscreen, top close / **Albums** selector / Generated / Spaces / Library.
- Videos and Photos tabs with cyan active indicator.
- Tight 3-column thumbnail grid with duration and selection circles.
- Bottom persistent selected-count and **Add** button; optional HD toggle; Add media / Script to video navigation.
- Android 13+ runtime media permission and scoped gallery content URIs (no private-media data collection); OpenDocuments fallback.

## Reference 3 — Editor
- Dark fullscreen UI. Header: close, find/search, AI UHD menu, cyan **Export**.
- Large immersive centered preview with black margin matching footage aspect.
- Transport controls row: fullscreen, play, preview toggle, undo/redo.
- Timeline area: time ruler with frame markers; quick actions Mute, AI clipper, Cover; clip thumbnails on video row; horizontally aligned Add audio and Add text track rows; vertical white playhead indicator.
- Fixed bottom horizontal tool rail **Edit / Audio / Text / Effects / Overlay / Captions / Filters / Adjust...** with icons above labels and contextual controls. This is a *multi-track visual architecture*, not a scrollable list of form fields.
- All controls must perform real operations before enabled; unavailable functions visibly indicate In development.
- Match spacing, hierarchy, flow and touch behavior while using TongCut's original branding, assets and colour accent.

## Gate
Owner explicitly requests **no new APK builds or APK distribution until all phases are completed**. Keep CI compiling/unit-testing source only; final packaging requires full functional checklist, head-to-head usability tests, privacy/security checks, UI snapshot tests and device rendering proof. No fake claim of 100% parity.
