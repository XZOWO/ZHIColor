package com.naivety.zhicolor.bridge.render;

import java.text.BreakIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Visual-word emphasis, independent from canonical word timing and ordinary syllable lift. */
public final class WordEmphasisMotion {
    private static final long MAX_OBSERVATION_GAP_MS = 1_000L;
    private static final long POSITION_JUMP_TOLERANCE_MS = 500L;
    private final String text;
    private final int[] characterGlyph;
    private final int[] glyphWords;
    private final int[] wordStarts, wordEnds, firstGlyphs, glyphCounts;
    private final long[] starts;
    private final double[] durations, factors, attackDelays, returnDelays;
    private final boolean[] consumed, chineseTailWords;
    private final SpringChannel glyphMotion, glowMotion;
    private boolean initialized, running, languageKnown, languageAllowed, chineseLanguageAllowed;
    private boolean chineseTailEnabled = true;
    private String previousLanguage;
    private long lastPosition, lastNow;

    public WordEmphasisMotion(String sourceText, List<WordRange> sourceWords) {
        text = sourceText == null ? "" : sourceText;
        ArrayList<VisualWord> visualWords = new ArrayList<>();
        VisualWord previous = null;
        WordRange lastSungRange = null;
        int lastSungIndex = -1;
        if (sourceWords != null) {
            for (int sourceIndex = 0; sourceIndex < sourceWords.size(); sourceIndex++) {
                WordRange range = sourceWords.get(sourceIndex);
                if (range == null || range.timeMillis < 0L || range.start < 0
                        || range.end > text.length() || range.start >= range.end) {
                    previous = null;
                    continue;
                }
                int start = trimStart(text, range.start, range.end);
                int end = trimEnd(text, start, range.end);
                if (start >= end) { previous = null; continue; }
                if (!onlyTailSeparators(text, start, end)) {
                    lastSungRange = range;
                    lastSungIndex = sourceIndex;
                }
                boolean token = !containsWhitespace(text, start, end)
                        && LyricAnimationLanguage.allowsEmphasis("en", text.substring(start, end));
                int visualStart = start, visualEnd = end;
                if (token) {
                    while (visualStart > 0) {
                        int codePoint = text.codePointBefore(visualStart);
                        if (whitespace(codePoint)) break;
                        visualStart -= Character.charCount(codePoint);
                    }
                    while (visualEnd < text.length()) {
                        int codePoint = text.codePointAt(visualEnd);
                        if (whitespace(codePoint)) break;
                        visualEnd += Character.charCount(codePoint);
                    }
                }
                if (token && previous != null && previous.token
                        && previous.lastSourceIndex + 1 == sourceIndex
                        && previous.tokenStart == visualStart && previous.tokenEnd == visualEnd
                        && previous.sourceEnd == start && previous.lastStart <= range.timeMillis) {
                    previous.sourceEnd = end;
                    previous.end = end;
                    previous.lastStart = range.timeMillis;
                    previous.endMillis = range.endTimeMillis;
                    previous.lastSourceIndex = sourceIndex;
                } else {
                    // Whitespace trimming is an Android adapter for source tokens that include
                    // separators. Never extend the timed span into an untimed token suffix.
                    previous = new VisualWord(start, end, range, sourceIndex, token,
                            visualStart, visualEnd);
                    visualWords.add(previous);
                }
            }
        }
        VisualWord chineseTail = chineseTail(text, lastSungRange, lastSungIndex);
        if (chineseTail != null) visualWords.add(chineseTail);
        int count = visualWords.size(), totalGlyphs = 0;
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        for (VisualWord word : visualWords) {
            iterator.setText(new StringCharacterIterator(text, word.start, word.end, word.start));
            int[] boundaries = new int[word.end - word.start + 1];
            int size = 0;
            for (int at = iterator.first(); at != BreakIterator.DONE; at = iterator.next())
                boundaries[size++] = at;
            word.boundaries = Arrays.copyOf(boundaries, size);
            totalGlyphs += Math.max(0, size - 1);
        }
        characterGlyph = new int[text.length()];
        Arrays.fill(characterGlyph, -1);
        glyphWords = new int[totalGlyphs];
        wordStarts = new int[count]; wordEnds = new int[count];
        firstGlyphs = new int[count]; glyphCounts = new int[count];
        starts = new long[count]; durations = new double[count]; factors = new double[count];
        consumed = new boolean[count]; chineseTailWords = new boolean[count];
        attackDelays = new double[totalGlyphs]; returnDelays = new double[totalGlyphs];
        double[] glyphPeriods = new double[totalGlyphs], wordPeriods = new double[count];
        int glyph = 0;
        for (int wordIndex = 0; wordIndex < count; wordIndex++) {
            VisualWord word = visualWords.get(wordIndex);
            chineseTailWords[wordIndex] = word.chineseTail;
            wordStarts[wordIndex] = word.start; wordEnds[wordIndex] = word.end;
            starts[wordIndex] = word.startMillis;
            double duration = word.endMillis > word.startMillis
                    ? ((double) word.endMillis - word.startMillis) / 1_000d : 0d;
            durations[wordIndex] = duration;
            factors[wordIndex] = word.chineseTail
                    ? chineseTailFactor(duration)
                    : (duration > 1d && word.end - word.start < 8 ? Math.min(duration, 2d) - 1d : 0d);
            double period = Math.min(Math.max(duration, 0.001d), 3d);
            wordPeriods[wordIndex] = period;
            firstGlyphs[wordIndex] = glyph;
            int glyphCount = Math.max(0, word.boundaries.length - 1);
            glyphCounts[wordIndex] = glyphCount;
            double unitDuration = glyphCount > 0 ? duration / glyphCount : 0d;
            for (int local = 0; local < glyphCount; local++, glyph++) {
                glyphWords[glyph] = wordIndex;
                glyphPeriods[glyph] = period;
                attackDelays[glyph] = (local + 1d) * Math.min(unitDuration * 0.4d, 0.4d) * 1_000d;
                returnDelays[glyph] = attackDelays[glyph] + 2d * unitDuration * 1_000d;
                for (int at = word.boundaries[local]; at < word.boundaries[local + 1]; at++)
                    if (word.chineseTail || characterGlyph[at] < 0) characterGlyph[at] = glyph;
            }
        }
        glyphMotion = new SpringChannel(glyphPeriods, false);
        glowMotion = new SpringChannel(wordPeriods, true);
    }

