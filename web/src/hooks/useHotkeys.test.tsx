import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import { Segmented, Switch, Tab, TabList, TabPanel, Tabs } from "../ui";
import { useHotkeys, type HotkeyMap } from "./useHotkeys";

function Harness({ map, enabled = true }: { map: HotkeyMap; enabled?: boolean }) {
  useHotkeys(map, enabled);
  const [speed, setSpeed] = useState("1");
  const [first, setFirst] = useState(false);
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
      <Switch isSelected={first} onChange={setFirst}>
        Play the original first
      </Switch>
      <input aria-label="Search" />
      <input aria-label="Seed" type="number" />
      <textarea aria-label="Note" />
      <div aria-label="Editor" role="textbox" contentEditable suppressContentEditableWarning />
      <input aria-label="Remember" type="checkbox" />
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

  // The bug: a switch (React Aria renders a checkbox <input>) counted as a text field,
  // so after toggling "Play the original first" R / A / B stayed dead until focus moved.
  it("keeps working on a focused switch or checkbox: only text entry swallows keys", async () => {
    const record = vi.fn();
    render(<Harness map={{ r: record }} />);
    await userEvent.click(screen.getByRole("switch", { name: "Play the original first" }));
    expect(screen.getByRole("switch", { name: "Play the original first" })).toHaveFocus();
    await userEvent.keyboard("r");
    expect(record).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole("checkbox", { name: "Remember" }));
    await userEvent.keyboard("r");
    expect(record).toHaveBeenCalledTimes(2);

    await userEvent.click(screen.getByRole("radio", { name: "0.5×" }));
    await userEvent.keyboard("r");
    expect(record).toHaveBeenCalledTimes(3);
  });

  it("Space still toggles a focused switch, and does not also fire the shortcut", async () => {
    const toggle = vi.fn();
    render(<Harness map={{ " ": toggle }} />);
    const sw = screen.getByRole("switch", { name: "Play the original first" });
    await userEvent.click(sw);
    expect(sw).toBeChecked();
    await userEvent.keyboard(" ");
    expect(sw).not.toBeChecked();
    expect(toggle).not.toHaveBeenCalled();
  });

  it("text entry still swallows every key: text and number inputs, textarea, contenteditable", () => {
    const record = vi.fn();
    render(<Harness map={{ r: record, "1": record }} />);
    for (const name of ["Search", "Note"]) fireEvent.keyDown(screen.getByRole("textbox", { name }), { key: "r" });
    fireEvent.keyDown(screen.getByRole("spinbutton", { name: "Seed" }), { key: "1" });
    fireEvent.keyDown(screen.getByRole("textbox", { name: "Editor" }), { key: "r" });
    expect(record).not.toHaveBeenCalled();
  });

  it("does nothing while disabled", () => {
    const seek = vi.fn();
    render(<Harness map={{ ArrowRight: seek }} enabled={false} />);
    fireEvent.keyDown(document.body, { key: "ArrowRight" });
    expect(seek).not.toHaveBeenCalled();
  });
});
