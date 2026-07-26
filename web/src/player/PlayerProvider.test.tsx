/** El reproductor es la pieza más delicada del frontend: aquí se ejerce de
 *  verdad (los tests de componentes usan un doble). */

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

function mount(src: string | null = "/api/jobs/x/audio") {
  const view = render(
    <PlayerProvider src={src}>
      <Probe />
    </PlayerProvider>,
  );
  const audio = screen.getByTestId("player-audio") as HTMLAudioElement;
  // jsdom no implementa la reproducción: aquí va un <audio> de mentira que sí
  // mantiene currentTime/paused y emite los eventos, que es de lo que vive el hook.
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
  it("reproducir un fragmento salta a su inicio", () => {
    const { audio, play } = mount();
    act(() => api.play({ start: 1.5, end: 2 }));

    expect(audio.currentTime).toBe(1.5);
    expect(play).toHaveBeenCalled();
    expect(api.span).toEqual({ start: 1.5, end: 2 });
  });

  it("para al llegar al final del fragmento", () => {
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

  it("en bucle vuelve al principio del fragmento en vez de parar", () => {
    const { audio, pause, setTime } = mount();
    act(() => api.setLoop(true));
    act(() => api.play({ start: 1, end: 2 }));

    setTime(2.01);
    tick();

    expect(pause).not.toHaveBeenCalled();
    expect(audio.currentTime).toBe(1);
    expect(api.clock.getSnapshot()).toBe(1);
  });

  it("publica el tiempo en el reloj mientras suena", () => {
    const { setTime } = mount();
    act(() => api.play(null));

    setTime(0.3);
    tick();
    setTime(0.7);
    tick();

    expect(api.clock.getSnapshot()).toBe(0.7);
  });

  it("la velocidad se aplica al elemento de audio", () => {
    const { audio } = mount();
    act(() => api.setRate(0.5));
    expect(audio.playbackRate).toBe(0.5);
    expect(api.rate).toBe(0.5);
  });

  it("cambiar de análisis rebobina y suelta el fragmento", () => {
    const { audio, view, setTime } = mount();
    act(() => api.play({ start: 1, end: 2 }));
    setTime(1.5);
    tick();

    view.rerender(
      <PlayerProvider src="/api/jobs/otro/audio">
        <Probe />
      </PlayerProvider>,
    );

    expect(api.span).toBeNull();
    expect(api.clock.getSnapshot()).toBe(0);
    expect(audio.currentTime).toBe(0);
  });

  it("toggle sobre el mismo fragmento pausa; sobre otro salta", () => {
    const { play, pause } = mount();
    act(() => api.play({ start: 1, end: 2 }));
    play.mockClear();

    act(() => api.toggle({ start: 1, end: 2 }));   // el mismo: pausa
    expect(pause).toHaveBeenCalled();

    act(() => api.toggle({ start: 5, end: 6 }));   // otro: salta y reproduce
    expect(play).toHaveBeenCalled();
    expect(api.span).toEqual({ start: 5, end: 6 });
  });
});
