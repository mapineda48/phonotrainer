/** Quick display settings from the rail: appearance and colors, one click away. */

import { Monitor, Moon, Palette, Sun } from "lucide-react";
import { Button as AriaButton, DialogTrigger } from "react-aria-components";

import { paths } from "../paths";
import { useSettings } from "../settings/SettingsProvider";
import type { Appearance, PaletteName } from "../settings/settings";
import { cn, Popover, PopoverDialog, Segmented, TextLink } from "../ui";

export const APPEARANCE_OPTIONS = [
  { id: "system", label: "System", icon: Monitor },
  { id: "light", label: "Light", icon: Sun },
  { id: "dark", label: "Dark", icon: Moon },
] as const;

export const PALETTE_OPTIONS = [
  { id: "standard", label: "Standard" },
  { id: "cvd", label: "Color-vision friendly" },
] as const;

export function DisplayPopover({ collapsed }: { collapsed: boolean }) {
  const { settings, update } = useSettings();
  return (
    <DialogTrigger>
      <AriaButton
        aria-label={collapsed ? "Display" : undefined}
        className={cn(
          "flex h-11 items-center gap-3 rounded-control px-3 text-base text-ink outline-none hover:bg-surface-2",
          collapsed && "justify-center px-0",
        )}
      >
        <Palette size={20} aria-hidden="true" />
        {!collapsed && <span>Display</span>}
      </AriaButton>
      <Popover placement="end bottom">
        <PopoverDialog title="Display" ariaLabel="Display">
          <div className="flex flex-col gap-4 pt-1">
            <Segmented<Appearance>
              label="Appearance"
              size="sm"
              options={APPEARANCE_OPTIONS}
              value={settings.appearance}
              onChange={(appearance) => update({ appearance })}
            />
            <Segmented<PaletteName>
              label="Colors"
              size="sm"
              options={PALETTE_OPTIONS}
              value={settings.palette}
              onChange={(palette) => update({ palette })}
            />
            <TextLink href={paths.settings()} className="text-sm">
              All display settings
            </TextLink>
          </div>
        </PopoverDialog>
      </Popover>
    </DialogTrigger>
  );
}
