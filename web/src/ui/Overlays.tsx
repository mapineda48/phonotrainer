/** Overlays: popover, modal dialog, confirmation dialog, menu. Esc closes and focus goes
 *  back to the trigger (React Aria). No window.confirm anywhere. */

import { X } from "lucide-react";
import type { ReactNode } from "react";
import {
  Dialog as AriaDialog,
  Heading,
  Menu as AriaMenu,
  MenuItem as AriaMenuItem,
  Modal,
  ModalOverlay,
  Popover as AriaPopover,
  Separator,
  type MenuItemProps,
  type MenuProps,
  type PopoverProps as AriaPopoverProps,
} from "react-aria-components";

import { Button, IconButton } from "./Button";
import { cn, composeClass } from "./cn";

/* ---- Popover ---------------------------------------------------------------------- */

export function Popover({ className, ...props }: AriaPopoverProps) {
  return (
    <AriaPopover
      offset={8}
      {...props}
      className={composeClass(
        className,
        "max-w-[min(26rem,calc(100vw-2rem))] rounded-card bg-surface text-ink shadow-2 outline-none ring-1 ring-line-strong",
      )}
    />
  );
}

/** Popover content with a title; `aria-label` falls back to the title text. */
export function PopoverDialog({
  title,
  children,
  className,
  ariaLabel,
}: {
  title?: ReactNode;
  children: ReactNode;
  className?: string;
  ariaLabel?: string;
}) {
  return (
    <AriaDialog aria-label={ariaLabel} className={cn("flex flex-col gap-2 p-4 outline-none", className)}>
      {title && (
        <Heading slot="title" className="text-base font-semibold text-ink">
          {title}
        </Heading>
      )}
      {children}
    </AriaDialog>
  );
}

/* ---- Modal dialog ------------------------------------------------------------------- */

interface DialogProps {
  title: ReactNode;
  children: ReactNode | ((close: () => void) => ReactNode);
  isOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  /** Footer buttons; receive `close`. */
  actions?: (close: () => void) => ReactNode;
  size?: "sm" | "md" | "lg";
  /** Clicking outside closes (default true; false for destructive confirmations). */
  dismissable?: boolean;
  role?: "dialog" | "alertdialog";
}

export function AppDialog({
  title,
  children,
  isOpen,
  onOpenChange,
  actions,
  size = "md",
  dismissable = true,
  role = "dialog",
}: DialogProps) {
  return (
    <ModalOverlay
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      isDismissable={dismissable}
      className="fixed inset-0 z-50 flex items-center justify-center bg-scrim p-4"
    >
      <Modal
        className={cn(
          "max-h-[calc(100dvh-2rem)] w-full overflow-auto rounded-card bg-surface text-ink shadow-2 outline-none ring-1 ring-line-strong",
          size === "sm" && "max-w-md",
          size === "md" && "max-w-xl",
          size === "lg" && "max-w-3xl",
        )}
      >
        <AriaDialog role={role} className="flex flex-col gap-4 p-5 outline-none">
          {({ close }) => (
            <>
              <div className="flex items-start justify-between gap-3">
                <Heading slot="title" className="text-xl font-semibold text-ink">
                  {title}
                </Heading>
                <IconButton icon={X} label="Close" size="sm" onPress={close} noTooltip />
              </div>
              <div className="text-base text-ink-2">
                {typeof children === "function" ? children(close) : children}
              </div>
              {actions && <div className="flex flex-wrap justify-end gap-2 pt-1">{actions(close)}</div>}
            </>
          )}
        </AriaDialog>
      </Modal>
    </ModalOverlay>
  );
}

interface ConfirmDialogProps {
  title: ReactNode;
  children: ReactNode;
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  confirmLabel: string;
  onConfirm: () => void | Promise<void>;
  cancelLabel?: string;
  destructive?: boolean;
}

/** "Are you sure?" — an alertdialog with Cancel focused first for destructive actions. */
export function ConfirmDialog({
  title,
  children,
  isOpen,
  onOpenChange,
  confirmLabel,
  onConfirm,
  cancelLabel = "Cancel",
  destructive,
}: ConfirmDialogProps) {
  return (
    <AppDialog
      title={title}
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      size="sm"
      role="alertdialog"
      dismissable={!destructive}
      actions={(close) => (
        <>
          <Button variant="secondary" onPress={close} autoFocus={destructive}>
            {cancelLabel}
          </Button>
          <Button
            variant={destructive ? "danger" : "primary"}
            onPress={async () => {
              await onConfirm();
              close();
            }}
          >
            {confirmLabel}
          </Button>
        </>
      )}
    >
      {children}
    </AppDialog>
  );
}

/* ---- Menu ------------------------------------------------------------------------------ */

export function Menu<T extends object>(props: MenuProps<T>) {
  return <AriaMenu {...props} className={composeClass(props.className, "min-w-52 p-1 outline-none")} />;
}

export function MenuItem(props: MenuItemProps) {
  return (
    <AriaMenuItem
      {...props}
      className={composeClass(
        props.className,
        "flex cursor-default items-center gap-2.5 rounded-control px-3 py-2 text-sm text-ink outline-none data-[focused]:bg-surface-2",
      )}
    />
  );
}

export function MenuSeparator() {
  return <Separator className="my-1 border-t border-line" />;
}
