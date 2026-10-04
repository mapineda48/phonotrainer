/** Buttons. Built on React Aria: press, keyboard and focus behave the same with mouse,
 *  touch and keys, and keyboard focus is marked with data-focus-visible (the global
 *  3 px focus ring in index.css). Every target is at least 32 px tall (WCAG 2.5.8 asks
 *  for 24). Selected/pressed states are an ink FILL, never a hue. */

import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import {
  Button as AriaButton,
  Link as AriaLink,
  ToggleButton as AriaToggleButton,
  type ButtonProps as AriaButtonProps,
  type LinkProps as AriaLinkProps,
  type ToggleButtonProps as AriaToggleButtonProps,
} from "react-aria-components";

import { cn, composeClass, tv, type VariantProps } from "./cn";
import { Tip } from "./Tooltip";

export const buttonStyles = tv({
  base: [
    "inline-flex items-center justify-center gap-2 select-none whitespace-nowrap",
    "rounded-control font-medium cursor-default outline-none",
    "transition-[background-color,color,box-shadow] duration-(--dur-fast) ease-standard",
    "data-[disabled]:opacity-45 data-[disabled]:cursor-not-allowed",
    "data-[pressed]:translate-y-px",
  ],
  variants: {
    variant: {
      primary: "bg-ink text-page hover:bg-ink-2 data-[pressed]:bg-ink-2",
      secondary:
        "bg-surface text-ink shadow-[inset_0_0_0_1px_var(--line-strong)] hover:bg-surface-2 data-[pressed]:bg-surface-2",
      quiet: "bg-transparent text-ink hover:bg-surface-2 data-[pressed]:bg-surface-2",
      /* destructive actions: ink text + an icon chosen by the caller, never red text */
      danger:
        "bg-surface text-ink shadow-[inset_0_0_0_1.5px_var(--critical)] hover:bg-surface-2 data-[pressed]:bg-surface-2",
    },
    size: {
      sm: "h-8 px-3 text-sm",
      md: "h-10 px-4 text-base",
      lg: "h-12 px-5 text-lg",
    },
  },
  defaultVariants: { variant: "secondary", size: "md" },
});

type ButtonStyleProps = VariantProps<typeof buttonStyles>;

export interface ButtonProps extends AriaButtonProps, ButtonStyleProps {
  /** Icon before the label (decorative: the label names the action). */
  icon?: LucideIcon;
}

export function Button({ variant, size, icon: Icon, className, children, ...rest }: ButtonProps) {
  return (
    <AriaButton
      {...rest}
      className={composeClass(className, buttonStyles({ variant, size }))}
    >
      {(state) => (
        <>
          {Icon && <Icon size={size === "sm" ? 16 : 18} aria-hidden="true" />}
          {typeof children === "function" ? children(state) : children}
        </>
      )}
    </AriaButton>
  );
}

const iconButtonStyles = tv({
  extend: buttonStyles,
  // shrink-0: a squeezed row must never narrow an icon button below its target size
  base: "shrink-0",
  variants: {
    size: {
      sm: "size-8 px-0",
      md: "size-10 px-0",
      lg: "size-12 px-0",
    },
  },
  defaultVariants: { variant: "quiet", size: "md" },
});

export interface IconButtonProps extends Omit<AriaButtonProps, "children">, ButtonStyleProps {
  icon: LucideIcon;
  /** Required: an icon-only button needs an accessible name, also shown as a tooltip. */
  label: string;
  /** Hide the hover tooltip (when a visible label sits right next to the button). */
  noTooltip?: boolean;
}

export function IconButton({ icon: Icon, label, noTooltip, variant, size, className, ...rest }: IconButtonProps) {
  const button = (
    <AriaButton
      {...rest}
      aria-label={label}
      className={composeClass(className, iconButtonStyles({ variant, size }))}
    >
      <Icon size={size === "sm" ? 16 : 20} aria-hidden="true" className="shrink-0" />
    </AriaButton>
  );
  return noTooltip ? button : <Tip content={label}>{button}</Tip>;
}

export interface LinkButtonProps extends AriaLinkProps, ButtonStyleProps {
  icon?: LucideIcon;
}

/** A link (it navigates) that looks like a button. Client-side routing goes through the
 *  RouterProvider in App, so href="/learn" does not reload the page. */
export function LinkButton({ variant, size, icon: Icon, className, children, ...rest }: LinkButtonProps) {
  return (
    <AriaLink {...rest} className={composeClass(className, buttonStyles({ variant, size }))}>
      {(state) => (
        <>
          {Icon && <Icon size={size === "sm" ? 16 : 18} aria-hidden="true" />}
          {typeof children === "function" ? children(state) : children}
        </>
      )}
    </AriaLink>
  );
}

const toggleStyles = tv({
  extend: buttonStyles,
  base: "data-[selected]:bg-ink data-[selected]:text-page data-[selected]:shadow-none",
  defaultVariants: { variant: "secondary", size: "md" },
});

export interface ToggleButtonProps extends AriaToggleButtonProps, ButtonStyleProps {
  icon?: LucideIcon;
}

/** On/off button (e.g. Loop). Selected = ink fill + aria-pressed. */
export function ToggleButton({ variant, size, icon: Icon, className, children, ...rest }: ToggleButtonProps) {
  return (
    <AriaToggleButton {...rest} className={composeClass(className, toggleStyles({ variant, size }))}>
      {(state) => (
        <>
          {Icon && <Icon size={size === "sm" ? 16 : 18} aria-hidden="true" />}
          {typeof children === "function" ? children(state) : children}
        </>
      )}
    </AriaToggleButton>
  );
}

/** Plain inline text link (navigates). */
export function TextLink({ className, ...rest }: AriaLinkProps) {
  return (
    <AriaLink
      {...rest}
      className={composeClass(
        className,
        "text-ink underline decoration-1 underline-offset-[0.2em] hover:decoration-2 cursor-pointer rounded-sm",
      )}
    />
  );
}

/** Wrapper for a visible row of related controls. */
export function ButtonRow({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn("flex flex-wrap items-center gap-2", className)}>{children}</div>;
}
