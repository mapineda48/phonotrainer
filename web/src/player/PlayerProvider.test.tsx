/** The player is the most delicate piece of the frontend: here it is exercised
 *  for real (the component tests use a double). */

import { render, screen } from "@testing-library/react";
import { act } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { PlayerProvider, usePlayer, type PlayerApi } from "./PlayerProvider";

let frames: FrameRequestCallback[] = [];

function tick() {
  const pending = frames;
  frames = [];
  act(() => pending.forEach((frame) => frame(0)));
}

let api: PlayerApi;

function Probe() {
  api = usePlayer();
  return null;
}

function mount(src: string | null = "/api/jobs/x/audio", sourceKey?: string) {
  const view = render(
    <PlayerProvider src={src} sourceKey={sourceKey}>
      <Probe />
    </PlayerProvider>,
  );
  const audio = screen.getByTestId("player-audio") as HTMLAudioElement;
  // jsdom does not implement playback: here goes a fake <audio> that does keep
  // currentTime/paused and emits the events, which is what the hook lives on.
  let time = 0;
  let paused = true;
  Object.defineProperty(audio, "currentTime", {
    configurable: true,
    get: () => time,
    set: (value: number) => {
      time = value;
    },
  });
  Object.defineProperty(audio, "paused", { configurable: true, get: () => paused });
  const pause = vi.fn(() => {
    paused = true;
    audio.dispatchEvent(new Event("pause"));
  });
  Object.defineProperty(audio, "pause", { configurable: true, value: pause });
  const play = vi.fn(() => {
    paused = false;
    audio.dispatchEvent(new Event("play"));
    return Promise.resolve();
  });
  Object.defineProperty(audio, "play", { configurable: true, value: play });
  return { audio, play, pause, setTime: (value: number) => (audio.currentTime = value), view };
}

beforeEach(() => {
  frames = [];
  vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => frames.push(cb));
  vi.stubGlobal("cancelAnimationFrame", () => undefined);
});

describe("PlayerProvider", () => {
  it("playing a span jumps to its start", () => {
    const { audio, play } = mount();
    act(() => api.play({ start: 1.5, end: 2 }));

    expect(audio.currentTime).toBe(1.5);
    expect(play).toHaveBeenCalled();
    expect(api.span).toEqual({ start: 1.5, end: 2 });
  });

  it("stops on reaching the end of the span", () => {
    const { pause, setTime } = mount();
    act(() => api.play({ start: 1, end: 2 }));

    setTime(1.4);
    tick();
    expect(pause).not.toHaveBeenCalled();
    expect(api.clock.getSnapshot()).toBe(1.4);

    setTime(2.01);
    tick();
    expect(pause).toHaveBeenCalled();
  });

  it("in loop mode it returns to the span start instead of stopping", () => {
    const { audio, pause, setTime } = mount();
    act(() => api.setLoop(true));
    act(() => api.play({ start: 1, end: 2 }));

    setTime(2.01);
    tick();

    expect(pause).not.toHaveBeenCalled();
    expect(audio.currentTime).toBe(1);
    expect(api.clock.getSnapshot()).toBe(1);
  });

  it("publishes the time on the clock while sounding", () => {
    const { setTime } = mount();
    act(() => api.play(null));

    setTime(0.3);
    tick();
    setTime(0.7);
    tick();

    expect(api.clock.getSnapshot()).toBe(0.7);
  });

  it("the rate is applied to the audio element", () => {
    const { audio } = mount();
    act(() => api.setRate(0.5));
    expect(audio.playbackRate).toBe(0.5);
    expect(api.rate).toBe(0.5);
  });

  it("does not rewind on mount: whoever arrives asking for a word must hear it", () => {
    const { audio } = mount();
    act(() => api.play({ start: 1.98, end: 2.2 }));

    expect(audio.currentTime).toBe(1.98);
    expect(audio.paused).toBe(false);
    expect(api.span).toEqual({ start: 1.98, end: 2.2 });
  });

  it("switching analyses rewinds and releases the span", () => {
    const { audio, view, setTime } = mount();
    act(() => api.play({ start: 1, end: 2 }));
    setTime(1.5);
    tick();

    view.rerender(
      <PlayerProvider src="/api/jobs/other/audio">
        <Probe />
      </PlayerProvider>,
    );

    expect(api.span).toBeNull();
    expect(api.clock.getSnapshot()).toBe(0);
    expect(audio.currentTime).toBe(0);
  });

  it("toggle on the same span pauses; on a different one it jumps", () => {
    const { play, pause } = mount();
    act(() => api.play({ start: 1, end: 2 }));
    play.mockClear();

    act(() => api.toggle({ start: 1, end: 2 }));   // the same one: pause
    expect(pause).toHaveBeenCalled();

    act(() => api.toggle({ start: 5, end: 6 }));   // a different one: jump and play
    expect(play).toHaveBeenCalled();
    expect(api.span).toEqual({ start: 5, end: 6 });
  });

  it("another track of the same material keeps the place and keeps playing", () => {
    const { audio, view, play, setTime } = mount("/api/jobs/x/audio", "x");
    act(() => api.play({ start: 1, end: 3 }));
    setTime(1.7);
    tick();

    view.rerender(
      <PlayerProvider src="/api/jobs/x/audio?track=dialogue" sourceKey="x">
        <Probe />
      </PlayerProvider>,
    );
    // the new source rewinds the element; the provider puts it back on load
    setTime(0);
    play.mockClear();
    act(() => {
      audio.dispatchEvent(new Event("loadedmetadata"));
    });

    expect(audio.currentTime).toBe(1.7);
    expect(api.clock.getSnapshot()).toBe(1.7);
    expect(play).toHaveBeenCalled();
    expect(api.span).toEqual({ start: 1, end: 3 });
  });

  it("a different material still rewinds even with a track chosen", () => {
    const { audio, view, setTime } = mount("/api/jobs/x/audio", "x");
    act(() => api.play({ start: 1, end: 2 }));
    setTime(1.5);
    tick();

    view.rerender(
      <PlayerProvider src="/api/jobs/y/audio?track=dialogue" sourceKey="y">
        <Probe />
      </PlayerProvider>,
    );

    expect(api.span).toBeNull();
    expect(audio.currentTime).toBe(0);
  });
});
