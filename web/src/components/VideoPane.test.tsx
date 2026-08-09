import { act } from "react";
import { describe, expect, it, vi } from "vitest";

import { fakePlayer, renderWith } from "../test/fixtures";
import { VideoPane } from "./VideoPane";

const video = (): HTMLVideoElement => document.querySelector("video")!;

describe("VideoPane", () => {
  it("va mudo y sigue la velocidad del reproductor", () => {
    const player = fakePlayer({ rate: 0.5 });
    renderWith(<VideoPane src="/x.mp4" />, { player });

    expect(video().muted).toBe(true);
    expect(video().playbackRate).toBe(0.5);
  });

  it("reproduce y pausa a la orden del reproductor", () => {
    const playSpy = vi.fn(() => Promise.resolve());
    const pauseSpy = vi.fn(() => undefined);
    Object.defineProperty(window.HTMLMediaElement.prototype, "play", {
      configurable: true,
      value: playSpy,
    });
    Object.defineProperty(window.HTMLMediaElement.prototype, "pause", {
      configurable: true,
      value: pauseSpy,
    });

    const player = fakePlayer({ playing: false });
    const tools = renderWith(<VideoPane src="/x.mp4" />, { player });
    expect(pauseSpy).toHaveBeenCalled();

    player.playing = true;
    tools.rerender(<VideoPane src="/x.mp4" />);
    expect(playSpy).toHaveBeenCalled();
  });

  it("corrige la posición solo si se desfasa más de 200 ms", () => {
    const player = fakePlayer();
    renderWith(<VideoPane src="/x.mp4" />, { player });

    act(() => player.clock.set(5.9));
    expect(video().currentTime).toBeCloseTo(5.9);

    act(() => player.clock.set(6.05)); // deriva de 150 ms: dentro de la tolerancia
    expect(video().currentTime).toBeCloseTo(5.9);

    act(() => player.clock.set(6.5)); // deriva de 600 ms: se corrige
    expect(video().currentTime).toBeCloseTo(6.5);
  });
});
