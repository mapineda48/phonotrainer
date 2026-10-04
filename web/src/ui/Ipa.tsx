/** IPA text. Always rendered in Charis through these components: per-glyph font
 *  fallback misplaces the combining marks (t̚, ɾ̃, n̩), which is exactly what a learner
 *  is trying to read. */

import { Fragment, type ReactNode } from "react";

import { cn } from "./cn";

interface IpaProps {
  children: ReactNode;
  /** "phonemic" wraps in /…/ (dictionary form), "phonetic" in […] (what was said). */
  kind?: "phonemic" | "phonetic" | "plain";
  className?: string;
}

export function Ipa({ children, kind = "plain", className }: IpaProps) {
  const [open, close] = kind === "phonemic" ? ["/", "/"] : kind === "phonetic" ? ["[", "]"] : ["", ""];
  return (
    <span lang="und-fonipa" className={cn("ipa-text", className)}>
      {open}
      {children}
      {close}
    </span>
  );
}

/** Spans of `text` that are IPA: `/…/`, `[…]`, and whatever follows the last "→"
 *  (the backend's descriptions end with "word → ipa"). */
export function splitIpa(text: string): { text: string; ipa: boolean }[] {
  const parts: { text: string; ipa: boolean }[] = [];
  let body = text;
  let tail: string | null = null;
  const arrow = text.lastIndexOf("→");
  if (arrow >= 0) {
    body = text.slice(0, arrow + 1);
    tail = text.slice(arrow + 1);
  }
  const pattern = /(\/[^/\s][^/]*\/|\[[^\]\s][^\]]*\])/g;
  let last = 0;
  for (const match of body.matchAll(pattern)) {
    const at = match.index ?? 0;
    if (at > last) parts.push({ text: body.slice(last, at), ipa: false });
    parts.push({ text: match[0], ipa: true });
    last = at + match[0].length;
  }
  if (last < body.length) parts.push({ text: body.slice(last), ipa: false });
  if (tail !== null) {
    const lead = tail.match(/^\s*/)?.[0] ?? "";
    const ipa = tail.slice(lead.length).trimEnd();
    if (lead) parts.push({ text: lead, ipa: false });
    if (ipa) parts.push({ text: ipa, ipa: true });
  }
  return parts;
}

/** Plain text from the backend (phenomenon descriptions, glossary bodies) with its IPA
 *  parts set in the IPA font. */
export function RichText({ text, className }: { text: string; className?: string }) {
  return (
    <span className={className}>
      {splitIpa(text).map((part, i) =>
        part.ipa ? (
          <Ipa key={i}>{part.text}</Ipa>
        ) : (
          <Fragment key={i}>{part.text}</Fragment>
        ),
      )}
    </span>
  );
}
