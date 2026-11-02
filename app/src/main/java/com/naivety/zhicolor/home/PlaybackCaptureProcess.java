package com.naivety.zhicolor.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Looper;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short-lived root entry point. Captures MEDIA/UNKNOWN playback from ONE UID; never a microphone.
 * No capture-policy override, output-mix fallback, recording file, or persistent service.
 * Only 48 spectrum amplitudes and two level measurements leave this process. */
public final class PlaybackCaptureProcess {
    public static final int MAGIC = 0x5a484950;
    public static final int MAGIC_V2 = 0x5a484951;
    private static final int RATE = 48000;
    private static final int FRAMES = 2048;
    public static void main(String[] args) {
        int exit = 0;
        try { run(Integer.parseInt(args[0]), Integer.parseInt(args[1])); }
        catch (Throwable error) { System.err.println("ZHIColor playback capture: " + error); exit = 1; }
        System.exit(exit);
    }

    private static void run(int uid, int ownerPid) throws Exception {
        if (uid < 10000 || ownerPid <= 0) throw new IllegalArgumentException("Invalid playback owner");
        Looper.prepare();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        AudioManager manager = context.getSystemService(AudioManager.class);
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> rbClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> mbClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
        Class<?> pbClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object rb = rbClass.getConstructor().newInstance();
        rbClass.getMethod("addMixRule", int.class, Object.class).invoke(rb, 4, uid);
        for (int usage : new int[] {AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_UNKNOWN}) {
            rbClass.getMethod("addRule", AudioAttributes.class, int.class).invoke(rb,
                new AudioAttributes.Builder().setUsage(usage).build(), 1);
        }
        Object rule = rbClass.getMethod("build").invoke(rb);
        Object mb = mbClass.getConstructor(ruleClass).newInstance(rule);
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        mbClass.getMethod("setFormat", AudioFormat.class).invoke(mb, format);
        // LOOP_BACK | RENDER duplicates matching playback and leaves the user's output audible.
        mbClass.getMethod("setRouteFlags", int.class).invoke(mb, 3);
        Object mix = mbClass.getMethod("build").invoke(mb);
        Object pb = pbClass.getConstructor(Context.class).newInstance(context);
        pbClass.getMethod("setLooper", Looper.class).invoke(pb, Looper.myLooper());
        pbClass.getMethod("addMix", mixClass).invoke(pb, mix);
        Object policy = pbClass.getMethod("build").invoke(pb);
        int status = (Integer) AudioManager.class.getMethod("registerAudioPolicy", policyClass).invoke(manager, policy);
        if (status != 0) throw new IllegalStateException("Audio policy registration: " + status);
        AudioRecord record = null;
        AtomicBoolean running = new AtomicBoolean(true);
        try {
            record = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Playback sink initialization");
            final AudioRecord sink = record;
            Thread endOfOwner = new Thread(() -> {
                try { System.in.read(); } catch (Exception ignored) {}
                running.set(false);
                try { sink.stop(); } catch (Exception ignored) {}
            }, "ZHIColor-capture-owner");
            endOfOwner.setDaemon(true); endOfOwner.start();
            record.startRecording();
            DataOutputStream output = new DataOutputStream(new BufferedOutputStream(System.out));
            output.writeInt(MAGIC_V2); output.flush();
            short[] pcm = new short[FRAMES * 2];
            float[] left = new float[FRAMES], right = new float[FRAMES];
            WaveformSpectrum fftLeft = new WaveformSpectrum(), fftRight = new WaveformSpectrum();
            RawBackgroundSpectrum backgroundSpectrum = new RawBackgroundSpectrum();
            File owner = new File("/proc/" + ownerPid);
            while (running.get() && owner.exists()) {
                int offset = 0;
                while (offset < pcm.length && running.get()) {
                    int read = record.read(pcm, offset, pcm.length - offset);
                    if (read <= 0) { running.set(false); break; }
                    offset += read;
                }
                if (offset != pcm.length) break;
                long capturedAtNanos = android.os.SystemClock.elapsedRealtimeNanos();
                float peak = 0f;
                for (int i = 0; i < FRAMES; i++) {
                    left[i] = pcm[2 * i] / 32768f; right[i] = pcm[2 * i + 1] / 32768f;
                    peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
                }
                WaveformSpectrum.Frame l = fftLeft.analyze(left, RATE * 1000);
                WaveformSpectrum.Frame r = fftRight.analyze(right, RATE * 1000);
                List<Float> lb = l.getBands(), rbands = r.getBands();
                for (int i = 0; i < 48; i++) output.writeFloat(powerMean(lb.get(i), rbands.get(i)));
                output.writeFloat(powerMean(l.getRms(), r.getRms())); output.writeFloat(peak);
                BackgroundAudioFrame background = backgroundSpectrum.analyzeStereo(left, right, RATE * 1000);
                output.writeFloat(background.getLow()); output.writeFloat(background.getMid()); output.writeFloat(background.getHigh());
                output.writeFloat(background.getLevel());
                output.writeLong(capturedAtNanos); output.flush();
            }
        } finally {
            running.set(false);
            if (record != null) {
                try { record.stop(); } catch (Exception ignored) {}
                record.release();
            }
            AudioManager.class.getMethod("unregisterAudioPolicy", policyClass).invoke(manager, policy);
        }
    }
    private static float powerMean(float a, float b) { return (float) Math.sqrt((a * a + b * b) * 0.5f); }
}
