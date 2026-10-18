package com.naivety.zhicolor.bridge.render;

import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * One timed lyric line, optionally carrying per-word timing via
 * {@link WordRange}. Owns the cache fields consumed by
 * {@link OfficialLyricTextRenderer} (renderer layout cache, main-line window
 * cache, row visual scale state, slot height cache) and exposes the lookup /
 * progress helpers used by {@link WordLyricModel}. Promoted from
 * {@code LockscreenLyricsModule} in step 3.1.
 */
public final class WordLine {
    public boolean sentenceWindowExpanded;

    public int visibleWrappedLineLimit() {
        return sentenceWindowExpanded ? 256 : 2;
    }


    public final long timeMillis;
    public final long endTimeMillis;
    public final String text;
    public final String normalizedText;
    public final String textMatchKey;
    public final ArrayList<WordRange> words;
    public final LyricTimingMode timingMode;

    public String displayText = "";
    public String translation = "";
    /** Optional translation word clock supplied by this exact lyric lane. */
    public WordLine translationLine;
    /** False for line ends inferred from the next LRC row or a display fallback. */
    public boolean explicitEndTimeProvided;
    /** Background/second vocal with its own word clock; never a translation alias. */
    public WordLine secondaryLine;
    /** Source layout: 0=start, 1=center, 2=end. Unknown/plain LRC stays centered. */
    public int sourceAlignment = 1;
    public boolean sourceAlignmentProvided;
    private String normalizedDisplaySource = "";
    private String normalizedDisplayText = "";
    private String displayMatchKey = "";
    private String normalizedTranslationSource = "";
    private String normalizedTranslationText = "";

    public int rendererLayoutWidthKey = -1;
    public int rendererLayoutTextSizeKey = -1;
    public Typeface rendererLayoutTypeface;
    public boolean rendererLayoutSingleLine;
    public boolean rendererLayoutBalanceUntranslatedText;
    public int rendererLayoutCount;
    public int[] rendererLayoutStarts = new int[4];
    public int[] rendererLayoutEnds = new int[4];
    public float[] rendererLayoutWidths = new float[4];

    public int mainLineWindowLayoutKey = -1;
    public int mainLineWindowStart;
    public int mainLineWindowPreviousStart;
    public long mainLineWindowChangedAtMs;
    /** Last AOD visual role; bright progress may include an additional overlapping vocal. */
    public boolean aodVisualFocused;

    public int focusedVisualActiveIndex = Integer.MIN_VALUE;
    public long focusedVisualStartElapsedMs;
    public boolean rowVisualScaleInitialized;
    public int rowVisualScaleActiveIndex = Integer.MIN_VALUE;
    public final ZhiToolLyricMotion.Value visualFade = new ZhiToolLyricMotion.Value();
    public final ZhiToolLyricMotion.Value visualFocus = new ZhiToolLyricMotion.Value();
    public final LyricLineExitPolicy highlightExit = new LyricLineExitPolicy();
    public final ZhiToolLyricMotion.Value visualSecondary = new ZhiToolLyricMotion.Value();
    public boolean secondaryFocus;
    public long secondaryChangedAtMs;
    public final ZhiToolLyricMotion.Value secondaryLayout = new ZhiToolLyricMotion.Value();
    public boolean secondaryLayoutInitialized;
    public int secondaryLayoutFocusIndex = Integer.MIN_VALUE;
    public final ZhiToolLyricMotion.Value vocalLayout = new ZhiToolLyricMotion.Value();
    public final ZhiToolLyricMotion.Value vocalAppearance = new ZhiToolLyricMotion.Value();
    public boolean vocalVisible;
    public long vocalChangedAtMs;

    public int slotHeightWidthKey = -1;
    public int slotHeightBaseTextSizeKey = -1;
    public int slotHeightTypographyKey = -1;
    public int slotHeightTranslationHash;
    public int slotHeightTranslationLength = -1;
    public int slotHeightTranslationAmountKey = -1;
    public boolean slotHeightScrollScaleEnabled;
    public boolean slotHeightForceOfficial;
    public int slotHeightCollapsedValue = -1;
    public int slotHeightExpandedValue = -1;
    public int slotHeightValue = -1;

    public WordLine(long timeMillis, String text, ArrayList<WordRange> words) {
        this(
                timeMillis,
                text,
                words,
                WordLyricRenderSupport.inferWordLineEndMillis(timeMillis, words),
                words != null && words.size() > 1
                        ? LyricTimingMode.WORD_TIMED
                        : LyricTimingMode.LINE_TIMED);
    }

