/** The main navigation.
 *
 *  Wide screens (768 px and up): a rail at the side, always visible, collapsible to icons
 *  (the labels stay as accessible names). Narrow screens: a slim bar at the top with a
 *  Menu button that opens the same navigation in a drawer, so a phone gives the whole
 *  width to the page. Current page = ink fill + bold + aria-current, in both. */

import {
  ChartColumn,
  CircleHelp,
  GraduationCap,
  Keyboard,
  LibraryBig,
  Menu as MenuIcon,
  Mic2,
  PanelLeftClose,
  PanelLeftOpen,
  Route,
  Settings2,
  Target,
  X,
  type LucideIcon,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  Button as AriaButton,
  Dialog,
  DialogTrigger,
  Link as AriaLink,
  MenuTrigger,
  Modal,
  ModalOverlay,
} from "react-aria-components";
import { useLocation } from "wouter";

import { TOUR } from "../didactic/tour-ids";
import { startTour } from "../features/tour";
import { usePersistentFlag } from "../hooks/usePersistentFlag";
import { paths } from "../paths";
import { cn, IconButton, Menu, MenuItem, Popover, Tip } from "../ui";
import { DisplayPopover } from "./DisplayPopover";
import { AboutEnginesDialog, ShortcutsDialog } from "./HelpDialogs";

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  /** Does the current path belong to this item? */
  match: (path: string) => boolean;
  tourId?: string;
}

const ITEMS: NavItem[] = [
  {
    href: paths.library(),
    label: "Library",
    icon: LibraryBig,
    match: (p) => p === "/" || p === "/new" || p.startsWith("/analysis/"),
  },
  {
    href: paths.learn(),
    label: "Learn",
    icon: GraduationCap,
    match: (p) => p.startsWith("/learn"),
    tourId: TOUR.learnNav,
  },
  { href: paths.practice(), label: "Practice", icon: Target, match: (p) => p.startsWith("/practice") },
  { href: paths.insights(), label: "Insights", icon: ChartColumn, match: (p) => p.startsWith("/insights") },
];

const SETTINGS_ITEM: NavItem = {
  href: paths.settings(),
  label: "Settings",
  icon: Settings2,
  match: (p) => p === "/settings",
};

type HelpDialog = "shortcuts" | "engines";

/** Shared row style of the rail and the drawer: 44 px tall, a comfortable touch target. */
const rowStyles = (collapsed: boolean) =>
  cn(
    "flex h-11 items-center gap-3 rounded-control px-3 text-base text-ink outline-none",
    collapsed && "justify-center px-0",
  );

