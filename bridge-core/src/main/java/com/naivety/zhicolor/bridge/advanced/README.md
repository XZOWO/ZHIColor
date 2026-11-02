# Advanced lockscreen lyrics — fidelity pass 30

## Ownership and data
Only lyric-content rendering changes. SystemUI owns the clock, control-card geometry, outer alpha, matrix, scale, translation and AOD lifecycle. Standard mode is unchanged. No phone playback, touch, wake, unlock, volume or DND command is part of the local test harness.

## AOD and wake restoration
Advanced AOD uses a distinct black, low-luminance text surface. It has no artwork renderer, shader, blur, continuous frame callback, playback controls or wake lock. Real line boundaries drive text updates; the parent retains native visibility and outer transitions. Paused content does not run a periodic text animation. Simultaneous main voices keep their source identity.

On wake, the bright view accepts a fresh media anchor and native geometry before restoring focus. Suspended browsing, reflow, delayed row springs and glyph/child state do not leak into the resumed frame. The first native pre-draw commits the final wake geometry. Native margin restoration is conditional: replacement LayoutParams become the new native baseline, including explicit zeros, rather than receiving an obsolete pre-AOD snapshot.

Real source WordRanges remain authoritative. No per-letter timestamps are synthesized from LRC. Simultaneous independent singing follows Android Apple Music as requested; hierarchy, colors, ordinary syllable lift, gradient, child selection, blur-index and interludes follow the saved iOS 27 reference paths. Evidence is in the delivery's evidence/glyph, evidence/rows and evidence/interlude folders.

## Replaced mechanics
- No Chinese-by-Unicode crossfade. Both recovered ordinary/transliterated Line constructors default to gradient; real syllable boundaries and word grouping explain script differences.
- One linear front per laid-out lyric text, retargeted only on active syllable identity changes. A 30dp feather uses terminal/short-word/short-syllable coefficients 1/.5/.25/.12. A word-final syllable covers the next word's leading blank when on the same visual line. Visual wraps retain line-specific coordinates and selection clipping.
- Source ranges group geometrically by Latin whitespace token without changing source time intervals; CJK authentic ranges stay independent. Grapheme handling preserves combining marks, supplementary codepoints, ZWJ/modifiers and flags.
- Ordinary lift uses m1/k14/c7 to 2dp after syllable start, holds through the word end and pause, and resets on explicit seek/row lifecycle. No duration-generated emphasis or glow is invented.
- A single selected visual headstart +100ms is applied at the content entrance, also inside nonanimated transactions. The media position itself is unchanged.
- Main white slots 1/.35/.175; child .35/.175. Children appear with parent selection, including before their own syllables, hold after their word end, and hide with parent deselection. Show spring m1/k30/c9, hide critical ratio1/period.2, scale .9 to 1, no invented 7dp Y travel.
- Followed row scroll defaults k100/c18 because the traced natural/user callers pass optional absent. Row cascade uses .05s after current/next, reversed order uses half delay. No four-row cap or arbitrary .98 whole-row scale.
- Explicit source left/right side remains when source lacks iOS agent ids. Parent direction and side own child/subtitle geometry. Native control margins own the outer text column; no duplicate screen inset is introduced.
- Intro >7s and genuine vocal gaps >7s get three 12dp dots, 8dp gaps, 52dp width/40dp height. Gap start adds100ms. Recovered stagger/fill/group breath/anticipation/fade/shrink are evaluated from the real interval. Android concurrent voices use interval union to avoid false silence. No automatic song-tail dots.
- Viewport fade stays fixed in viewport coordinates; top .075 and bottom .21 of native reference height. Native outer transitions remain untouched.

## Self-check loop
Initial policy assertions passed but hardware screenshots exposed disappearing raised glyphs in the nested alpha RenderNode path. The final renderer draws cached glyph geometry on the destination hardware Canvas; it does not use that failing nested full-layout alpha path. Android screenshot QA now checks every sung/future character, CJK/Latin/RTL gradient, wrapping, source sides, child lifetime and exact color slots, intro/long/short/no-tail gaps, pause/seek/AOD, native margins, outer transforms and model/layout reuse. A 12-frame hardware screenshot sequence supplements stateless policy tests.

## Remaining evidence gaps
Static reconstruction and Android screenshots are not a side-by-side iPhone pixel/timeline comparison. CoreText/SF font metrics, OEM GPU blur equivalence, all controller-specific Specs overrides and the complete global CA pause hierarchy remain unmeasured. Exact native emphasis requires source factor metadata; the current Lyricon WordRange contract exposes timing/text only, so no emphasis is guessed. The separate SBS measured-width/15-point layout is not selected by current timed-word source content. The interlude sampler freezes with the media clock; native submitted-animation behavior under full hierarchy pause has not been runtime-correlated. These are explicit fidelity limits, not test failures hidden by successful builds.
