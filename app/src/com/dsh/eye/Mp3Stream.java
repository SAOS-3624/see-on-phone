/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dsh.eye;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * MP3 **流式**播放：喂一块 MP3 字节，立刻就出声 —— 不等整句合成完。
 *
 * ## 为什么不用 MediaPlayer
 *
 * `MediaPlayer` 只吃"完整的文件/流"，边下边播要给它一个会增长的源头（自建 HTTP 代理、
 * 或者 `MediaDataSource` 自己实现 seek）——两条路都在赌机型行为，而且 `prepare()` 会
 * 等到能定位为止，等于又把首声拖回去。
 *
 * 这条路是 Android 自带的解码器直连扬声器，零第三方库（这个 App 全靠零依赖压到 113KB）：
 *
 * ```
 * MP3 块 → MediaCodec("audio/mpeg") → PCM 16bit → AudioTrack(MODE_STREAM) → 喇叭
 * ```
 *
 * ## 阻塞是特性，不是缺陷
 *
 * `AudioTrack.write()` 缓冲区满了就阻塞。这正好当节流用：网络来得再快，
 * 解码器和播放器也不会把内存吃光，而且打断时只要 `stop()` 就能立刻断。
 *
 * ⚠️ **只能在后台线程用**，而且 `feed()/finish()` 与 `stop()` 必须在不同线程调
 * （`stop()` 本来就是给"用户又说话了"那条控制线程用的）。
 */
final class Mp3Stream {

    /** 解码器/扬声器随便哪个的等待上限。给得短一点，打断才跟手。 */
    private static final long WAIT_US = 10_000;
    /** 兜底格式：服务端要的是 `audio-24khz-48kbitrate-mono-mp3`，真格式以解码器回报为准。 */
    private static final int DEF_RATE = 24_000;

    private MediaCodec codec;
    private AudioTrack track;
    private volatile boolean stopped;
    private int rate = DEF_RATE;
    private int channels = 1;
    private long framesWritten;
    /** 首块 PCM 落地的时刻（相对 {@link #t0}），给状态页看"到底有没有边收边播"。 */
    private long firstPcmAt;
    private long t0;

    void start() throws IOException {
        if (t0 == 0) t0 = System.currentTimeMillis();
        codec = MediaCodec.createDecoderByType("audio/mpeg");
        MediaFormat f = MediaFormat.createAudioFormat("audio/mpeg", DEF_RATE, 1);
        codec.configure(f, null, null, 0);
        codec.start();
    }

    /**
     * 喂一段 MP3（**会阻塞**到解码器收下为止）。
     *
     * MP3 是连续比特流，块不能丢 —— 所以输入缓冲拿不到就一边等一边把已经解出来的
     * PCM 放出去，绝不丢块（丢了后面整段都会花）。
     */
    void feed(byte[] data, int off, int len) {
        if (stopped || data == null || len <= 0) return;
        int at = off;
        int left = len;
        int spins = 0;
        while (left > 0 && !stopped) {
            int idx;
            try {
                idx = codec.dequeueInputBuffer(WAIT_US);
            } catch (Throwable e) {
                return;                       // 已经被 stop() 拆了
            }
            drain(false);
            if (idx < 0) {
                if (++spins > 200) return;    // 2 秒还喂不进去：这次放弃，别把线程挂死
                continue;
            }
            spins = 0;
            try {
                ByteBuffer in = codec.getInputBuffer(idx);
                if (in == null) return;
                in.clear();
                int n = Math.min(left, in.remaining());
                in.put(data, at, n);
                codec.queueInputBuffer(idx, 0, n, 0, 0);
                at += n;
                left -= n;
            } catch (Throwable e) {
                return;
            }
        }
        drain(false);
    }

    /** 这句话发完了：把尾巴解完、等真的播完再返回（不等就会把最后半秒切掉）。 */
    void finish() {
        if (stopped || codec == null) return;
        try {
            int idx = codec.dequeueInputBuffer(WAIT_US * 5);
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
        } catch (Throwable ignored) { }
        waitPlayedOut();
    }

    /** 首声延迟（毫秒；没出声返回 -1）。 */
    long firstMs() { return firstPcmAt > 0 ? firstPcmAt : -1; }

    /** 从别的线程掐断：先断扬声器，再拆解码器 —— 顺序反了会有一声爆音。 */
    void stop() {
        stopped = true;
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) { }
            try { t.flush(); } catch (Throwable ignored) { }
            try { t.stop(); } catch (Throwable ignored) { }
            try { t.release(); } catch (Throwable ignored) { }
        }
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try { c.stop(); } catch (Throwable ignored) { }
            try { c.release(); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- 内部

    private void drain(boolean untilEos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (!stopped && codec != null) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, untilEos ? WAIT_US * 2 : 0);
            } catch (Throwable e) {
                return;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
                if (++idle > 300) return;     // 6 秒等不到尾巴就当它播完了
                continue;
            }
            idle = 0;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                ensureTrack(codec.getOutputFormat());
                continue;
            }
            if (idx < 0) continue;            // INFO_OUTPUT_BUFFERS_CHANGED（老 API）
            try {
                ByteBuffer out = codec.getOutputBuffer(idx);
                if (out != null && info.size > 0) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    byte[] pcm = new byte[info.size];
                    out.get(pcm);
                    writePcm(pcm);
                }
            } catch (Throwable e) {
                if (stopped) return;
                EyeLog.w("edge", "取 PCM 失败：" + e);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            try { codec.releaseOutputBuffer(idx, false); } catch (Throwable ignored) { }
            if (eos) return;
        }
    }

    /** 第一次拿到解码器回报的真实格式时建轨道（别按 DEFAULT 猜，机型会把单声道报成双声道）。 */
    private void ensureTrack(MediaFormat fmt) {
        if (track != null || fmt == null) return;
        try {
            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        } catch (Throwable ignored) { }
        int chMask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 2, rate / 4 * 2 * Math.max(1, channels));   // ≈250ms
        try {
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, chMask,
                AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
            t.play();
            track = t;
            EyeLog.w("edge", "流式播放 " + rate + "Hz/" + channels + "ch buf=" + size);
        } catch (Throwable e) {
            EyeLog.w("edge", "建 AudioTrack 失败：" + e);
        }
    }

    private void writePcm(byte[] pcm) {
        AudioTrack t = track;
        if (t == null || pcm.length == 0) return;
        if (firstPcmAt == 0) firstPcmAt = System.currentTimeMillis() - t0;
        try {
            int n = t.write(pcm, 0, pcm.length);   // 阻塞：缓冲区满就等，天然节流
            if (n > 0) framesWritten += n / (2L * Math.max(1, channels));
        } catch (Throwable e) {
            if (!stopped) EyeLog.w("edge", "写 PCM 失败：" + e);
        }
    }

    /** 等播放头追上写入量（最多 4 秒）——不然 `stop()` 会把最后几个字切掉。 */
    private void waitPlayedOut() {
        AudioTrack t = track;
        if (t == null) return;
        long deadline = System.currentTimeMillis() + 4_000;
        while (!stopped && System.currentTimeMillis() < deadline) {
            long played;
            try { played = t.getPlaybackHeadPosition(); } catch (Throwable e) { return; }
            if (played >= framesWritten) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }
}