    public void sample(long position, long now, boolean selected, boolean animate,
                       String sourceLanguage) {
        sample(position, now, selected, animate, sourceLanguage, true);
    }

    public void sample(long position, long now, boolean selected, boolean animate,
                       String sourceLanguage, boolean chineseTailEnabled) {
        this.chineseTailEnabled = chineseTailEnabled;
        if (!languageKnown || !(previousLanguage == sourceLanguage
                || (previousLanguage != null && previousLanguage.equals(sourceLanguage)))) {
            languageAllowed = LyricAnimationLanguage.allowsEmphasis(sourceLanguage, text);
            chineseLanguageAllowed = LyricAnimationLanguage.allowsChineseTailEmphasis(sourceLanguage, text);
            previousLanguage = sourceLanguage;
            languageKnown = true;
        }
        boolean first = !initialized;
        double elapsed = first ? 0d : (double) now - lastNow;
        double positionDelta = first ? 0d : (double) position - lastPosition;
        boolean continuous = !first && elapsed >= 0d && elapsed <= MAX_OBSERVATION_GAP_MS
                && positionDelta >= -POSITION_JUMP_TOLERANCE_MS
                && positionDelta <= Math.max(POSITION_JUMP_TOLERANCE_MS, elapsed + 160d);
        if (!animate || !continuous) {
            glyphMotion.clear(); glowMotion.clear();
            for (int word = 0; word < starts.length; word++) {
                consumed[word] = position >= starts[word];
                // First observation exactly at a real start is not a replay from mid-word.
                if (first && animate && selected && allowsWord(word) && position == starts[word])
                    trigger(word, now);
            }
        } else {
            glyphMotion.sample(now); glowMotion.sample(now);
            boolean rewind = positionDelta < 0d;
            for (int word = 0; word < starts.length; word++) {
                if (position < starts[word]) {
                    consumed[word] = false;
                    release(word, now);
                } else {
                    if (!consumed[word]) {
                        consumed[word] = true;
                        if (selected && allowsWord(word) && !rewind) trigger(word, now);
                    }
                    if (!selected || !allowsWord(word) || rewind) release(word, now);
                }
            }
        }
        glyphMotion.sample(now); glowMotion.sample(now);
        running = glyphMotion.running || glowMotion.running;
        initialized = true; lastPosition = position; lastNow = now;
    }

    private boolean allowsWord(int word) {
        return chineseTailWords[word] ? chineseTailEnabled && chineseLanguageAllowed : languageAllowed;
    }

    private static double chineseTailFactor(double duration) {
        if (duration < 1d) return 0d;
        if (duration <= 1.5d) return 0.2d + 0.4d * (duration - 1d) / 0.5d;
        if (duration < 2.1d) return 0.6d + 0.4d * (duration - 1.5d) / 0.6d;
        return 1d;
    }

