package com.naivety.zhicolor.bridge;

import com.naivety.zhicolor.bridge.render.AppleLyricMotionPolicy;
import com.naivety.zhicolor.bridge.render.LyricTimingMode;
import com.naivety.zhicolor.bridge.render.WordLine;
import com.naivety.zhicolor.bridge.render.WordLyricModel;
import com.naivety.zhicolor.bridge.render.WordLyricRenderSupport;
import com.naivety.zhicolor.bridge.render.WordRange;
import com.naivety.zhicolor.bridge.render.ZhiToolLyricMotion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apple Music (iOS 27 LyricsX) waiting-dots policy for pre-first-line intros
 * and mid-song instrumental breaks: standalone row injection, gap discovery,
 * AM 4-phase dot timeline, and synchronous overall group breathing scale.
 */
public final class LyricWaitingDotsPolicy {
    /** Gap strictly above 7.0s spawns waiting dots. */
    public static final long MIN_GAP_MILLIS = 7_000L;
    /** Interlude dots start 0.1s after the preceding vocal finishes. */
    public static final long INTERLUDE_START_OFFSET_MILLIS = 100L;
    /** AM LyricsSpecs animationHeadstart=0.4s: animated collapse starts right as exit fade finishes. */
    public static final long LAYOUT_COLLAPSE_HEADSTART_MILLIS = 400L;

    public static final int DOT_COUNT = 3;
    /** AM LyricsSpecs: dotLength=12, dotMargin=8, viewHeight=40 at ~36sp baseline (~0.333em / 0.222em). */
    public static final float DOT_DIAMETER_EM = 0.33f;
    public static final float DOT_MARGIN_EM = 0.22f;
    public static final float DOT_PITCH_EM = DOT_DIAMETER_EM + DOT_MARGIN_EM;
    public static final float MAX_SCALE = 1.2f;
    public static final float MIN_BREATH_SCALE = 0.9f;
    public static final float EXIT_SHRINK_SCALE = 0.2f;
    public static final float UNLIT_DOT_ALPHA = 0.1f;

    private static final double ENTRY_FADE_DURATION_S = 0.8;
    private static final double ENTRY_STAGGER_S = 0.06;
    private static final double FADE_IN_START_OFFSET_S = 1.0;
    private static final double BREATH_TRIGGER_LEAD_S = 1.8;
    private static final double BREATH_CYCLES_PER_SECOND = 0.25;
    private static final double NOMINAL_BREATH_PERIOD_S = 4.0;
    private static final double BREATH_HOLD_MAX_S = 0.2;
    private static final double COUNTDOWN_FILL_TRIM_S = 0.1;
    private static final double EXIT_SWELL_DURATION_S = 1.0;
    private static final double EXIT_FADE_DURATION_S = 0.3;
    private static final double EXIT_SHRINK_DURATION_S = 0.5;
    private static final Pattern LEADING_LRC_TIME_TAG =
            Pattern.compile("^\\[([0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?)]");

    private static final long FOCUS_HANDOFF_MILLIS = 280L;

    private static final Map<WordLyricModel, CachedBreaks> BREAK_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private LyricWaitingDotsPolicy() {}

    /** Single waiting-dots window hosted on the upcoming singable {@link #hostLine}. */
    public static final class BreakWindow {
        public final WordLine hostLine;
        public final WordLine previousLine;
        public final int hostIndex;
        public final long startMillis;
        public final long endMillis;
        public final boolean intro;

        public BreakWindow(
                WordLine hostLine,
                WordLine previousLine,
                int hostIndex,
                long startMillis,
                long endMillis,
                boolean intro) {
            this.hostLine = hostLine;
            this.previousLine = previousLine;
            this.hostIndex = hostIndex;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.intro = intro;
        }

        public boolean contains(long positionMillis) {
            return positionMillis >= startMillis && positionMillis < endMillis;
        }
    }

    /** Evaluated AM 4-phase per-dot opacities and scale factors. */
    public static final class DotFrameState {
        public final float[] opacities = new float[DOT_COUNT];
        public final float[] scales = new float[DOT_COUNT];
        public final boolean anyVisible;

