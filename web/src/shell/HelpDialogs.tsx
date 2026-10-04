/** Help → Keyboard shortcuts and Help → About the engines. */

import { Fragment } from "react";

import { useReference } from "../reference";
import { AppDialog, Kbd } from "../ui";
import { SHORTCUT_GROUPS } from "./shortcuts";

interface DialogState {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
}

export function ShortcutsDialog({ isOpen, onOpenChange }: DialogState) {
  return (
    <AppDialog title="Keyboard shortcuts" isOpen={isOpen} onOpenChange={onOpenChange} size="md">
      <div className="flex flex-col gap-5">
        {SHORTCUT_GROUPS.map((group) => (
          <section key={group.title} className="flex flex-col gap-2">
            <h3 className="text-sm font-semibold text-ink">{group.title}</h3>
            <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-2 text-sm">
              {group.shortcuts.map((shortcut) => (
                <Fragment key={shortcut.action}>
                  <dt className="flex flex-wrap items-center gap-1">
                    {shortcut.keys.map((key, i) => (
                      <Fragment key={key}>
                        {i > 0 && (
                          <span className="text-ink-muted">{shortcut.combo ? "+" : "or"}</span>
                        )}
                        <Kbd>{key}</Kbd>
                      </Fragment>
                    ))}
                  </dt>
                  <dd className="m-0 text-ink-2">{shortcut.action}</dd>
                </Fragment>
              ))}
            </dl>
          </section>
        ))}
      </div>
    </AppDialog>
  );
}

const ENGINE_TITLES: Record<string, string> = {
  timit61: "TIMIT-61 (default)",
  espeak: "espeak",
};

export function AboutEnginesDialog({ isOpen, onOpenChange }: DialogState) {
  const reference = useReference();
  const notes = reference.options.phone_engine_notes ?? {};
  return (
    <AppDialog title="About the phone engines" isOpen={isOpen} onOpenChange={onOpenChange} size="md">
      <div className="flex flex-col gap-4">
        <p>
          The phone engine listens to the audio and writes down the sounds it hears. Each analysis
          says which engine produced it, and the figures of different engines are never mixed.
        </p>
        {reference.options.phone_engines.map((engine) => (
          <section key={engine} className="flex flex-col gap-1">
            <h3 className="text-base font-semibold text-ink">{ENGINE_TITLES[engine] ?? engine}</h3>
            {notes[engine] && <p className="text-sm">{notes[engine]}</p>}
          </section>
        ))}
      </div>
    </AppDialog>
  );
}