    private void trigger(int word, long now) {
        if (factors[word] <= 0d || glyphCounts[word] == 0) return;
        int end = firstGlyphs[word] + glyphCounts[word];
        for (int glyph = firstGlyphs[word]; glyph < end; glyph++)
            glyphMotion.schedule(glyph, now, attackDelays[glyph], returnDelays[glyph]);
        // Native glow is word-level, starts immediately, and deglows after D seconds.
        glowMotion.schedule(word, now, 0d, durations[word] * 1_000d);
    }

    private void release(int word, long now) {
        int end = firstGlyphs[word] + glyphCounts[word];
        for (int glyph = firstGlyphs[word]; glyph < end; glyph++) glyphMotion.release(glyph, now);
        glowMotion.release(word, now);
    }

    public float amountForCharacter(int utf16Index) {
        int glyph = glyphFor(utf16Index);
        return glyph < 0 || !allowsWord(glyphWords[glyph]) ? 0f
                : (float) (factors[glyphWords[glyph]] * glyphMotion.values[glyph]);
    }

    public float glowAmountForCharacter(int utf16Index) {
        int glyph = glyphFor(utf16Index);
        if (glyph < 0) return 0f;
        int word = glyphWords[glyph];
        return allowsWord(word) ? (float) (factors[word] * glowMotion.values[word]) : 0f;
    }

    public int wordStartForCharacter(int utf16Index) {
        int glyph = glyphFor(utf16Index);
        return glyph < 0 ? -1 : wordStarts[glyphWords[glyph]];
    }

    public int wordEndForCharacter(int utf16Index) {
        int glyph = glyphFor(utf16Index);
        return glyph < 0 ? -1 : wordEnds[glyphWords[glyph]];
    }

    /** Identity of the mapped Chinese extension group; current amount/capability is separate. */
    public boolean isChineseTailForCharacter(int utf16Index) {
        int glyph = glyphFor(utf16Index);
        return glyph >= 0 && chineseTailWords[glyphWords[glyph]];
    }

    private int glyphFor(int index) {
        return index < 0 || index >= characterGlyph.length ? -1 : characterGlyph[index];
    }

    public boolean isRunning() { return running; }

    public void reset() {
        glyphMotion.clear(); glowMotion.clear(); Arrays.fill(consumed, false);
        initialized = false; running = false;
    }

    private static boolean whitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static int trimStart(String text, int start, int end) {
        while (start < end && whitespace(text.codePointAt(start)))
            start += Character.charCount(text.codePointAt(start));
        return start;
    }

    private static int trimEnd(String text, int start, int end) {
        while (end > start && whitespace(text.codePointBefore(end)))
            end -= Character.charCount(text.codePointBefore(end));
        return end;
    }