    public WordLine(long timeMillis, String text, ArrayList<WordRange> words, long endTimeMillis) {
        this(
                timeMillis,
                text,
                words,
                endTimeMillis,
                words != null && words.size() > 1
                        ? LyricTimingMode.WORD_TIMED
                        : LyricTimingMode.LINE_TIMED);
    }

    public WordLine(
            long timeMillis,
            String text,
            ArrayList<WordRange> words,
            long endTimeMillis,
            LyricTimingMode timingMode) {
        this.timeMillis = timeMillis;
        this.endTimeMillis = Math.max(timeMillis, endTimeMillis);
        this.text = text;
        this.normalizedText = WordLyricRenderSupport.normalizeLine(text);
        this.textMatchKey = WordLyricRenderSupport.lyricMatchKeyFromNormalized(normalizedText);
        this.words = words;
        this.timingMode = timingMode == null
                ? LyricTimingMode.LINE_TIMED
                : timingMode;
    }

    public void ensureRendererLayoutCapacity(int requiredCapacity) {
        if (requiredCapacity <= rendererLayoutStarts.length) {
            return;
        }
        int capacity = rendererLayoutStarts.length;
        while (capacity < requiredCapacity) {
            capacity = Math.min(256, capacity * 2);
            if (capacity >= requiredCapacity || capacity == 256) {
                break;
            }
        }
        rendererLayoutStarts = Arrays.copyOf(rendererLayoutStarts, capacity);
        rendererLayoutEnds = Arrays.copyOf(rendererLayoutEnds, capacity);
        rendererLayoutWidths = Arrays.copyOf(rendererLayoutWidths, capacity);
    }

    public String normalizedDisplayText() {
        String source = WordLyricRenderSupport.nullToEmpty(displayText);
        if (!source.equals(normalizedDisplaySource)) {
            normalizedDisplaySource = source;
            normalizedDisplayText = WordLyricRenderSupport.normalizeLine(source);
            displayMatchKey = WordLyricRenderSupport.lyricMatchKeyFromNormalized(normalizedDisplayText);
        }
        return normalizedDisplayText;
    }

    public String displayMatchKey() {
        normalizedDisplayText();
        return displayMatchKey;
    }

    public String normalizedTranslation() {
        String source = WordLyricRenderSupport.nullToEmpty(translation);
        if (!source.equals(normalizedTranslationSource)) {
            normalizedTranslationSource = source;
            normalizedTranslationText = WordLyricRenderSupport.normalizeLine(source);
        }
        return normalizedTranslationText;
    }

    public WordRange findWord(long position) {
        int index = findWordIndex(position);
        return index >= 0 ? words.get(index) : null;
    }

    public long firstProgressStartMillis() {
        if (timingMode == LyricTimingMode.WORD_TIMED
                && words != null
                && !words.isEmpty()) {
            WordRange firstWord = words.get(0);
            if (firstWord != null && firstWord.timeMillis >= 0L) {
                return firstWord.timeMillis;
            }
        }
        return timeMillis;
    }

    public int findWordIndex(long position) {
        if (words == null || words.isEmpty()) {
            return -1;
        }
        int fallback = -1;
        for (int i = 0; i < words.size(); i++) {
            WordRange word = words.get(i);
            if (word.timeMillis <= position) {
                fallback = i;
            } else {
                break;
            }
        }
        return fallback;
    }

    public long delayToNextWordMillis(long position) {
        for (WordRange word : words) {
            if (word.timeMillis > position) {
                return Math.max(40L, word.timeMillis - position + 16L);
            }
        }
        return 220L;
    }

    public long wordEndMillis(int index) {
        if (index < 0 || index >= words.size()) {
            return timeMillis + 600L;
        }
        long begin = words.get(index).timeMillis;
        if (words.get(index).endTimeMillis > begin) {
            return words.get(index).endTimeMillis;
        }
        if (index + 1 < words.size()) {
            return Math.max(begin + WordLyricRenderSupport.MIN_WORD_REVEAL_MS, words.get(index + 1).timeMillis);
        }
        return WordLyricRenderSupport.lastWordRevealEndMillis(begin, endTimeMillis, words);
    }

    public float wordProgress(int index, long position) {
        if (index < 0 || index >= words.size()) {
            return 0f;
        }
        long begin = words.get(index).timeMillis;
        long end = wordEndMillis(index);
        if (position <= begin) {
            return 0f;
        }
        if (position >= end) {
            return 1f;
        }
        return (float) (position - begin) / (float) Math.max(1L, end - begin);
    }
}
