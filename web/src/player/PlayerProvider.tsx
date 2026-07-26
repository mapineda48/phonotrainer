/** Reproductor central: un solo <audio> gobierna toda la app.
 *
 *  Reproduce el WAV que analizó el pipeline (no el video), así lo que se oye es
 *  exactamente aquello sobre lo que se calcularon los tiempos. Sabe reproducir
 *  un *span* (palabra, fono o segmento) y repetirlo en bucle, que es el gesto
 *  que más se repite estudiando pronunciación.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { Clock } from "./clock";

export interface Span {
  start: number;
  end: number;
}

export interface PlayerApi {
  clock: Clock;
  playing: boolean;
  rate: number;
  loop: boolean;
  span: Span | null;
  duration: number;
  /** Reproduce un fragmento (o todo, si `span` es null). */
  play: (span?: Span | null) => void;
  pause: () => void;
  /** Play/pausa; si se pasa un span distinto del actual, salta a él. */
  toggle: (span?: Span | null) => void;
  seek: (time: number) => void;
  setRate: (rate: number) => void;
  setLoop: (loop: boolean) => void;
  /** Deja de acotar la reproducción a un fragmento. */
  clearSpan: () => void;
}

const PlayerContext = createContext<PlayerApi | null>(null);

export function usePlayer(): PlayerApi {
  const api = useContext(PlayerContext);
  if (!api) throw new Error("usePlayer() necesita un <PlayerProvider>");
  return api;
}

/** Para tests: inyecta un reproductor falso sin tocar el DOM de audio. */
export const PlayerContextProvider = PlayerContext.Provider;

const SPAN_EPSILON = 0.015; // s de margen al comparar con el final del fragmento

export function PlayerProvider({ src, children }: { src: string | null; children: ReactNode }) {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const clock = useMemo(() => new Clock(), []);
  const spanRef = useRef<Span | null>(null);
  const loopRef = useRef(false);
  const frameRef = useRef<number | null>(null);

  const [playing, setPlaying] = useState(false);
  const [rate, setRateState] = useState(1);
  const [loop, setLoopState] = useState(false);
  const [span, setSpanState] = useState<Span | null>(null);
  const [duration, setDuration] = useState(0);

  const setSpan = useCallback((next: Span | null) => {
    spanRef.current = next;
    setSpanState(next);
  }, []);

  const pause = useCallback(() => {
    audioRef.current?.pause();
  }, []);

  const startPlayback = useCallback(() => {
    const audio = audioRef.current;
    if (!audio) return;
    try {
      const promise = audio.play() as Promise<void> | undefined;
      promise?.catch(() => setPlaying(false));
    } catch {
      setPlaying(false); // jsdom y navegadores que bloquean el autoplay
    }
  }, []);

  const seek = useCallback(
    (time: number) => {
      const audio = audioRef.current;
      if (!audio) return;
      audio.currentTime = Math.max(0, time);
      clock.set(audio.currentTime);
    },
    [clock],
  );

  const play = useCallback(
    (next?: Span | null) => {
      const target = next === undefined ? spanRef.current : next;
      setSpan(target ?? null);
      if (target) seek(target.start);
      startPlayback();
    },
    [seek, setSpan, startPlayback],
  );

  const toggle = useCallback(
    (next?: Span | null) => {
      const audio = audioRef.current;
      const current = spanRef.current;
      const sameSpan =
        next == null || (current && current.start === next.start && current.end === next.end);
      if (audio && !audio.paused && sameSpan) {
        pause();
        return;
      }
      play(next);
    },
    [pause, play],
  );

  const setRate = useCallback((value: number) => {
    setRateState(value);
    if (audioRef.current) audioRef.current.playbackRate = value;
  }, []);

  const setLoop = useCallback((value: boolean) => {
    loopRef.current = value;
    setLoopState(value);
  }, []);

  const clearSpan = useCallback(() => setSpan(null), [setSpan]);

  // Bucle de animación: publica el tiempo y hace respetar el fragmento activo.
  useEffect(() => {
    if (!playing) return;
    const tick = () => {
      const audio = audioRef.current;
      if (audio) {
        const now = audio.currentTime;
        const active = spanRef.current;
        if (active && now >= active.end - SPAN_EPSILON) {
          if (loopRef.current) {
            audio.currentTime = active.start;
            clock.set(active.start);
          } else {
            audio.pause();
            clock.set(active.end);
          }
        } else {
          clock.set(now);
        }
      }
      frameRef.current = requestAnimationFrame(tick);
    };
    frameRef.current = requestAnimationFrame(tick);
    return () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
    };
  }, [playing, clock]);

  // Al CAMBIAR de análisis: paramos y volvemos al principio. En el montaje no:
  // si no, este efecto (del padre) pisaba al que abre una palabra concreta al
  // entrar desde el corpus, y la palabra quedaba seleccionada pero muda.
  const previousSrc = useRef<string | null | undefined>(undefined);
  useEffect(() => {
    if (previousSrc.current === undefined || previousSrc.current === src) {
      previousSrc.current = src;
      return;
    }
    previousSrc.current = src;
    setSpan(null);
    setPlaying(false);
    clock.set(0);
    const audio = audioRef.current;
    if (audio) {
      audio.pause();
      audio.currentTime = 0;
    }
  }, [src, clock, setSpan]);

  const api = useMemo<PlayerApi>(
    () => ({
      clock,
      playing,
      rate,
      loop,
      span,
      duration,
      play,
      pause,
      toggle,
      seek,
      setRate,
      setLoop,
      clearSpan,
    }),
    [clock, playing, rate, loop, span, duration, play, pause, toggle, seek, setRate, setLoop, clearSpan],
  );

  return (
    <PlayerContext.Provider value={api}>
      <audio
        ref={audioRef}
        src={src ?? undefined}
        preload="auto"
        data-testid="player-audio"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onLoadedMetadata={(event) => {
          const audio = event.currentTarget;
          audio.playbackRate = rate;
          setDuration(Number.isFinite(audio.duration) ? audio.duration : 0);
        }}
        onSeeked={(event) => clock.set(event.currentTarget.currentTime)}
      />
      {children}
    </PlayerContext.Provider>
  );
}
