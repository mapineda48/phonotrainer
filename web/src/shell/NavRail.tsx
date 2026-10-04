/** The navigation rail: always visible, collapsible to icons (the labels stay as
 *  accessible names). Current page = ink fill + bold + aria-current. */

import {
  ChartColumn,
  CircleHelp,
  GraduationCap,
  Keyboard,
  LibraryBig,
  Mic2,
  PanelLeftClose,
  PanelLeftOpen,
  Route,
  Settings2,
  Target,
  type LucideIcon,
} from "lucide-react";
import { useRef, useState } from "react";
import { Button as AriaButton, Link as AriaLink, MenuTrigger } from "react-aria-components";
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
  { href: paths.learn(), label: "Learn", icon: GraduationCap, match: (p) => p.startsWith("/learn"), tourId: TOUR.learnNav },
  { href: paths.practice(), label: "Practice", icon: Target, match: (p) => p.startsWith("/practice") },
  { href: paths.insights(), label: "Insights", icon: ChartColumn, match: (p) => p.startsWith("/insights") },
];

function RailLink({ item, path, collapsed }: { item: NavItem; path: string; collapsed: boolean }) {
  const current = item.match(path);
  const Icon = item.icon;
  const link = (
    <AriaLink
      href={item.href}
      aria-current={current ? "page" : undefined}
      aria-label={collapsed ? item.label : undefined}
      data-tour={item.tourId}
      className={cn(
        "flex h-11 items-center gap-3 rounded-control px-3 text-base text-ink outline-none",
        "transition-colors duration-(--dur-fast)",
        current ? "bg-ink font-semibold text-page" : "hover:bg-surface-2",
        collapsed && "justify-center px-0",
      )}
    >
      <Icon size={20} aria-hidden="true" className="shrink-0" />
      {!collapsed && <span>{item.label}</span>}
    </AriaLink>
  );
  return collapsed ? <Tip content={item.label} placement="end">{link}</Tip> : link;
}

export function NavRail() {
  const [path] = useLocation();
  const [collapsed, toggleCollapsed] = usePersistentFlag("phonotrainer:rail-collapsed", false);
  const helpRef = useRef<HTMLButtonElement>(null);
  const [dialog, setDialog] = useState<"shortcuts" | "engines" | null>(null);

  return (
    <nav
      aria-label="Main"
      data-tour={TOUR.nav}
      className={cn(
        "flex h-full flex-col gap-1 border-e border-line bg-surface p-2",
        collapsed ? "w-[60px]" : "w-[208px]",
      )}
    >
      <div className={cn("flex items-center gap-2 px-1 pb-3 pt-1", collapsed ? "justify-center" : "justify-between")}>
        {!collapsed && (
          <span className="flex items-center gap-2 text-base font-bold tracking-tight text-ink">
            <Mic2 size={20} aria-hidden="true" />
            PhonoTrainer
          </span>
        )}
        <IconButton
          icon={collapsed ? PanelLeftOpen : PanelLeftClose}
          label={collapsed ? "Expand the menu" : "Collapse the menu"}
          size="sm"
          onPress={toggleCollapsed}
        />
      </div>

      <ul className="m-0 flex list-none flex-col gap-1 p-0">
        {ITEMS.map((item) => (
          <li key={item.href}>
            <RailLink item={item} path={path} collapsed={collapsed} />
          </li>
        ))}
      </ul>

      <div className="mt-auto flex flex-col gap-1 border-t border-line pt-2">
        <DisplayPopover collapsed={collapsed} />
        <RailLink
          item={{ href: paths.settings(), label: "Settings", icon: Settings2, match: (p) => p === "/settings" }}
          path={path}
          collapsed={collapsed}
        />
        <MenuTrigger>
          <AriaButton
            ref={helpRef}
            aria-label={collapsed ? "Help" : undefined}
            className={cn(
              "flex h-11 items-center gap-3 rounded-control px-3 text-base text-ink outline-none hover:bg-surface-2",
              collapsed && "justify-center px-0",
            )}
          >
            <CircleHelp size={20} aria-hidden="true" />
            {!collapsed && <span>Help</span>}
          </AriaButton>
          <Popover placement="end bottom">
            <Menu
              aria-label="Help"
              onAction={(key) => {
                // the tour hands the focus back to Help when it ends, not to this menu item,
                // which will be gone by then
                if (key === "tour") startTour({ returnFocus: helpRef.current });
                if (key === "shortcuts") setDialog("shortcuts");
                if (key === "engines") setDialog("engines");
              }}
            >
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

      <ShortcutsDialog isOpen={dialog === "shortcuts"} onOpenChange={(open) => setDialog(open ? "shortcuts" : null)} />
      <AboutEnginesDialog isOpen={dialog === "engines"} onOpenChange={(open) => setDialog(open ? "engines" : null)} />
    </nav>
  );
}
