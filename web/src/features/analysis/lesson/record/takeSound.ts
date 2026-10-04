/** Playback of the learner's take, held in memory only (a Blob; never written anywhere).
 *
 *  With Web Audio the take is decoded once and a span of it plays through a gain node,
 *  which can match its loudness to the clip's (an <audio> element can only turn it
 *  down) and does not depend on seeking inside a MediaRecorder file, which often has no
 *  index. Without Web Audio it falls back to an <audio> element. */

import { Clock } from "../../../../player/clock";

type AudioContextClass = typeof AudioContext;

function audioContextClass(): AudioContextClass | null {
  const w = window as unknown as { AudioContext?: AudioContextClass; webkitAudioContext?: AudioContextClass };
  return w.AudioContext ?? w.webkitAudioContext ?? null;
}

export interface Range {
  start: number;
  end: number;
}

export class TakeSound {
  /** Seconds into the take while it plays (the chart's playhead). */
  readonly clock = new Clock();
  readonly url: string | null;
  private ctx: AudioContext | null = null;
  private decoded: Promise<AudioBuffer | null> | null = null;
  private source: AudioBufferSourceNode | null = null;
  private audio: HTMLAudioElement | null = null;
  private timer: number | null = null;
  private frame: number | null = null;
  private onEnd: (() => void) | null = null;
  private token = 0;

  constructor(
    readonly blob: Blob,
    /** Length of the recording in seconds, as the recorder measured it. */
    readonly seconds: number,
  ) {
    this.url = typeof URL.createObjectURL === "function" ? URL.createObjectURL(blob) : null;
  }

  /** Play [range.start, range.end] of the take, `gainDb` louder (or quieter); `onEnd`
   *  runs when it finishes by itself, not when stopped. */
  play(range: Range, gainDb: number, onEnd: () => void): void {
    this.halt();
    this.onEnd = onEnd;
    const Ctx = audioContextClass();
    if (Ctx) void this.playDecoded(Ctx, range, gainDb, this.token);
    else this.playElement(range, gainDb);
  }

  stop(): void {
    this.onEnd = null;
    this.halt();
  }

  /** Free everything: the decoded audio, the context and the object URL. */
  dispose(): void {
    this.stop();
    void this.ctx?.close().catch(() => undefined);
    this.ctx = null;
    this.decoded = null;
    if (this.url && typeof URL.revokeObjectURL === "function") URL.revokeObjectURL(this.url);
  }

  private async playDecoded(Ctx: AudioContextClass, range: Range, gainDb: number, token: number) {
    if (!this.ctx) this.ctx = new Ctx();
    const ctx = this.ctx;
    if (!this.decoded) {
      this.decoded = this.blob
        .arrayBuffer()
        .then((data) => ctx.decodeAudioData(data))
        .catch(() => null);
    }
    const buffer = await this.decoded;
    if (token !== this.token) return; // stopped while decoding
    if (!buffer) {
      this.playElement(range, gainDb);
      return;
    }
    if (ctx.state === "suspended") await ctx.resume().catch(() => undefined);
    if (token !== this.token) return;
    const start = Math.max(0, Math.min(range.start, buffer.duration));
    const length = Math.max(0, Math.min(range.end, buffer.duration) - start);
    const source = ctx.createBufferSource();
    source.buffer = buffer;
    const gain = ctx.createGain();
    gain.gain.value = 10 ** (gainDb / 20);
    source.connect(gain).connect(ctx.destination);
    source.onended = () => {
      if (this.source === source) this.finish();
    };
    const t0 = ctx.currentTime;
    source.start(0, start, length);
    this.source = source;
    this.follow(() => start + (ctx.currentTime - t0));
  }

  private playElement(range: Range, gainDb: number) {
    if (!this.url) {
      this.finish();
      return;
    }
    if (!this.audio) this.audio = new Audio(this.url);
    const audio = this.audio;
    audio.volume = Math.max(0, Math.min(1, 10 ** (gainDb / 20)));
    try {
      audio.currentTime = range.start;
    } catch {
      /* not seekable yet: it plays from the start */
    }
    try {
      void (audio.play() as Promise<void> | undefined)?.catch(() => this.finish());
    } catch {
      this.finish();
      return;
    }
    this.timer = window.setTimeout(() => this.finish(), Math.max(0, range.end - range.start) * 1000);
    this.follow(() => audio.currentTime);
  }

  private follow(now: () => number) {
    if (typeof window.requestAnimationFrame !== "function") return;
    const tick = () => {
      this.clock.set(now());
      this.frame = window.requestAnimationFrame(tick);
    };
    tick();
  }

  private finish() {
    const callback = this.onEnd;
    this.onEnd = null;
    this.halt();
    callback?.();
  }

  private halt() {
    this.token += 1;
    if (this.timer !== null) window.clearTimeout(this.timer);
    this.timer = null;
    if (this.frame !== null && typeof window.cancelAnimationFrame === "function") {
      window.cancelAnimationFrame(this.frame);
    }
    this.frame = null;
    const source = this.source;
    this.source = null;
    if (source) {
      try {
        source.stop();
      } catch {
        /* already stopped */
      }
    }
    this.audio?.pause();
  }
}
