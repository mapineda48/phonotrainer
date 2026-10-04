/** Settings: appearance, colors (with color-vision previews), pattern emphasis, text
 *  size, motion, lesson guidance, tour and shortcuts. Every change applies at once and
 *  is remembered on this computer. */

import { Keyboard, Route } from "lucide-react";
import { useState } from "react";

import { TOUR } from "../didactic/tour-ids";
import { startTour } from "../features/tour";
import { useSettings } from "../settings/SettingsProvider";
import { cvdFilterId, type CvdKind } from "../settings/CvdFilterDefs";
import type { LessonGuidance, MotionPref, TextSize } from "../settings/settings";
import { Button, Card, PageHeader, RadioCard, RadioCardGroup, Segmented, Switch } from "../ui";
import { APPEARANCE_OPTIONS } from "./DisplayPopover";
import { FamilyPreview } from "./FamilyPreview";
import { ShortcutsDialog } from "./HelpDialogs";
import { useDocumentTitle } from "./useDocumentTitle";

const CVD_PREVIEWS: { kind: CvdKind; title: string; note: string }[] = [
  { kind: "protanopia", title: "Protanopia", note: "no red-sensitive cones" },
  { kind: "deuteranopia", title: "Deuteranopia", note: "no green-sensitive cones (the most common)" },
  { kind: "tritanopia", title: "Tritanopia", note: "no blue-sensitive cones" },
];

export function SettingsPage() {
  useDocumentTitle("Settings");
  const { settings, update, patterns } = useSettings();
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  return (
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-5 px-4 py-6 sm:px-6 sm:py-8">
      <PageHeader
        title="Settings"
        lede="Make the screen comfortable for your eyes. Changes apply at once and are remembered on this computer."
      />

      <Card title="Appearance" description="Light or dark, or follow your system.">
        <Segmented
          label="Appearance"
          hideLabel
          options={APPEARANCE_OPTIONS}
          value={settings.appearance}
          onChange={(appearance) => update({ appearance })}
        />
      </Card>

      <Card
        title="Colors"
        description="Colors only tell the four families apart, and each family also has an icon, an underline style and a name — so nothing depends on color alone."
      >
        <div className="flex flex-col gap-5">
          <RadioCardGroup
            label="Color palette"
            hideLabel
            orientation="horizontal"
            value={settings.palette}
            onChange={(palette) => update({ palette })}
            tourId={TOUR.settingsColors}
          >
            <RadioCard value="standard" title="Standard" className="flex-1 basis-64">
              Calm blue, amber, plum and teal.
            </RadioCard>
            <RadioCard value="cvd" title="Color-vision friendly" className="flex-1 basis-64">
              Blue, orange, vermilion and sky — kept apart even with red-green or blue-yellow color
              blindness. Turns on pattern emphasis.
            </RadioCard>
          </RadioCardGroup>

          <div className="grid gap-3 md:grid-cols-2">
            {(["standard", "cvd"] as const).map((palette) => (
              <figure key={palette} className="m-0 flex flex-col gap-1.5">
                <div data-palette={palette}>
                  <FamilyPreview compact />
                </div>
                <figcaption className="text-xs text-ink-2">
                  {palette === "standard" ? "Standard" : "Color-vision friendly"} palette
                </figcaption>
              </figure>
            ))}
          </div>

          <section aria-labelledby="cvd-previews" className="flex flex-col gap-2">
            <h3 id="cvd-previews" className="text-sm font-semibold text-ink">
              How your palette looks with color blindness
            </h3>
            <p className="text-sm text-ink-2">
              The same four families, simulated for the three types of color blindness. If two of them
              look alike, the icon and the underline still tell them apart.
            </p>
            <div className="grid gap-3 md:grid-cols-3">
              {CVD_PREVIEWS.map((preview) => (
                <figure key={preview.kind} className="m-0 flex flex-col gap-1.5">
                  <div style={{ filter: `url(#${cvdFilterId(preview.kind)})` }} aria-hidden="true">
                    <FamilyPreview compact />
                  </div>
                  <figcaption className="text-xs text-ink-2">
                    <span className="font-semibold text-ink">{preview.title}</span> — {preview.note}
                  </figcaption>
                </figure>
              ))}
            </div>
          </section>
        </div>
      </Card>

      <Card title="Pattern emphasis">
        <div className="flex flex-col gap-2">
          <Switch
            isSelected={patterns}
            onChange={(on) => update({ patterns: on ? "on" : "off" })}
            description="Adds the family icon after each marked word, thicker underlines and hatched chart bars."
          >
            Show patterns as well as colors
          </Switch>
          {settings.patterns !== "auto" && (
            <Button size="sm" variant="quiet" className="w-fit" onPress={() => update({ patterns: "auto" })}>
              Follow the Colors setting again
            </Button>
          )}
        </div>
      </Card>

      <Card title="Reading" description="Bigger text everywhere, including the IPA.">
        <div className="flex flex-wrap gap-6">
          <Segmented<TextSize>
            label="Text size"
            options={[
              { id: "default", label: "Default" },
              { id: "large", label: "Large" },
              { id: "larger", label: "Larger" },
            ]}
            value={settings.text}
            onChange={(text) => update({ text })}
          />
          <Segmented<MotionPref>
            label="Motion"
            options={[
              { id: "system", label: "Follow system" },
              { id: "reduce", label: "Reduce" },
            ]}
            value={settings.motion}
            onChange={(motion) => update({ motion })}
          />
        </div>
      </Card>

      <Card
        title="Lessons"
        description="Full opens every step of a word's lesson; Compact shows only the step titles until you open one."
      >
        <Segmented<LessonGuidance>
          label="Lesson guidance"
          options={[
            { id: "full", label: "Full" },
            { id: "compact", label: "Compact" },
          ]}
          value={settings.guidance}
          onChange={(guidance) => update({ guidance })}
        />
      </Card>

      <Card title="Help">
        <div className="flex flex-wrap gap-2">
          <Button icon={Route} onPress={() => startTour()}>
            Restart the tour
          </Button>
          <Button icon={Keyboard} onPress={() => setShortcutsOpen(true)}>
            Keyboard shortcuts
          </Button>
        </div>
      </Card>

      <ShortcutsDialog isOpen={shortcutsOpen} onOpenChange={setShortcutsOpen} />
    </div>
  );
}