function RailLink({
  item,
  path,
  collapsed,
  onNavigate,
}: {
  item: NavItem;
  path: string;
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const current = item.match(path);
  const Icon = item.icon;
  const link = (
    <AriaLink
      href={item.href}
      aria-current={current ? "page" : undefined}
      aria-label={collapsed ? item.label : undefined}
      data-tour={item.tourId}
      onPress={onNavigate}
      className={cn(
        rowStyles(collapsed),
        "transition-colors duration-(--dur-fast)",
        current ? "bg-ink font-semibold text-page" : "hover:bg-surface-2",
      )}
    >
      <Icon size={20} aria-hidden="true" className="shrink-0" />
      {!collapsed && <span>{item.label}</span>}
    </AriaLink>
  );
  return collapsed ? (
    <Tip content={item.label} placement="end">
      {link}
    </Tip>
  ) : (
    link
  );
}

/** The four sections. */
function SectionLinks({ path, collapsed, onNavigate }: { path: string; collapsed: boolean; onNavigate?: () => void }) {
  return (
    <ul className="m-0 flex list-none flex-col gap-1 p-0">
      {ITEMS.map((item) => (
        <li key={item.href}>
          <RailLink item={item} path={path} collapsed={collapsed} onNavigate={onNavigate} />
        </li>
      ))}
    </ul>
  );
}

/** Display, Settings and Help, at the foot of the rail or the drawer. */
function NavFooter({
  path,
  collapsed,
  onNavigate,
  onHelp,
  helpRef,
}: {
  path: string;
  collapsed: boolean;
  onNavigate?: () => void;
  onHelp: (key: "tour" | HelpDialog) => void;
  helpRef?: React.RefObject<HTMLButtonElement | null>;
}) {
  return (
    <div className="mt-auto flex flex-col gap-1 border-t border-line pt-2">
      <DisplayPopover collapsed={collapsed} />
      <RailLink item={SETTINGS_ITEM} path={path} collapsed={collapsed} onNavigate={onNavigate} />
      <MenuTrigger>
        <AriaButton
          ref={helpRef}
          aria-label={collapsed ? "Help" : undefined}
          className={cn(rowStyles(collapsed), "hover:bg-surface-2")}
        >
          <CircleHelp size={20} aria-hidden="true" />
          {!collapsed && <span>Help</span>}
        </AriaButton>
        <Popover placement="end bottom">
          <Menu aria-label="Help" onAction={(key) => onHelp(key as "tour" | HelpDialog)}>
            <MenuItem id="tour" textValue="Restart the tour">
              <Route size={16} aria-hidden="true" /> Restart the tour
            </MenuItem>
            <MenuItem id="shortcuts" textValue="Keyboard shortcuts">
              <Keyboard size={16} aria-hidden="true" /> Keyboard shortcuts
            </MenuItem>
            <MenuItem id="engines" textValue="About the engines">
              <Mic2 size={16} aria-hidden="true" /> About the engines
            </MenuItem>
          </Menu>
        </Popover>
      </MenuTrigger>
    </div>
  );
}

function Brand() {
  return (
    <span className="flex items-center gap-2 text-base font-bold tracking-tight text-ink">
      <Mic2 size={20} aria-hidden="true" />
      PhonoTrainer
    </span>
  );
}

/** Wide screens: the rail. */
function Rail({ path, onDialog }: { path: string; onDialog: (dialog: HelpDialog) => void }) {
  const [collapsed, toggleCollapsed] = usePersistentFlag("phonotrainer:rail-collapsed", false);
  const helpRef = useRef<HTMLButtonElement>(null);
  return (
    <nav
      aria-label="Main"
      data-tour={TOUR.nav}
      className={cn(
        "flex h-full flex-col gap-1 overflow-y-auto border-e border-line bg-surface p-2",
        collapsed ? "w-[60px]" : "w-[208px]",
      )}
    >
      <div className={cn("flex items-center gap-2 px-1 pb-3 pt-1", collapsed ? "justify-center" : "justify-between")}>
        {!collapsed && <Brand />}
        <IconButton
          icon={collapsed ? PanelLeftOpen : PanelLeftClose}
          label={collapsed ? "Expand the menu" : "Collapse the menu"}
          size="sm"
          onPress={toggleCollapsed}
        />
      </div>
      <SectionLinks path={path} collapsed={collapsed} />
      <NavFooter
        path={path}
        collapsed={collapsed}
        helpRef={helpRef}
        onHelp={(key) => {
          // the tour hands the focus back to Help when it ends, not to this menu item,
          // which will be gone by then
          if (key === "tour") startTour({ returnFocus: helpRef.current });
          else onDialog(key);
        }}
      />
    </nav>
  );
}

/** Narrow screens: a slim top bar whose Menu button opens the navigation in a drawer
 *  (a modal dialog: focus moves in, Esc or a tap outside closes it, focus returns to the
 *  button). Choosing a page closes it. */
function MenuBar({ path, onDialog }: { path: string; onDialog: (dialog: HelpDialog) => void }) {
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);

  // a page changed some other way (a link inside the page, Back): never leave it covered
  useEffect(() => setOpen(false), [path]);

  /** Close the drawer first, then act once focus is back on the Menu button, so the
   *  dialog or the tour opened next hands the focus back to a control that still exists. */
  const afterClosing = (action: () => void) => {
    setOpen(false);
    window.setTimeout(action, 0);
  };

  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b border-line bg-surface px-2">
      <DialogTrigger isOpen={open} onOpenChange={setOpen}>
        <AriaButton
          ref={menuRef}
          data-tour={TOUR.nav}
          className="flex h-11 items-center gap-2 rounded-control px-3 text-base font-medium text-ink outline-none hover:bg-surface-2 data-[pressed]:bg-surface-2"
        >
          <MenuIcon size={22} aria-hidden="true" />
          Menu
        </AriaButton>
        <ModalOverlay
          isDismissable
          className="fixed inset-0 z-50 bg-scrim motion-ok:data-[entering]:animate-[pt-fade-in_var(--dur)_var(--ease-standard)]"
        >
          <Modal className="fixed inset-y-0 start-0 flex w-[min(18rem,calc(100vw-3rem))] flex-col bg-surface text-ink shadow-2 outline-none motion-ok:data-[entering]:animate-[pt-drawer-in_var(--dur)_var(--ease-standard)]">
            <Dialog aria-label="Menu" className="flex min-h-0 flex-1 flex-col outline-none">
              {({ close }) => (
                <nav aria-label="Main" className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto p-2">
                  <div className="flex items-center justify-between gap-2 px-1 pb-3 pt-1">
                    <Brand />
                    <IconButton icon={X} label="Close the menu" onPress={close} noTooltip />
                  </div>
                  <SectionLinks path={path} collapsed={false} onNavigate={close} />
                  <NavFooter
                    path={path}
                    collapsed={false}
                    onNavigate={close}
                    onHelp={(key) =>
                      afterClosing(() => {
                        if (key === "tour") startTour({ returnFocus: menuRef.current });
                        else onDialog(key);
                      })
                    }
                  />
                </nav>
              )}
            </Dialog>
          </Modal>
        </ModalOverlay>
      </DialogTrigger>
      <Brand />
    </header>
  );
}

/** The navigation for the current screen width, and the Help dialogs it opens (kept out
 *  of the drawer, so they outlive it). */
export function NavRail({ compact = false }: { compact?: boolean }) {
  const [path] = useLocation();
  const [dialog, setDialog] = useState<HelpDialog | null>(null);
  return (
    <>
      {compact ? <MenuBar path={path} onDialog={setDialog} /> : <Rail path={path} onDialog={setDialog} />}
      <ShortcutsDialog isOpen={dialog === "shortcuts"} onOpenChange={(open) => setDialog(open ? "shortcuts" : null)} />
      <AboutEnginesDialog isOpen={dialog === "engines"} onOpenChange={(open) => setDialog(open ? "engines" : null)} />
    </>
  );
}