        private DotFrameState(float[] opacities, float[] scales, boolean anyVisible) {
            System.arraycopy(opacities, 0, this.opacities, 0, DOT_COUNT);
            System.arraycopy(scales, 0, this.scales, 0, DOT_COUNT);
            this.anyVisible = anyVisible;
        }
    }

    private static final class CachedBreaks {
        final int lineCount;
        final List<BreakWindow> windows;

        CachedBreaks(int lineCount, List<BreakWindow> windows) {
            this.lineCount = lineCount;
            this.windows = windows;
        }
    }

    public static boolean isSingableLine(WordLine line) {
        if (line == null || line.timeMillis < 0L || line.normalizedText.isEmpty()
                || LyricMetadataFilter.isNonLyricInfoLine(line.text, line.timeMillis)) {
            return false;
        }
        for (int i = 0; i < line.normalizedText.length();) {
            int codePoint = line.normalizedText.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    public static long beginMillis(WordLine line) {
        if (line == null) {
            return -1L;
        }
        if (line.timingMode == LyricTimingMode.WORD_TIMED && line.words != null) {
            for (WordRange word : line.words) {
                if (word != null && word.timeMillis >= 0L && word.end > word.start
                        && word.start >= 0 && word.end <= line.text.length()
                        && hasVisibleWord(line.text, word.start, word.end)) {
                    return word.timeMillis;
                }
            }
        }
        return line.timeMillis;
    }

    private static boolean hasVisibleWord(String text, int start, int end) {
        String word = LyricTextSanitizer.removeIgnorableCharacters(text.substring(start, end));
        for (int i = 0; i < word.length();) {
            int codePoint = word.codePointAt(i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }

    private static long explicitEndMillis(WordLine line) {
        if (line == null || !line.explicitEndTimeProvided) {
            return -1L;
        }
        long end = Math.max(line.timeMillis, line.endTimeMillis);
        if (line.words != null && !line.words.isEmpty()) {
            long wordEnd = line.wordEndMillis(line.words.size() - 1);
            if (wordEnd > end) {
                end = wordEnd;
            }
        }
        if (line.secondaryLine != null && line.secondaryLine.explicitEndTimeProvided) {
            long secondaryEnd = Math.max(
                    line.secondaryLine.timeMillis,
                    line.secondaryLine.endTimeMillis);
            if (secondaryEnd > end) {
                end = secondaryEnd;
            }
        }
        return end;
    }

    public static List<BreakWindow> breaks(WordLyricModel model) {
        if (model == null || model.lines.isEmpty()) {
            return Collections.emptyList();
        }
        int lineCount = model.lines.size();
        CachedBreaks cached = BREAK_CACHE.get(model);
        if (cached != null && cached.lineCount == lineCount) {
            return cached.windows;
        }
        ArrayList<BreakWindow> result = new ArrayList<>();
        WordLine prevSingable = null;
        long runningExplicitEnd = -1L;
        long latestSpacerBetween = -1L;
        for (int i = 0; i < lineCount; i++) {
            WordLine line = model.lines.get(i);
            if (!isSingableLine(line)) {
                if (prevSingable != null && line != null && !line.isWaitingDots
                        && !LyricMetadataFilter.isNonLyricInfoLine(line.text, line.timeMillis)
                        && line.timeMillis > prevSingable.timeMillis) {
                    latestSpacerBetween = Math.max(latestSpacerBetween, line.timeMillis);
                }
                continue;
            }
            long currBegin = beginMillis(line);
            if (currBegin < 0L) {
                continue;
            }
            if (prevSingable == null) {
                if (currBegin > MIN_GAP_MILLIS) {
                    result.add(new BreakWindow(line, null, i, 0L, currBegin, true));
                }
            } else {
                long gapStart = -1L;
                if (prevSingable.explicitEndTimeProvided && runningExplicitEnd >= 0L) {
                    gapStart = latestSpacerBetween > runningExplicitEnd
                            ? latestSpacerBetween
                            : runningExplicitEnd;
                } else if (latestSpacerBetween > prevSingable.timeMillis) {
                    gapStart = latestSpacerBetween;
                }
                if (gapStart >= 0L && currBegin - gapStart > MIN_GAP_MILLIS) {
                    long breakStart = gapStart + INTERLUDE_START_OFFSET_MILLIS;
                    if (currBegin > breakStart) {
                        result.add(new BreakWindow(
                                line, prevSingable, i, breakStart, currBegin, false));
                    }
                }
            }
            long currEnd = explicitEndMillis(line);
            if (currEnd >= 0L) {
                runningExplicitEnd = Math.max(runningExplicitEnd, currEnd);
            }
            prevSingable = line;
            latestSpacerBetween = -1L;
        }
        List<BreakWindow> immutable = result.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(result);
        BREAK_CACHE.put(model, new CachedBreaks(lineCount, immutable));
        return immutable;
    }

    public static BreakWindow activeBreak(WordLyricModel model, long positionMillis) {
        if (model == null || positionMillis < 0L) {
            return null;
        }
        List<BreakWindow> windows = breaks(model);
        for (int i = 0; i < windows.size(); i++) {
            BreakWindow window = windows.get(i);
            if (window.contains(positionMillis)) {
                return window;
            }
            if (window.startMillis > positionMillis) {
                break;
            }
        }
        return null;
    }

    public static WordLine activeHostLine(WordLyricModel model, long positionMillis) {
        BreakWindow active = activeBreak(model, positionMillis);
        return active == null ? null : active.hostLine;
    }

    public static boolean isBreakHostBeforeSingStart(
            WordLyricModel model,
            WordLine line,
            long positionMillis) {
        BreakWindow active = activeBreak(model, positionMillis);
        return active != null && line != null && active.hostLine == line;
    }

    /** Matches AM's pre-first-line binding guard (allowing repeated interlude host text via identity). */
    public static WordLine boundIntroLine(
            WordLyricModel model,
            WordLine indexedLine,
            String normalizedText,
            long positionMillis) {
        BreakWindow active = activeBreak(model, positionMillis);
        if (active == null || !active.intro) {
            return null;
        }
        WordLine host = active.hostLine;
        if (!WordLyricRenderSupport.matchesWordLineText(host, normalizedText)) {
            return null;
        }
        if (indexedLine != null && indexedLine != host && isSingableLine(indexedLine)) {
            return null;
        }
        if (indexedLine != host && model.hasDuplicateRenderableText(normalizedText)) {
            return null;
        }
        return host;
    }

    public static boolean isWaitingDotsText(CharSequence text) {
        return LyricTextSanitizer.isWaitingDotsMarker(text);
    }

    public static WordLine activeWaitingDotsLine(WordLyricModel model, long positionMillis) {
        if (model == null || positionMillis < 0L || model.lines.isEmpty()) {
            return null;
        }
        WordLine candidate = model.findActiveLine(positionMillis);
        if (candidate != null && candidate.isWaitingDots
                && positionMillis >= candidate.timeMillis
                && positionMillis < candidate.endTimeMillis) {
            return candidate;
        }
        for (int i = 0; i < model.lines.size(); i++) {
            WordLine line = model.lines.get(i);
            if (line == null) {
                continue;
            }
            if (line.timeMillis > positionMillis) {
                break;
            }
            if (line.isWaitingDots && positionMillis < line.endTimeMillis) {
                return line;
            }
        }
        return null;
    }

    public static boolean shouldAnimate(
            BreakWindow window,
            long positionMillis,
            boolean playbackAdvancing,
            boolean motionEnabled) {
        return playbackAdvancing
                && motionEnabled
                && window != null
                && window.contains(positionMillis);
    }

    public static boolean shouldAnimate(
            WordLine waitingLine,
            long positionMillis,
            boolean playbackAdvancing,
            boolean motionEnabled) {
        return playbackAdvancing
                && motionEnabled
                && waitingLine != null
                && waitingLine.isWaitingDots
                && positionMillis >= waitingLine.timeMillis
                && positionMillis < waitingLine.endTimeMillis;
    }

    /**
     * Injects standalone waiting-dots rows ({@code \u200B\u2060}) into SystemUI's normalized
     * official LRC list and restores pre-rolled intro vocal timestamps so dots occupy their own
     * RecyclerView row.
     */
    public static LyricInfoContract.NormalizedPayload injectOfficialWaitingDotsRows(
            LyricInfoContract.Payload preNormalized,
            LyricInfoContract.NormalizedPayload normalized) {
        if (normalized == null || normalized.payload == null || preNormalized == null
                || normalized.payload.snapshotPositionMillis >= 0L
                || !LyricInfoContract.containsTimedLrc(normalized.payload.lyric)) {
            return normalized;
        }
        String rawSource = LyricInfoContract.containsTimedLrc(preNormalized.rawLyric)
                ? preNormalized.rawLyric
                : preNormalized.lyric;
        if (!LyricInfoContract.containsTimedLrc(rawSource)) {
            return normalized;
        }
        boolean displayTimed = LyricInfoContract.containsTimedLrc(preNormalized.lyric);
        WordLyricModel previewModel = new NativeLyricModelAssembler(null).assemble(
                rawSource,
                true,
                preNormalized.lyric,
                displayTimed,
                "",
                preNormalized.lineMetadata).model;
        List<BreakWindow> windows = breaks(previewModel);
        if (windows.isEmpty()) {
            return normalized;
        }
        List<OfficialLrcRow> rows = parseOfficialLrcRows(normalized.payload.lyric);
        if (rows.isEmpty()) {
            return normalized;
        }
        boolean modified = false;
        for (int w = 0; w < windows.size(); w++) {
            BreakWindow window = windows.get(w);
            if (window.intro) {
                if (insertIntroDotsRow(rows, window.endMillis)) {
                    modified = true;
                }
            } else {
                if (insertInterludeDotsRow(rows, window.startMillis)) {
                    modified = true;
                }
            }
        }
        if (!modified) {
            return normalized;
        }
        String updatedLyric = formatOfficialLrcRows(rows);
        return LyricInfoContract.withOfficialLyric(normalized, updatedLyric);
    }

    private static boolean insertIntroDotsRow(List<OfficialLrcRow> rows, long introEndMillis) {
        if (rows.isEmpty() || introEndMillis <= MIN_GAP_MILLIS) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            OfficialLrcRow existing = rows.get(i);
            if (existing.timeMillis >= introEndMillis) {
                break;
            }
            if (LyricTextSanitizer.isWaitingDotsMarker(existing.text)) {
                return false;
            }
        }
        long nonMetadataZeroRestore = introEndMillis;
        for (int i = 0; i < rows.size(); i++) {
            OfficialLrcRow row = rows.get(i);
            if (row.timeMillis > 0L && !LyricMetadataFilter.isNonLyricInfoLine(row.text, row.timeMillis)) {
                nonMetadataZeroRestore = Math.min(introEndMillis, row.timeMillis);
                break;
            }
        }
        int insertIndex = 0;
        long dotsTimeMillis = 0L;
        for (int i = 0; i < rows.size(); i++) {
            OfficialLrcRow row = rows.get(i);
            if (row.timeMillis >= introEndMillis) {
                break;
            }
            if (LyricMetadataFilter.isNonLyricInfoLine(row.text, row.timeMillis)) {
                insertIndex = i + 1;
                dotsTimeMillis = Math.max(dotsTimeMillis, row.timeMillis + 1L);
            } else if (row.timeMillis == 0L && !LyricTextSanitizer.isPlaceholderOnly(row.text)) {
                row.timeMillis = nonMetadataZeroRestore;
            }
        }
        if (dotsTimeMillis >= introEndMillis) {
            return false;
        }
        rows.add(insertIndex, new OfficialLrcRow(dotsTimeMillis, LyricTextSanitizer.WAITING_DOTS_MARKER));
        return true;
    }

    private static boolean insertInterludeDotsRow(List<OfficialLrcRow> rows, long startMillis) {
        if (rows.isEmpty() || startMillis <= 0L) {
            return false;
        }
        int insertIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            OfficialLrcRow row = rows.get(i);
            if (LyricTextSanitizer.isWaitingDotsMarker(row.text)
                    && Math.abs(row.timeMillis - startMillis) <= INTERLUDE_START_OFFSET_MILLIS) {
                return false;
            }
            if (row.timeMillis > startMillis) {
                insertIndex = i;
                break;
            }
        }
        if (insertIndex <= 0) {
            return false;
        }
        boolean hasUpcomingLyricRow = false;
        for (int i = insertIndex; i < rows.size(); i++) {
            if (!LyricTextSanitizer.isPlaceholderOnly(rows.get(i).text)) {
                hasUpcomingLyricRow = true;
                break;
            }
        }
        if (!hasUpcomingLyricRow) {
            return false;
        }
        rows.add(insertIndex, new OfficialLrcRow(startMillis, LyricTextSanitizer.WAITING_DOTS_MARKER));
        return true;
    }

    private static List<OfficialLrcRow> parseOfficialLrcRows(String officialLyric) {
        ArrayList<OfficialLrcRow> rows = new ArrayList<>();
        if (officialLyric == null || officialLyric.isEmpty()) {
            return rows;
        }
        String[] rawLines = officialLyric.replace('\r', '\n').split("\n");
        for (String rawLine : rawLines) {
            if (rawLine == null || rawLine.isEmpty()) {
                continue;
            }
            Matcher matcher = LEADING_LRC_TIME_TAG.matcher(rawLine);
            if (!matcher.find()) {
                continue;
            }
            long timeMillis = parseLrcTimeMillis(matcher.group(1));
            String text = rawLine.substring(matcher.end());
            if (!text.isEmpty()) {
                rows.add(new OfficialLrcRow(timeMillis, text));
            }
        }
        return rows;
    }

    private static String formatOfficialLrcRows(List<OfficialLrcRow> rows) {
        StringBuilder out = new StringBuilder(rows.size() * 24);
        for (int i = 0; i < rows.size(); i++) {
            OfficialLrcRow row = rows.get(i);
            if (i > 0) {
                out.append('\n');
            }
            out.append('[').append(formatLrcTime(row.timeMillis)).append(']').append(row.text);
        }
        return out.toString();
    }

    private static long parseLrcTimeMillis(String time) {
        String[] minuteAndRest = time.split(":", 2);
        if (minuteAndRest.length != 2) {
            return 0L;
        }
        long minutes = safeParseLong(minuteAndRest[0]);
        String rest = minuteAndRest[1].replace(':', '.');
        String[] secondAndFraction = rest.split("\\.", 2);
        long seconds = safeParseLong(secondAndFraction[0]);
        long millis = 0L;
        if (secondAndFraction.length == 2) {
            String fraction = secondAndFraction[1];
            if (fraction.length() > 3) {
                fraction = fraction.substring(0, 3);
            }
            while (fraction.length() < 3) {
                fraction = fraction + "0";
            }
            millis = safeParseLong(fraction);
        }
        return minutes * 60_000L + seconds * 1_000L + millis;
    }

    private static long safeParseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String formatLrcTime(long timeMillis) {
        long clamped = Math.max(0L, timeMillis);
        long minutes = clamped / 60_000L;
        long seconds = (clamped % 60_000L) / 1_000L;
        long millis = clamped % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private static final class OfficialLrcRow {
        long timeMillis;
        final String text;

        OfficialLrcRow(long timeMillis, String text) {
            this.timeMillis = timeMillis;
            this.text = text;
        }
    }

    /** Nominal overall group breathing cycle period (seconds) during instrumental waiting. */
    public static double breathWavePeriodSeconds() {
        return NOMINAL_BREATH_PERIOD_S;
    }

    /** Exact quantized breathing cycle period (seconds) matching AM InstLayer_updateState. */
    public static double breathWavePeriodSeconds(long startMillis, long endMillis) {
        double startS = startMillis / 1000.0;
        double endS = endMillis / 1000.0;
        double breathTriggerS = Math.max(startS + FADE_IN_START_OFFSET_S, endS - BREATH_TRIGGER_LEAD_S);
        double breathSpanS = Math.max(0.1, breathTriggerS - startS);
        int cycles = Math.max(1, (int) Math.floor(breathSpanS * BREATH_CYCLES_PER_SECOND));
        return breathSpanS / cycles;
    }

    public static boolean isInBreakWindow(WordLine line, long positionMillis) {
        return line != null
                && line.isWaitingDots
                && positionMillis >= line.timeMillis
                && positionMillis < line.endTimeMillis;
    }

    public static boolean shouldExpandWaitingDotsLayout(
            WordLine line,
            long positionMillis,
            boolean animatedMode) {
        if (!isInBreakWindow(line, positionMillis)) {
            return false;
        }
        if (!animatedMode) {
            return true;
        }
        long duration = line.endTimeMillis - line.timeMillis;
        long collapseAt = duration >= 2_000L
                ? line.endTimeMillis - LAYOUT_COLLAPSE_HEADSTART_MILLIS
                : line.endTimeMillis;
        return positionMillis < collapseAt;
    }

    /** Evaluates AM 4-phase per-dot opacities and cycle-quantized overall group breathing scale. */
    public static DotFrameState evaluateDots(
            long startMillis,
            long endMillis,
            long positionMillis,
            boolean motionEnabled,
            boolean playbackAdvancing) {
        float[] opacities = new float[DOT_COUNT];
        float[] scales = new float[DOT_COUNT];
        if (positionMillis < startMillis || positionMillis >= endMillis) {
            for (int i = 0; i < DOT_COUNT; i++) {
                opacities[i] = 0f;
                scales[i] = EXIT_SHRINK_SCALE;
            }
            return new DotFrameState(opacities, scales, false);
        }
        if (!motionEnabled) {
            double endS = endMillis / 1000.0;
            double startS = startMillis / 1000.0;
            double breathTriggerS = Math.max(startS + FADE_IN_START_OFFSET_S, endS - BREATH_TRIGGER_LEAD_S);
            double dotFadeInDurationS = Math.max(0.15, (breathTriggerS - (startS + FADE_IN_START_OFFSET_S)) / DOT_COUNT);
            double elapsedS = positionMillis / 1000.0;
            for (int i = 0; i < DOT_COUNT; i++) {
                double fillAt = startS + FADE_IN_START_OFFSET_S + i * dotFadeInDurationS;
                opacities[i] = elapsedS >= fillAt ? 1f : 0.25f;
                scales[i] = 1f;
            }
            return new DotFrameState(opacities, scales, true);
        }

        double startS = startMillis / 1000.0;
        double endS = endMillis / 1000.0;
        double elapsedS = positionMillis / 1000.0;
        double breathTriggerS = Math.max(startS + FADE_IN_START_OFFSET_S, endS - BREATH_TRIGGER_LEAD_S);
        double fadeInStartS = startS + FADE_IN_START_OFFSET_S;
        double dotFadeInDurationS = Math.max(0.15, (breathTriggerS - fadeInStartS) / DOT_COUNT);
        double fillDurationS = Math.max(0.05, dotFadeInDurationS - COUNTDOWN_FILL_TRIM_S);

        boolean anyVisible = false;
        if (elapsedS < breathTriggerS) {
            double pausedAtStartFloor = (!playbackAdvancing && elapsedS < startS + 0.15) ? 1.0 : 0.0;
            float groupScale = evaluateOverallBreathScale(startS, breathTriggerS, elapsedS);
            for (int i = 0; i < DOT_COUNT; i++) {
                double entryStart = startS + i * ENTRY_STAGGER_S;
                double entryU = clampUnit((elapsedS - entryStart) / ENTRY_FADE_DURATION_S);
                float entryLinear = (float) Math.max(pausedAtStartFloor, entryU);
                double fillStart = fadeInStartS + i * dotFadeInDurationS;
                float fillLevel;
                if (elapsedS <= fillStart) {
                    fillLevel = UNLIT_DOT_ALPHA;
                } else {
                    double fillU = clampUnit((elapsedS - fillStart) / fillDurationS);
                    fillLevel = UNLIT_DOT_ALPHA + (1f - UNLIT_DOT_ALPHA) * (float) fillU;
                }
                float alpha = clampUnit(entryLinear * fillLevel);
                opacities[i] = alpha;
                scales[i] = groupScale;
                anyVisible |= alpha > 0.002f;
            }
            return new DotFrameState(opacities, scales, anyVisible);
        }

        // Phase 4: Gather group from resting breath scale to 1.2x, fade out, and shrink to 0.2x.
        double exitT = elapsedS - breathTriggerS;
        float preExitScale = evaluateOverallBreathScale(startS, breathTriggerS, breathTriggerS);
        float exitScale;
        if (exitT <= EXIT_SWELL_DURATION_S) {
            double uSwell = clampUnit(exitT / EXIT_SWELL_DURATION_S);
            exitScale = lerp(preExitScale, MAX_SCALE, (float) cubicBezierEase(0.25, 0.1, 0.25, 1.0, uSwell));
        } else {
            double uShrink = clampUnit((exitT - EXIT_SWELL_DURATION_S) / EXIT_SHRINK_DURATION_S);
            exitScale = lerp(MAX_SCALE, EXIT_SHRINK_SCALE, (float) cubicBezierEase(0.0, 0.0, 0.58, 1.0, uShrink));
        }
        float exitAlpha;
        if (exitT <= EXIT_SWELL_DURATION_S) {
            exitAlpha = 1f;
        } else {
            double uFade = clampUnit((exitT - EXIT_SWELL_DURATION_S) / EXIT_FADE_DURATION_S);
            exitAlpha = 1f - (float) cubicBezierEase(0.0, 0.0, 0.58, 1.0, uFade);
        }
        for (int i = 0; i < DOT_COUNT; i++) {
            opacities[i] = exitAlpha;
            scales[i] = exitScale;
        }
        return new DotFrameState(opacities, scales, exitAlpha > 0.002f);
    }

    private static float evaluateOverallBreathScale(double startS, double breathTriggerS, double elapsedS) {
        if (elapsedS <= startS) {
            return 1f;
        }
        double breathSpanS = Math.max(0.1, breathTriggerS - startS);
        int cycles = Math.max(1, (int) Math.floor(breathSpanS * BREATH_CYCLES_PER_SECOND));
        double halfCycleS = (breathSpanS / cycles) * 0.5;
        double holdS = Math.min(BREATH_HOLD_MAX_S, halfCycleS * 0.25);
        double localElapsedS = Math.max(0.0, Math.min(breathSpanS, elapsedS - startS));
        int totalHalfSteps = cycles * 2;
        int stepIndex = Math.min(totalHalfSteps - 1, (int) Math.floor(localElapsedS / halfCycleS));
        float fromScale = stepIndex == 0 ? 1f : ((stepIndex % 2 == 1) ? MAX_SCALE : MIN_BREATH_SCALE);
        float toScale = (stepIndex % 2 == 0) ? MAX_SCALE : MIN_BREATH_SCALE;
        double stepT = localElapsedS - stepIndex * halfCycleS;
        double moveDuration = halfCycleS - 2.0 * holdS;
        if (stepT <= holdS) {
            return fromScale;
        }
        if (stepT >= halfCycleS - holdS || moveDuration <= 1e-6) {
            return toScale;
        }
        double u = (stepT - holdS) / moveDuration;
        return lerp(fromScale, toScale, (float) easeInOut(u));
    }

    public static long focusTransitionMillis(int motionMode) {
        if (motionMode == LyricUiConfig.MOTION_OFF) {
            return 0L;
        }
        if (motionMode == LyricUiConfig.MOTION_APPLE) {
            return FOCUS_HANDOFF_MILLIS;
        }
        return Math.min(LyricMotionPolicy.focusMillis(motionMode), 340L);
    }

    public static long expandCollapseMillis(int motionMode) {
        if (motionMode == LyricUiConfig.MOTION_OFF) {
            return 0L;
        }
        if (motionMode == LyricUiConfig.MOTION_APPLE) {
            return AppleLyricMotionPolicy.SCROLL_MS;
        }
        return Math.max(240L, LyricMotionPolicy.scrollMillis(motionMode));
    }

    public static float expandCollapseCurve(int motionMode, float progress) {
        if (!Float.isFinite(progress) || progress <= 0f) {
            return 0f;
        }
        if (progress >= 1f) {
            return 1f;
        }
        if (motionMode == LyricUiConfig.MOTION_APPLE) {
            return AppleLyricMotionPolicy.springValue(progress);
        }
        return ZhiToolLyricMotion.easeOutCubic(progress);
    }

    public static float deselectedBodyScale(LyricUiConfig config, float dotsFocusAmount) {
        float focus = clampUnit(dotsFocusAmount);
        if (config == null || !config.scaleEnabled || focus <= 0f) {
            return 1f;
        }
        float inactive = Math.max(0.5f, Math.min(1f, config.inactiveScalePercent / 100f));
        return lerp(1f, inactive, focus);
    }

    /** Blurs the upcoming lyric body beneath focused dots without blurring the dots themselves. */
    public static float deselectedBodyBlurRadius(
            LyricUiConfig config,
            boolean aodLowFrameRateMode,
            float viewportAdjacentBlurPx,
            float dotsFocusAmount) {
        float focus = clampUnit(dotsFocusAmount);
        if (config == null || !config.blurEnabled || aodLowFrameRateMode || focus <= 0.001f) {
            return 0f;
        }
        float maxBlur = Math.max(0f, config.blurRadiusTenthsPx / 10f);
        if (maxBlur <= 0.05f) {
            return 0f;
        }
        float targetBlur = Math.max(viewportAdjacentBlurPx, maxBlur * 0.5f);
        return Math.min(maxBlur, targetBlur) * focus;
    }

    public static float rowHeight(float mainTextSize, float lineSpacing) {
        return Math.max(0f, mainTextSize) * 1.35f + Math.max(0f, lineSpacing);
    }

    public static float boundedTextSize(float mainTextSize, float availableWidth) {
        float groupSpanEm = (DOT_COUNT - 1) * DOT_PITCH_EM + DOT_DIAMETER_EM * MAX_SCALE;
        float maximum = Math.max(0f, availableWidth) / Math.max(0.1f, groupSpanEm);
        return Math.max(0f, Math.min(mainTextSize, maximum));
    }

    public static float dotCenterX(
            int dotIndex,
            int alignment,
            boolean rtl,
            float left,
            float availableWidth,
            float textSize) {
        float radius = textSize * (DOT_DIAMETER_EM * 0.5f);
        float pitch = textSize * DOT_PITCH_EM;
        float edgePad = radius * MAX_SCALE;
        float unscaledSpan = (DOT_COUNT - 1) * pitch;
        float totalVisualWidth = unscaledSpan + 2f * edgePad;
        float firstDotCenterX;
        if (alignment == LyricUiConfig.ALIGN_CENTER) {
            firstDotCenterX = left + availableWidth * 0.5f - unscaledSpan * 0.5f;
        } else {
            boolean alignRight = alignment == LyricUiConfig.ALIGN_END ? !rtl : rtl;
            firstDotCenterX = alignRight
                    ? left + availableWidth - Math.max(edgePad, totalVisualWidth - edgePad)
                    : left + Math.min(availableWidth * 0.5f, edgePad);
        }
        int visualOrder = rtl ? (DOT_COUNT - 1 - dotIndex) : dotIndex;
        return firstDotCenterX + visualOrder * pitch;
    }

    private static double easeInOut(double t) {
        double u = clampUnit(t);
        return u * u * (3.0 - 2.0 * u);
    }

    private static double cubicBezierEase(double x1, double y1, double x2, double y2, double x) {
        double target = clampUnit(x);
        if (target <= 0.0 || target >= 1.0) {
            return target;
        }
        double u = target;
        for (int i = 0; i < 6; i++) {
            double currentX = sampleCubic(u, x1, x2) - target;
            if (Math.abs(currentX) < 1e-6) {
                break;
            }
            double derivative = sampleCubicDerivative(u, x1, x2);
            if (Math.abs(derivative) < 1e-6) {
                break;
            }
            u = clampUnit(u - currentX / derivative);
        }
        return clampUnit(sampleCubic(u, y1, y2));
    }

    private static double sampleCubic(double u, double p1, double p2) {
        double inv = 1.0 - u;
        return 3.0 * inv * inv * u * p1 + 3.0 * inv * u * u * p2 + u * u * u;
    }

    private static double sampleCubicDerivative(double u, double p1, double p2) {
        double inv = 1.0 - u;
        return 3.0 * inv * inv * p1 + 6.0 * inv * u * (p2 - p1) + 3.0 * u * u * (1.0 - p2);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * clampUnit(t);
    }

    private static float clampUnit(float v) {
        return Math.max(0f, Math.min(1f, Float.isFinite(v) ? v : 0f));
    }

    private static double clampUnit(double v) {
        return Math.max(0.0, Math.min(1.0, Double.isFinite(v) ? v : 0.0));
    }
}
