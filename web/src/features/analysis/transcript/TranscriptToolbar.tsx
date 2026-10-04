/** Above the transcript: what the marks mean, and which words to show. The filter fades
 *  the other words (it never hides text) and N walks through the ones that remain. */

import { ChevronDown, Ear, Filter, Mic } from "lucide-react";
import { Button as AriaButton, MenuTrigger, type Selection } from "react-aria-components";

import { Explain } from "../../../didactic/Explain";
import { phenomenaByFrequency } from "../../../lib/analysis";
import { phenomenonLabel, useReference } from "../../../reference";
import { FamilyPreview } from "../../../shell/FamilyPreview";
import type { Analysis } from "../../../types";
import {
  buttonStyles,
  cn,
  Disclosure,
  FamilyIcon,
  familyUnderlineClass,
  Menu,
  MenuItem,
  PhenomenonIcon,
  Popover,
  PRACTICE_TEXT,
  ToggleButton,
} from "../../../ui";
import { phenomenaByPractice } from "../lib/words";

interface Props {
  analysis: Analysis;
  filter: ReadonlySet<string>;
  onSetFilter: (phenomena: string[]) => void;
}

const sameSet = (names: string[], filter: ReadonlySet<string>) =>
  names.length > 0 && names.length === filter.size && names.every((name) => filter.has(name));

export function TranscriptToolbar({ analysis, filter, onSetFilter }: Props) {
  const reference = useReference();
  const counts = phenomenaByFrequency(analysis);
  const toProduce = phenomenaByPractice(analysis, reference, "produce");
  const toRecognize = phenomenaByPractice(analysis, reference, "understand");

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-xl font-semibold text-ink">Transcript</h2>
        <div className="flex flex-wrap items-center gap-2">
          <MenuTrigger>
            <AriaButton className={buttonStyles({ variant: "secondary", size: "sm" })}>
              <Filter size={16} aria-hidden="true" />
              {filter.size > 0 ? `Changes (${filter.size})` : "Filter by change"}
              <ChevronDown size={16} aria-hidden="true" />
            </AriaButton>
            <Popover placement="bottom end">
              <Menu
                aria-label="Show only words with these changes"
                selectionMode="multiple"
                selectedKeys={new Set(filter)}
                onSelectionChange={(keys: Selection) =>
                  onSetFilter(keys === "all" ? counts.map(([name]) => name) : [...keys].map(String))
                }
              >
                {counts.map(([name, count]) => (
                  <MenuItem key={name} id={name} textValue={phenomenonLabel(reference, name)}>
                    {({ isSelected }) => (
                      <>
                        <span aria-hidden="true" className="w-4 text-center font-bold">
                          {isSelected ? "✓" : ""}
                        </span>
                        <PhenomenonIcon name={name} size={15} />
                        <span className="flex-1">{phenomenonLabel(reference, name)}</span>
                        <span className="tabular-nums text-ink-2">{count}</span>
                      </>
                    )}
                  </MenuItem>
                ))}
              </Menu>
            </Popover>
          </MenuTrigger>
          {reference.practice && (
            <>
              <ToggleButton
                size="sm"
                icon={Mic}
                isSelected={sameSet(toProduce, filter)}
                isDisabled={toProduce.length === 0}
                onChange={() => onSetFilter(sameSet(toProduce, filter) ? [] : toProduce)}
              >
                {PRACTICE_TEXT.produce}
              </ToggleButton>
              <ToggleButton
                size="sm"
                icon={Ear}
                isSelected={sameSet(toRecognize, filter)}
                isDisabled={toRecognize.length === 0}
                onChange={() => onSetFilter(sameSet(toRecognize, filter) ? [] : toRecognize)}
              >
                {PRACTICE_TEXT.understand}
              </ToggleButton>
            </>
          )}
        </div>
      </div>
      <Disclosure title={<span className="text-sm">How to read the marks</span>} defaultExpanded={false} className="border-b-0">
        <div className="flex flex-col gap-2 text-sm text-ink-2">
          <p>
            Each underline style is a <Explain term="family">family of changes</Explain>; its icon says the same thing
            without color. A dotted underline with a small label is a reduced form like “wanna”. ‿ joins words that run
            together.
          </p>
          <FamilyPreview />
          <p className="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span className="inline-flex items-center gap-1.5">
              <FamilyIcon family="lexical" size={15} />
              <span className={cn(familyUnderlineClass("lexical"), "text-ink")}>wanna</span> a contraction with its own
              spelling
            </span>
            <span className="inline-flex items-center gap-1.5">
              <FamilyIcon family="none" size={15} />
              <span className={cn(familyUnderlineClass("none"), "text-ink")}>and</span> a change outside the families,
              such as a word that disappears
            </span>
          </p>
          <p>
            A ring marks the word you are studying; a filled word is the one playing; a dashed outline means{" "}
            <Explain term="low-confidence">low confidence</Explain>; bold is the most prominent word of its phrase.
          </p>
        </div>
      </Disclosure>
    </div>
  );
}
