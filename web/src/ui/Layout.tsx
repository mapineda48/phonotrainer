/** Layout and text primitives: page header, card, chip, key cap, empty state, notice,
 *  progress bar, tabs and disclosure (the lesson steps). */

import { ChevronRight, Info, TriangleAlert, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import {
  Button as AriaButton,
  Disclosure as AriaDisclosure,
  DisclosurePanel,
  Heading,
  Label,
  ProgressBar as AriaProgressBar,
  Tab as AriaTab,
  TabList as AriaTabList,
  TabPanel as AriaTabPanel,
  Tabs as AriaTabs,
  type TabListProps,
  type TabPanelProps,
  type TabProps,
  type TabsProps,
} from "react-aria-components";

import { cn, composeClass } from "./cn";

/* ---- PageHeader: title + one line of "what you can do here" ------------------------ */

interface PageHeaderProps {
  title: ReactNode;
  /** One sentence that teaches what this page is for. */
  lede?: ReactNode;
  actions?: ReactNode;
  /** Something above the title (a back link, a breadcrumb). */
  eyebrow?: ReactNode;
  className?: string;
}

export function PageHeader({ title, lede, actions, eyebrow, className }: PageHeaderProps) {
  return (
    <header className={cn("flex flex-wrap items-start justify-between gap-4 pb-5", className)}>
      <div className="flex min-w-0 flex-col gap-1">
        {eyebrow}
        <h1 className="text-3xl font-bold tracking-tight text-ink">{title}</h1>
        {lede && <p className="max-w-[65ch] text-base text-ink-2">{lede}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

/* ---- Card -------------------------------------------------------------------------- */

interface CardProps {
  children: ReactNode;
  title?: ReactNode;
  /** Short sentence under the title. */
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
  /** Heading level for the title (default h2). */
  level?: 2 | 3 | 4;
  as?: "section" | "div" | "article";
  tourId?: string;
}

export function Card({
  children,
  title,
  description,
  actions,
  className,
  level = 2,
  as: Tag = "section",
  tourId,
}: CardProps) {
  const H = `h${level}` as "h2" | "h3" | "h4";
  return (
    <Tag className={cn("rounded-card bg-surface p-4 shadow-1", className)} data-tour={tourId}>
      {(title || actions) && (
        <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
          <div className="flex min-w-0 flex-col gap-0.5">
            {title && <H className="text-lg font-semibold text-ink">{title}</H>}
            {description && <p className="text-sm text-ink-2">{description}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
    </Tag>
  );
}

/* ---- Chip (static label) ------------------------------------------------------------ */

interface ChipProps {
  children: ReactNode;
  icon?: ReactNode;
  className?: string;
  /** "outline" (default) or "tint" (a family tint behind ink text, set via style). */
  tone?: "outline" | "solid" | "muted";
  style?: React.CSSProperties;
  title?: string;
}

export function Chip({ children, icon, className, tone = "outline", style }: ChipProps) {
  return (
    <span
      style={style}
      className={cn(
        "inline-flex min-h-7 items-center gap-1.5 rounded-chip px-2.5 py-0.5 text-xs font-medium",
        tone === "outline" && "bg-surface text-ink shadow-[inset_0_0_0_1px_var(--line-strong)]",
        tone === "solid" && "bg-ink text-page",
        tone === "muted" && "bg-surface-2 text-ink-2",
        className,
      )}
    >
      {icon}
      {children}
    </span>
  );
}

/* ---- Kbd ------------------------------------------------------------------------------ */

export function Kbd({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <kbd
      className={cn(
        "inline-flex min-w-6 items-center justify-center rounded-[4px] bg-surface-2 px-1.5 font-mono text-xs text-ink shadow-[inset_0_-1px_0_var(--line-strong),inset_0_0_0_1px_var(--line)]",
        className,
      )}
    >
      {children}
    </kbd>
  );
}

/* ---- EmptyState: teaching copy + the next action ------------------------------------- */

interface EmptyStateProps {
  icon?: LucideIcon;
  title: ReactNode;
  children?: ReactNode;
  actions?: ReactNode;
  className?: string;
  /** 1 when the empty state IS the page (not found), so the page still has its h1. */
  level?: 1 | 2 | 3;
}

export function EmptyState({ icon: Icon, title, children, actions, className, level = 2 }: EmptyStateProps) {
  const Title = `h${level}` as const;
  return (
    <div
      className={cn(
        "mx-auto flex max-w-xl flex-col items-center gap-3 rounded-card px-6 py-10 text-center",
        className,
      )}
    >
      {Icon && (
        <span className="flex size-14 items-center justify-center rounded-full bg-surface-2 text-ink-2">
          <Icon size={28} aria-hidden="true" />
        </span>
      )}
      <Title className="text-xl font-semibold text-ink">{title}</Title>
      {children && <div className="text-base text-ink-2">{children}</div>}
      {actions && <div className="mt-2 flex flex-wrap justify-center gap-2">{actions}</div>}
    </div>
  );
}

/* ---- Notice: a calm message with an icon (never color alone) -------------------------- */

interface NoticeProps {
  children: ReactNode;
  title?: ReactNode;
  tone?: "info" | "caution";
  className?: string;
  /** Announce politely to screen readers when it appears. */
  live?: boolean;
}

export function Notice({ children, title, tone = "info", className, live }: NoticeProps) {
  const Icon = tone === "caution" ? TriangleAlert : Info;
  return (
    <div
      role={live ? "status" : undefined}
      className={cn(
        "flex gap-3 rounded-card bg-surface-2 p-3 text-sm text-ink",
        tone === "caution" && "shadow-[inset_3px_0_0_var(--ink)]",
        className,
      )}
    >
      <Icon size={18} aria-hidden="true" className="mt-0.5 shrink-0 text-ink-2" />
      <div className="flex flex-col gap-0.5">
        {title && <p className="font-semibold">{title}</p>}
        <div className="text-ink-2">{children}</div>
      </div>
    </div>
  );
}

/* ---- ProgressBar ------------------------------------------------------------------------ */

interface ProgressProps {
  label: string;
  /** 0–100; null = indeterminate. */
  value: number | null;
  /** Visible text after the bar ("62 % · separating dialogue…"). */
  valueText?: string;
  hideLabel?: boolean;
  className?: string;
}

export function ProgressBar({ label, value, valueText, hideLabel, className }: ProgressProps) {
  return (
    <AriaProgressBar
      value={value ?? undefined}
      isIndeterminate={value === null}
      valueLabel={valueText}
      className={cn("flex flex-col gap-1.5", className)}
    >
      {({ percentage, valueText: text, isIndeterminate }) => (
        <>
          <div className="flex justify-between gap-2 text-sm">
            <Label className={hideLabel ? "sr-only" : "font-medium text-ink"}>{label}</Label>
            <span className="tabular-nums text-ink-2">{text}</span>
          </div>
          <div className="h-2 overflow-hidden rounded-chip bg-surface-2 shadow-[inset_0_0_0_1px_var(--line)]">
            <div
              className={cn(
                "h-full rounded-chip bg-ink transition-[width] duration-(--dur)",
                isIndeterminate && "w-1/3 motion-ok:animate-pulse",
              )}
              style={isIndeterminate ? undefined : { width: `${percentage ?? 0}%` }}
            />
          </div>
        </>
      )}
    </AriaProgressBar>
  );
}

/* ---- Tabs ------------------------------------------------------------------------------- */

export function Tabs(props: TabsProps) {
  return <AriaTabs {...props} className={composeClass(props.className, "flex flex-col min-h-0")} />;
}

export function TabList<T extends object>(props: TabListProps<T>) {
  return (
    <AriaTabList
      {...props}
      className={composeClass(props.className, "flex gap-1 border-b border-line px-1")}
    />
  );
}

/** Selected tab = bold + a 3 px ink bar under it (two cues, no hue). */
export function Tab(props: TabProps) {
  return (
    <AriaTab
      {...props}
      className={composeClass(
        props.className,
        cn(
          "relative -mb-px flex h-10 cursor-default items-center gap-1.5 rounded-t-control px-3 text-sm text-ink-2 outline-none",
          "hover:text-ink data-[selected]:font-semibold data-[selected]:text-ink",
          "after:absolute after:inset-x-2 after:bottom-0 after:h-[3px] after:rounded-t-sm after:bg-transparent data-[selected]:after:bg-ink",
        ),
      )}
    />
  );
}

export function TabPanel(props: TabPanelProps) {
  return <AriaTabPanel {...props} className={composeClass(props.className, "min-h-0 outline-none")} />;
}

/* ---- Disclosure (a lesson step) ------------------------------------------------------------ */

interface DisclosureProps {
  title: ReactNode;
  /** Step number shown in a circle (1–5 in the word lesson). */
  step?: number;
  children: ReactNode;
  defaultExpanded?: boolean;
  isExpanded?: boolean;
  onExpandedChange?: (expanded: boolean) => void;
  /** Extra controls on the header line, outside the toggle button. */
  actions?: ReactNode;
  className?: string;
  tourId?: string;
  /** Heading level of the title: 3 by default (inside a section); 2 when the disclosure
   *  sits directly under the page's h1, so no level is skipped. */
  level?: 2 | 3 | 4 | 5;
}

export function Disclosure({
  title,
  step,
  children,
  defaultExpanded = true,
  isExpanded,
  onExpandedChange,
  actions,
  className,
  tourId,
  level = 3,
}: DisclosureProps) {
  return (
    <AriaDisclosure
      defaultExpanded={defaultExpanded}
      isExpanded={isExpanded}
      onExpandedChange={onExpandedChange}
      className={cn("group border-b border-line last:border-b-0", className)}
      data-tour={tourId}
    >
      <div className="flex items-center gap-2">
        <Heading level={level} className="m-0 flex-1">
          <AriaButton
            slot="trigger"
            className="flex w-full items-center gap-2.5 rounded-control py-3 text-left text-base font-semibold text-ink outline-none"
          >
            <ChevronRight
              size={18}
              aria-hidden="true"
              className="shrink-0 text-ink-2 transition-transform duration-(--dur-fast) group-data-[expanded]:rotate-90"
            />
            {step !== undefined && (
              <span
                aria-hidden="true"
                className="flex size-6 shrink-0 items-center justify-center rounded-full bg-ink text-xs font-bold text-page"
              >
                {step}
              </span>
            )}
            <span>
              {/* the space stays outside the sr-only span: inside it collapses, and
                  the name would read "Step 1:Listen" */}
              {step !== undefined && <><span className="sr-only">Step {step}:</span> </>}
              {title}
            </span>
          </AriaButton>
        </Heading>
        {actions}
      </div>
      {/* padding on an inner box: a collapsed panel is hidden="until-found", which keeps
          its own box, so padding on the panel itself would leave 16 px of dead space */}
      <DisclosurePanel>
        <div className="pb-4 ps-8">{children}</div>
      </DisclosurePanel>
    </AriaDisclosure>
  );
}
