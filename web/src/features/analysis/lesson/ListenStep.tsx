/** Lesson step 1 — Listen: the word, the word with its neighbor (when the change
 *  happens across the boundary) and the whole phrase, at any speed, looped if wanted. */

import { Play } from "lucide-react";

import { wordSpan } from "../../../lib/analysis";
import { usePlayer } from "../../../player/PlayerProvider";
import type { Segment, Word } from "../../../types";
import { Button, ButtonRow, Kbd, Switch } from "../../../ui";
import { SpeedControl } from "../player/PlayerBar";

interface Props {
  word: Word;
  /** The next word, when the phenomenon crosses the boundary; null otherwise. */
  next: Word | null;
  segment: Segment;
  canPlay: boolean;
}

function Keys({ children }: { children: React.ReactNode }) {
  return (
    <span aria-hidden="true" className="inline-flex gap-0.5">
      {children}
    </span>
  );
}

export function ListenStep({ word, next, segment, canPlay }: Props) {
  const player = usePlayer();
  const span = wordSpan(word);
  return (
    <div className="flex flex-col gap-4">
      <ButtonRow>
        <Button icon={Play} isDisabled={!canPlay} onPress={() => player.play(span)}>
          Play the word
          <Keys>
            <Kbd>P</Kbd>
          </Keys>
        </Button>
        {next && (
          <Button
            icon={Play}
            isDisabled={!canPlay}
            onPress={() => player.play({ start: span.start, end: wordSpan(next).end })}
          >
            Play with “{next.word}”
            <Keys>
              <Kbd>Shift</Kbd>
              <Kbd>P</Kbd>
            </Keys>
          </Button>
        )}
        <Button
          icon={Play}
          isDisabled={!canPlay}
          onPress={() => player.play({ start: segment.start, end: segment.end })}
        >
          Play the phrase
          <Keys>
            <Kbd>S</Kbd>
          </Keys>
        </Button>
      </ButtonRow>
      {next && (
        <p className="text-sm text-ink-2">
          This change happens between “{word.word}” and “{next.word}”: listen to them together.
        </p>
      )}
      {!canPlay && (
        <p className="text-sm text-ink-2">
          This analysis was imported without its audio: you can read it, but not listen to it.
        </p>
      )}
      <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
        <SpeedControl />
        <Switch isSelected={player.loop} onChange={(value) => player.setLoop(value)} isDisabled={!canPlay}>
          Loop: repeat what you play
        </Switch>
      </div>
    </div>
  );
}
