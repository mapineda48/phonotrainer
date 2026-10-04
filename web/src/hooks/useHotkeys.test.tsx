import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { Segmented, Tab, TabList, TabPanel, Tabs } from "../ui";
import { useHotkeys, type HotkeyMap } from "./useHotkeys";

function Harness({ map, enabled = true }: { map: HotkeyMap; enabled?: boolean }) {
  useHotkeys(map, enabled);
  const [speed, setSpeed] = useState("1");
  return (
    <div>
      <Segmented
        label="Speed"
        options={[
          { id: "0.5", label: "0.5×" },
          { id: "0.75", label: "0.75×" },
          { id: "1", label: "1×" },
        ]}
        value={speed}
        onChange={setSpeed}
      />
      <Tabs>
        <TabList aria-label="Pane">
          <Tab id="lesson">Lesson</Tab>
          <Tab id="summary">Summary</Tab>
        </TabList>
        <TabPanel id="lesson">lesson</TabPanel>
        <TabPanel id="summary">summary</TabPanel>
      </Tabs>
      <input aria-label="Search" />
      <button type="button">Play</button>
      <div data-testid="handled" onKeyDown={(event) => event.preventDefault()} tabIndex={-1} />
    </div>
  );
}

describe("useHotkeys", () => {
  it("runs the handler for its key anywhere on the page", () => {
    const seek = vi.fn();
    render(<Harness map={{ ArrowRight: seek }} />);
    fireEvent.keyDown(document.body, { key: "ArrowRight" });
    expect(seek).toHaveBeenCalledTimes(1);
  });

  it("ignores keys typed into a text field, and modified keys", () => {
    const seek = vi.fn();
    render(<Harness map={{ ArrowRight: seek, s: seek }} />);
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Search" }), { key: "s" });
    fireEvent.keyDown(document.body, { key: "s", ctrlKey: true });
    expect(seek).not.toHaveBeenCalled();
  });

  it("leaves Space and Enter to the focused button", () => {
    const toggle = vi.fn();
    render(<Harness map={{ " ": toggle, Enter: toggle }} />);
    fireEvent.keyDown(screen.getByRole("button", { name: "Play" }), { key: " " });
    fireEvent.keyDown(screen.getByRole("button", { name: "Play" }), { key: "Enter" });
    expect(toggle).not.toHaveBeenCalled();
  });

  // The bug: a control that handles a key with preventDefault() alone (React Aria's
  // ToggleButtonGroup did, under the old Speed control) let ← / → also seek 2 s.
  it("skips a key a control already handled (defaultPrevented)", () => {
    const seek = vi.fn();
    render(<Harness map={{ ArrowRight: seek }} />);
    fireEvent.keyDown(screen.getByTestId("handled"), { key: "ArrowRight" });
    expect(seek).not.toHaveBeenCalled();
  });

  it("arrow keys on a radiogroup (the Speed control) change it without also seeking", async () => {
    const seek = vi.fn();
    render(<Harness map={{ ArrowRight: seek, ArrowLeft: seek }} />);
    await userEvent.click(screen.getByRole("radio", { name: "0.75×" }));
    expect(screen.getByRole("radio", { name: "0.75×" })).toBeChecked();

    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("radio", { name: "1×" })).toBeChecked();
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByRole("radio", { name: "0.75×" })).toBeChecked();
    expect(seek).not.toHaveBeenCalled();
  });

  it("arrow keys on tabs (Lesson | Summary) switch the tab without also seeking", async () => {
    const seek = vi.fn();
    render(<Harness map={{ ArrowRight: seek, ArrowLeft: seek }} />);
    await userEvent.click(screen.getByRole("tab", { name: "Lesson" }));
    await userEvent.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "Summary" })).toHaveAttribute("aria-selected", "true");
    expect(seek).not.toHaveBeenCalled();
  });

  it("does nothing while disabled", () => {
    const seek = vi.fn();
    render(<Harness map={{ ArrowRight: seek }} enabled={false} />);
    fireEvent.keyDown(document.body, { key: "ArrowRight" });
    expect(seek).not.toHaveBeenCalled();
  });
});
