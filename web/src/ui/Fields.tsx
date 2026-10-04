/** Form controls: segmented choice, switch, radio cards, select, text and search fields.
 *  All React Aria: labels are real <label>s, descriptions are wired with
 *  aria-describedby, and selection is shown by an ink fill or a check — never a hue. */

import { Check, ChevronDown, Search, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import {
  Button as AriaButton,
  FieldError,
  Input,
  Label,
  ListBox,
  ListBoxItem,
  Popover as AriaPopover,
  Radio,
  RadioGroup as AriaRadioGroup,
  SearchField as AriaSearchField,
  Select as AriaSelect,
  SelectValue,
  Switch as AriaSwitch,
  Text,
  TextField as AriaTextField,
  type Key,
} from "react-aria-components";

import { cn } from "./cn";

const labelClass = "text-sm font-semibold text-ink";
const descriptionClass = "text-xs text-ink-muted";

/* ---- Segmented: one of a few options (speed, appearance, palette) -------------- */

export interface SegmentOption<K extends string = string> {
  id: K;
  label: ReactNode;
  icon?: LucideIcon;
  /** Accessible name when `label` is not plain text. */
  ariaLabel?: string;
}

interface SegmentedProps<K extends string> {
  /** Visible label; pass `hideLabel` to keep it only as the group's accessible name. */
  label: string;
  hideLabel?: boolean;
  options: readonly SegmentOption<K>[];
  value: K;
  onChange: (value: K) => void;
  size?: "sm" | "md";
  className?: string;
  /** data-tour attribute (see didactic/tour-ids.ts). */
  tourId?: string;
}

export function Segmented<K extends string>({
  label,
  hideLabel,
  options,
  value,
  onChange,
  size = "md",
  className,
  tourId,
}: SegmentedProps<K>) {
  // A real radio group (WAI-ARIA radio pattern): one Tab stop on the checked option,
  // and the arrow keys move AND check, so ← / → change the speed directly.
  return (
    <AriaRadioGroup
      value={value}
      onChange={(next) => onChange(next as K)}
      orientation="horizontal"
      className={cn("flex flex-col gap-1.5", className)}
      data-tour={tourId}
    >
      <Label className={hideLabel ? "sr-only" : labelClass}>{label}</Label>
      <div className="inline-flex w-fit rounded-control bg-surface p-0.5 shadow-[inset_0_0_0_1px_var(--line-strong)]">
        {options.map((option) => {
          const Icon = option.icon;
          return (
            <Radio
              key={option.id}
              value={option.id}
              aria-label={option.ariaLabel}
              className={cn(
                "inline-flex cursor-default items-center gap-1.5 rounded-[5px] font-medium text-ink outline-none",
                "transition-colors duration-(--dur-fast)",
                "data-[hovered]:bg-surface-2 data-[selected]:bg-ink data-[selected]:text-page",
                size === "sm" ? "h-8 px-2.5 text-sm" : "h-9 px-3 text-sm",
              )}
            >
              {Icon && <Icon size={16} aria-hidden="true" />}
              {option.label}
            </Radio>
          );
        })}
      </div>
    </AriaRadioGroup>
  );
}

/* ---- Switch ---------------------------------------------------------------------- */

interface SwitchProps {
  children: ReactNode;
  description?: ReactNode;
  isSelected: boolean;
  onChange: (selected: boolean) => void;
  isDisabled?: boolean;
  className?: string;
}

export function Switch({ children, description, isSelected, onChange, isDisabled, className }: SwitchProps) {
  return (
    <AriaSwitch
      isSelected={isSelected}
      onChange={onChange}
      isDisabled={isDisabled}
      className={cn("group flex items-start gap-3 cursor-default data-[disabled]:opacity-50", className)}
    >
      <span
        aria-hidden="true"
        className={cn(
          "mt-0.5 flex h-6 w-11 shrink-0 items-center rounded-chip p-0.5",
          "bg-surface-2 shadow-[inset_0_0_0_1.5px_var(--line-strong)]",
          "group-data-[selected]:bg-ink group-data-[selected]:shadow-none",
          "group-data-[focus-visible]:outline group-data-[focus-visible]:outline-3 group-data-[focus-visible]:outline-offset-2 group-data-[focus-visible]:outline-focus",
          "transition-colors duration-(--dur-fast)",
        )}
      >
        <span
          className={cn(
            "flex size-5 items-center justify-center rounded-full bg-surface shadow-1",
            "transition-transform duration-(--dur-fast) group-data-[selected]:translate-x-5",
          )}
        >
          <Check size={12} className="hidden text-ink group-data-[selected]:block" />
        </span>
      </span>
      <span className="flex flex-col">
        <span className="text-sm font-medium text-ink">{children}</span>
        {description && <span className={descriptionClass}>{description}</span>}
      </span>
    </AriaSwitch>
  );
}

/* ---- Radio cards (engine choice, palette choice) ---------------------------------- */

interface RadioCardGroupProps<K extends string> {
  label: string;
  hideLabel?: boolean;
  description?: ReactNode;
  value: K;
  onChange: (value: K) => void;
  children: ReactNode;
  orientation?: "vertical" | "horizontal";
  className?: string;
  tourId?: string;
}

export function RadioCardGroup<K extends string>({
  label,
  hideLabel,
  description,
  value,
  onChange,
  children,
  orientation = "vertical",
  className,
  tourId,
}: RadioCardGroupProps<K>) {
  return (
    <AriaRadioGroup
      value={value}
      onChange={(next) => onChange(next as K)}
      orientation={orientation}
      className={cn("flex flex-col gap-2", className)}
      data-tour={tourId}
    >
      <Label className={hideLabel ? "sr-only" : labelClass}>{label}</Label>
      {description && (
        <Text slot="description" className={descriptionClass}>
          {description}
        </Text>
      )}
      <div className={cn("flex gap-2", orientation === "vertical" ? "flex-col" : "flex-row flex-wrap")}>
        {children}
      </div>
    </AriaRadioGroup>
  );
}

interface RadioCardProps {
  value: string;
  title: ReactNode;
  children?: ReactNode;
  className?: string;
}

/** A selectable card: a ring + a filled dot + bold title when selected (three cues). */
export function RadioCard({ value, title, children, className }: RadioCardProps) {
  return (
    <Radio
      value={value}
      className={cn(
        "group flex cursor-default gap-3 rounded-card bg-surface p-3 outline-none",
        "shadow-[inset_0_0_0_1px_var(--line-strong)] hover:bg-surface-2",
        "data-[selected]:shadow-[inset_0_0_0_2px_var(--ink)]",
        "data-[focus-visible]:outline-3 data-[focus-visible]:outline-offset-2 data-[focus-visible]:outline-focus",
        className,
      )}
    >
      <span
        aria-hidden="true"
        className="mt-1 flex size-4 shrink-0 items-center justify-center rounded-full shadow-[inset_0_0_0_2px_var(--ink-2)] group-data-[selected]:shadow-[inset_0_0_0_2px_var(--ink)]"
      >
        <span className="size-2 rounded-full bg-ink opacity-0 group-data-[selected]:opacity-100" />
      </span>
      <span className="flex min-w-0 flex-col gap-1">
        <span className="font-semibold text-ink">{title}</span>
        {children && <span className="text-sm text-ink-2">{children}</span>}
      </span>
    </Radio>
  );
}

/* ---- Select ------------------------------------------------------------------------ */

export interface SelectItem<K extends string = string> {
  id: K;
  label: string;
  description?: string;
}

interface SelectProps<K extends string> {
  label: string;
  hideLabel?: boolean;
  items: readonly SelectItem<K>[];
  value: K | null;
  onChange: (value: K) => void;
  placeholder?: string;
  description?: ReactNode;
  className?: string;
}

export function Select<K extends string>({
  label,
  hideLabel,
  items,
  value,
  onChange,
  placeholder,
  description,
  className,
}: SelectProps<K>) {
  return (
    <AriaSelect
      selectedKey={value}
      onSelectionChange={(key: Key | null) => {
        if (key !== null) onChange(String(key) as K);
      }}
      placeholder={placeholder}
      className={cn("flex flex-col gap-1.5", className)}
    >
      <Label className={hideLabel ? "sr-only" : labelClass}>{label}</Label>
      <AriaButton className="inline-flex h-10 min-w-40 items-center justify-between gap-2 rounded-control bg-surface px-3 text-left text-sm text-ink shadow-[inset_0_0_0_1px_var(--line-strong)] outline-none hover:bg-surface-2">
        {/* only the label in the trigger: by default SelectValue would render the whole
            item, description included, and overflow the button */}
        <SelectValue className="truncate data-[placeholder]:text-ink-muted">
          {({ isPlaceholder, selectedText, defaultChildren }) => (isPlaceholder ? defaultChildren : selectedText)}
        </SelectValue>
        <ChevronDown size={16} aria-hidden="true" />
      </AriaButton>
      {description && (
        <Text slot="description" className={descriptionClass}>
          {description}
        </Text>
      )}
      <AriaPopover className="min-w-(--trigger-width) rounded-card bg-surface p-1 shadow-2 outline-none">
        <ListBox className="max-h-72 overflow-auto outline-none">
          {items.map((item) => (
            <ListBoxItem
              key={item.id}
              id={item.id}
              textValue={item.label}
              className="group flex cursor-default items-center gap-2 rounded-control px-2.5 py-2 text-sm text-ink outline-none data-[focused]:bg-surface-2 data-[selected]:font-semibold"
            >
              <Check size={16} aria-hidden="true" className="invisible group-data-[selected]:visible" />
              <span className="flex flex-col">
                <Text slot="label">{item.label}</Text>
                {item.description && (
                  <Text slot="description" className={descriptionClass}>
                    {item.description}
                  </Text>
                )}
              </span>
            </ListBoxItem>
          ))}
        </ListBox>
      </AriaPopover>
    </AriaSelect>
  );
}

/* ---- Text and search fields --------------------------------------------------------- */

const inputClass =
  "h-10 w-full rounded-control bg-surface px-3 text-sm text-ink placeholder:text-ink-muted shadow-[inset_0_0_0_1px_var(--line-strong)] outline-none data-[focused]:shadow-[inset_0_0_0_2px_var(--ink)] data-[invalid]:shadow-[inset_0_0_0_2px_var(--critical)]";

interface TextFieldProps {
  label: string;
  hideLabel?: boolean;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  description?: ReactNode;
  errorMessage?: string;
  type?: "text" | "url";
  className?: string;
  autoFocus?: boolean;
}

export function TextField({
  label,
  hideLabel,
  value,
  onChange,
  placeholder,
  description,
  errorMessage,
  type = "text",
  className,
  autoFocus,
}: TextFieldProps) {
  return (
    <AriaTextField
      value={value}
      onChange={onChange}
      type={type}
      isInvalid={Boolean(errorMessage)}
      autoFocus={autoFocus}
      className={cn("flex flex-col gap-1.5", className)}
    >
      <Label className={hideLabel ? "sr-only" : labelClass}>{label}</Label>
      <Input placeholder={placeholder} className={inputClass} />
      {description && (
        <Text slot="description" className={descriptionClass}>
          {description}
        </Text>
      )}
      <FieldError className="text-xs font-medium text-ink">{errorMessage}</FieldError>
    </AriaTextField>
  );
}

interface SearchFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  onSubmit?: (value: string) => void;
}

/** Search box; the label is visually hidden (the magnifier and placeholder say it). */
export function SearchField({ label, value, onChange, placeholder, className, onSubmit }: SearchFieldProps) {
  return (
    <AriaSearchField
      aria-label={label}
      value={value}
      onChange={onChange}
      onSubmit={onSubmit}
      className={cn("group relative flex items-center", className)}
    >
      <Search size={16} aria-hidden="true" className="pointer-events-none absolute left-3 text-ink-muted" />
      <Input placeholder={placeholder} className={cn(inputClass, "pl-9 pr-9 [&::-webkit-search-cancel-button]:hidden")} />
      <AriaButton className="absolute right-1 flex size-8 items-center justify-center rounded-control text-ink-2 outline-none hover:bg-surface-2 group-data-[empty]:hidden">
        <X size={16} aria-hidden="true" />
        <span className="sr-only">Clear search</span>
      </AriaButton>
    </AriaSearchField>
  );
}