    private static boolean containsWhitespace(String text, int start, int end) {
        for (int at = start; at < end; ) {
            int codePoint = text.codePointAt(at);
            if (whitespace(codePoint)) return true;
            at += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean tailSeparator(int codePoint) {
        if (whitespace(codePoint) || codePoint == '~' || codePoint == 0xFF5E) return true;
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean onlyTailSeparators(String text, int start, int end) {
        for (int at = start; at < end; ) {
            int codePoint = text.codePointAt(at);
            if (!tailSeparator(codePoint)) return false;
            at += Character.charCount(codePoint);
        }
        return true;
    }

    private static VisualWord chineseTail(String text, WordRange range, int sourceIndex) {
        if (range == null || range.endTimeMillis <= range.timeMillis) return null;
        int sungEnd = text.length();
        while (sungEnd > 0 && tailSeparator(text.codePointBefore(sungEnd)))
            sungEnd -= Character.charCount(text.codePointBefore(sungEnd));
        if (sungEnd == 0) return null;
        int codePoint = text.codePointBefore(sungEnd);
        if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return null;
        int start = sungEnd - Character.charCount(codePoint);
        int sourceStart = trimStart(text, range.start, range.end);
        int sourceEnd = trimEnd(text, sourceStart, range.end);
        if (sourceStart != start || sourceEnd < sungEnd
                || !onlyTailSeparators(text, sungEnd, sourceEnd)) return null;
        // A single genuinely timed Han character, never a split of a multi-character token.
        VisualWord word = new VisualWord(start, sungEnd, range, sourceIndex, false, start, sungEnd);
        word.chineseTail = true;
        return word;
    }

    private static final class VisualWord {
        final int start, tokenStart, tokenEnd;
        int end;
        final long startMillis;
        final boolean token;
        boolean chineseTail;
        int sourceEnd, lastSourceIndex;
        long endMillis, lastStart;
        int[] boundaries;
        VisualWord(int start, int end, WordRange range, int sourceIndex, boolean token,
                   int tokenStart, int tokenEnd) {
            this.start = start; this.end = end; this.sourceEnd = end;
            this.tokenStart = tokenStart; this.tokenEnd = tokenEnd;
            startMillis = range.timeMillis; endMillis = range.endTimeMillis;
            lastStart = range.timeMillis; lastSourceIndex = sourceIndex; this.token = token;
        }
    }

    /** Two exact event transitions per trigger, followed by a finite epsilon settle. */
    private static final class SpringChannel {
        private static final double EPSILON = 0.001d;
        private static final double GLOW_DECAY = 3.5d;
        private static final double GLOW_FREQUENCY = Math.sqrt(1.75d);
        final double[] values;
        final double[] velocities, origins, originVelocities, targets, beganAt, attackAt, returnAt, omega;
        final byte[] phase;
        final boolean[] moving;
        final boolean underdampedReturn;
        boolean running;
        SpringChannel(double[] periods, boolean underdampedReturn) {
            int count = periods.length;
            values = new double[count]; velocities = new double[count]; origins = new double[count];
            originVelocities = new double[count]; targets = new double[count]; beganAt = new double[count];
            attackAt = new double[count]; returnAt = new double[count]; omega = new double[count];
            phase = new byte[count]; moving = new boolean[count]; this.underdampedReturn = underdampedReturn;
            for (int i = 0; i < count; i++) omega[i] = 2d * Math.PI / periods[i];
        }
        void schedule(int index, double now, double attackDelay, double returnDelay) {
            attackAt[index] = now + attackDelay; returnAt[index] = now + returnDelay;
            phase[index] = 1;
        }
        void sample(double now) {
            running = false;
            for (int i = 0; i < values.length; i++) {
                if (phase[i] == 1 && now >= attackAt[i]) {
                    retarget(i, 1d, attackAt[i]); phase[i] = 2;
                }
                if (phase[i] == 2 && now >= returnAt[i]) {
                    retarget(i, 0d, returnAt[i]); phase[i] = 3;
                }
                advance(i, now);
                if (phase[i] == 3 && !moving[i]) phase[i] = 0;
                running |= moving[i] || phase[i] == 1 || phase[i] == 2;
            }
        }
        void release(int index, double now) {
            if (targets[index] != 0d) retarget(index, 0d, now);
            phase[index] = moving[index] ? (byte) 3 : 0;
        }
        private void retarget(int index, double target, double now) {
            advance(index, now);
            origins[index] = values[index]; originVelocities[index] = velocities[index];
            targets[index] = target; beganAt[index] = now;
            moving[index] = values[index] != target || velocities[index] != 0d;
        }
        private void advance(int index, double now) {
            if (!moving[index]) return;
            double seconds = Math.max(0d, (now - beganAt[index]) / 1_000d);
            double relative = origins[index] - targets[index], displacement, velocity;
            if (underdampedReturn && targets[index] == 0d) {
                double sineCoefficient = (originVelocities[index] + GLOW_DECAY * relative) / GLOW_FREQUENCY;
                double angle = GLOW_FREQUENCY * seconds;
                double sine = Math.sin(angle), cosine = Math.cos(angle), envelope = Math.exp(-GLOW_DECAY * seconds);
                double wave = relative * cosine + sineCoefficient * sine;
                displacement = envelope * wave;
                velocity = envelope * ((-relative * GLOW_FREQUENCY * sine
                        + sineCoefficient * GLOW_FREQUENCY * cosine) - GLOW_DECAY * wave);
            } else {
                double frequency = omega[index];
                double linear = originVelocities[index] + frequency * relative;
                double envelope = Math.exp(-frequency * seconds);
                displacement = (relative + linear * seconds) * envelope;
                velocity = (linear - frequency * (relative + linear * seconds)) * envelope;
            }
            values[index] = targets[index] + displacement; velocities[index] = velocity;
            if (Math.abs(displacement) <= EPSILON && Math.abs(velocity) <= EPSILON) {
                values[index] = targets[index]; velocities[index] = 0d; moving[index] = false;
            }
        }
        void clear() {
            Arrays.fill(values, 0d); Arrays.fill(velocities, 0d); Arrays.fill(origins, 0d);
            Arrays.fill(originVelocities, 0d); Arrays.fill(targets, 0d); Arrays.fill(phase, (byte) 0);
            Arrays.fill(moving, false); running = false;
        }
    }
}
