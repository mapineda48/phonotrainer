/** "What's this?" — a small (?) button after a label that opens an explanation in a
 *  popover. Reachable by keyboard and touch (never a title= tooltip). Content comes from
 *  the glossary, from the backend's phenomenon descriptions, or is passed inline. */

import { ArrowRight, CircleHelp } from "lucide-react";
import type { ReactNode } from "react";
import { Button as AriaButton, DialogTrigger } from "react-aria-components";

import { paths } from "../paths";
import { phenomenonDescription, phenomenonLabel, phenomenonPractice, useReference } from "../reference";
import { TextLink } from "../ui/Button";
import { PracticeBadge } from "../ui/Badges";
import { cn } from "../ui/cn";
import { PhenomenonIcon } from "../ui/Badges";
import { RichText } from "../ui/Ipa";
import { Popover, PopoverDialog } from "../ui/Overlays";
import { lookupGlossary, type GlossaryEntry } from "./glossary";

interface ExplainProps {
  /** Glossary key: "weak-form", ipaTerm("ɾ"), metricTerm("deviate")… */
  term?: string;
  /** A backend phenomenon key: explained from /api/reference (+ practice advice). */
  phenomenon?: string;
  /** Inline content instead of a glossary key. */
  entry?: GlossaryEntry;
  /** The label being explained; the (?) follows it. Omit for a bare (?) button. */
  children?: ReactNode;
  className?: string;
  /** Accessible name override for the (?) button. */
  buttonLabel?: string;
}

export function Explain({ term, phenomenon, entry, children, className, buttonLabel }: ExplainProps) {
  const reference = useReference();
  let resolved: GlossaryEntry | null = entry ?? (term ? lookupGlossary(term) : null);
  let practiceNode: ReactNode = null;
  let familyNode: ReactNode = null;

  if (!resolved && phenomenon) {
    const description = phenomenonDescription(reference, phenomenon);
    resolved = {
      title: phenomenonLabel(reference, phenomenon),
      body: description || "No description available.",
      learnMore: { href: paths.phenomenon(phenomenon), label: "More examples in Learn" },
    };
    const practice = phenomenonPractice(reference, phenomenon);
    practiceNode = practice ? (
      <div className="flex flex-col gap-1">
        <PracticeBadge practice={practice} showRegister />
        {practice.why && <p className="text-sm text-ink-2">{practice.why}</p>}
      </div>
    ) : null;
    familyNode = <PhenomenonIcon name={phenomenon} size={18} />;
  }

  if (!resolved) return <>{children}</>;

  return (
    <span className={cn("inline-flex items-baseline gap-0.5", className)}>
      {children}
      <DialogTrigger>
        <AriaButton
          aria-label={buttonLabel ?? `What's this: ${resolved.title}`}
          className="relative top-[0.15em] inline-flex size-6 shrink-0 items-center justify-center rounded-full text-ink-2 outline-none hover:bg-surface-2 hover:text-ink"
        >
          <CircleHelp size={15} aria-hidden="true" />
        </AriaButton>
        <Popover placement="bottom start">
          <PopoverDialog
            ariaLabel={resolved.title}
            title={
              <span className="inline-flex items-center gap-2">
                {familyNode}
                {resolved.title}
              </span>
            }
          >
            <p className="text-sm text-ink-2">
              <RichText text={resolved.body} />
            </p>
            {resolved.example && (
              <p className="text-sm text-ink">
                <RichText text={resolved.example} />
              </p>
            )}
            {practiceNode}
            {resolved.learnMore && (
              <TextLink href={resolved.learnMore.href} className="inline-flex w-fit items-center gap-1 text-sm">
                {resolved.learnMore.label ?? "Learn more"}
                <ArrowRight size={14} aria-hidden="true" />
              </TextLink>
            )}
          </PopoverDialog>
        </Popover>
      </DialogTrigger>
    </span>
  );
}
