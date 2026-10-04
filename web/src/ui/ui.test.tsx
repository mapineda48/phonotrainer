import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";

import { Explain } from "../didactic/Explain";
import { expectNoAxeViolations } from "../test/axe";
import { fullReference, renderPage } from "../test/render";
import {
  ChartFrame,
  ConfirmDialog,
  DataTable,
  FamilyInlineIcon,
  familyUnderlineClass,
  Ipa,
  markOf,
  markOfPhenomenon,
  MetricMeter,
  PhenomenonBadge,
  PhenomenonIcon,
  PracticeBadge,
  RichText,
  Segmented,
  Select,
  splitIpa,
} from ".";

describe("IPA text", () => {
  it("finds the IPA in backend descriptions", () => {
    expect(splitIpa("A full vowel reduces to schwa. does → dəz")).toEqual([
      { text: "A full vowel reduces to schwa. does →", ipa: false },
      { text: " ", ipa: false },
      { text: "dəz", ipa: true },
    ]);
    expect(splitIpa("dictionary /wɔtɚ/, said [wɔɾɚ]").filter((p) => p.ipa).map((p) => p.text)).toEqual([
      "/wɔtɚ/",
      "[wɔɾɚ]",
    ]);
  });

  it("marks IPA spans with the IPA language tag and font", () => {
    renderPage(
      <p>
        <RichText text="that → ðæt̚" /> <Ipa kind="phonemic">ðæt</Ipa>
      </p>,
    );
    const spans = document.querySelectorAll('[lang="und-fonipa"]');
    expect([...spans].map((s) => s.textContent)).toEqual(["ðæt̚", "/ðæt/"]);
    expect(spans[0]).toHaveClass("ipa-text");
  });
});

describe("family encodings", () => {
  it("every family has its own underline style class; unknown is the neutral mark, never the lexical one", () => {
    expect(familyUnderlineClass("td")).toBe("fam-u fam-u-td");
    expect(familyUnderlineClass("boundary")).toBe("fam-u fam-u-boundary");
    expect(familyUnderlineClass("lexical")).toBe("fam-u fam-u-lexical");
    expect(familyUnderlineClass("contraction")).toBe("fam-u fam-u-none");
    expect(familyUnderlineClass(null)).toBe("");
    expect(markOf(undefined)).toBe("none");
  });

  it("a phenomenon's mark: its family, the contraction's own, or the neutral one", () => {
    const familyOf = fullReference.family_of;
    expect(markOfPhenomenon("flapping", familyOf)).toBe("td");
    expect(markOfPhenomenon("contraction_lex", familyOf)).toBe("lexical");
    expect(markOfPhenomenon("word_elision", familyOf)).toBe("none");
  });

  it("the inline icon after a word uses the same neutral mark as the badge", () => {
    const { container } = renderPage(
      <>
        <FamilyInlineIcon family="none" />
        <FamilyInlineIcon family="lexical" />
        <PhenomenonIcon name="word_elision" />
      </>,
      { reference: fullReference },
    );
    const [inlineNone, inlineLexical, icon] = Array.from(container.querySelectorAll("svg.lucide"));
    expect(inlineNone).toHaveClass("lucide-circle-dashed");
    expect(inlineLexical).not.toHaveClass("lucide-circle-dashed");
    expect(icon).toHaveClass("lucide-circle-dashed");
  });

  it("a phenomenon badge names the phenomenon and its advice (never color alone)", () => {
    renderPage(<PhenomenonBadge name="flapping" showPractice />, { reference: fullReference });
    expect(screen.getByText(fullReference.labels.flapping)).toBeInTheDocument();
    expect(screen.getByText("Safe to produce")).toBeInTheDocument();
  });

  it("a phenomenon without a family does not borrow the lexical contraction's mark", () => {
    const { container } = renderPage(
      <>
        <PhenomenonBadge name="word_elision" />
        <PhenomenonBadge name="contraction_lex" />
      </>,
      { reference: fullReference },
    );
    const [elided, lexical] = Array.from(container.querySelectorAll("svg.lucide"));
    expect(elided).toHaveClass("lucide-circle-dashed");
    expect(lexical).not.toHaveClass("lucide-circle-dashed");
  });

  it("the practice badge reads as words, with the register for screen readers", () => {
    renderPage(<PracticeBadge practice={{ practice: "understand", register: "marked", why: "" }} showRegister />);
    expect(screen.getByText("Recognize only")).toBeInTheDocument();
    expect(screen.getByText("marked")).toBeInTheDocument();
  });
});

describe("Explain (what's this?)", () => {
  it("opens a glossary entry from a keyboard-reachable button", async () => {
    renderPage(<Explain term="weak-form">Weak form</Explain>);
    const button = screen.getByRole("button", { name: "What's this: Weak form" });
    await userEvent.click(button);
    const dialog = await screen.findByRole("dialog", { name: "Weak form" });
    expect(within(dialog).getByText(/strong form and a weak form/)).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("explains a phenomenon from the backend, with the report's advice and a link to Learn", async () => {
    renderPage(<Explain phenomenon="t_deletion" />, { reference: fullReference });
    await userEvent.click(screen.getByRole("button", { name: /What's this/ }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(fullReference.practice!.t_deletion.why)).toBeInTheDocument();
    expect(within(dialog).getByRole("link", { name: /More examples in Learn/ })).toHaveAttribute(
      "href",
      "/learn/t_deletion",
    );
  });

  it("renders just the label when the term is unknown", () => {
    renderPage(<Explain term="no-such-term">Plain</Explain>);
    expect(screen.getByText("Plain")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});

describe("MetricMeter", () => {
  it("exposes the value and the published reference to assistive technology", () => {
    renderPage(
      <MetricMeter
        label="Words that change at least one sound"
        value={0.721}
        detail="124 of 172 words"
        reference={{ low: 0.6, text: "> 60 %", source: "Johnson 2004" }}
      />,
    );
    const meter = screen.getByRole("meter", { name: "Words that change at least one sound" });
    expect(meter).toHaveAttribute("aria-valuetext", "72.1 %; published figure > 60 % (Johnson 2004)");
    expect(screen.getByText("124 of 172 words")).toBeInTheDocument();
  });
});

describe("ChartFrame", () => {
  it("always offers the same data as a table", async () => {
    renderPage(
      <ChartFrame
        title="Phenomena in this clip"
        summary="Word boundary is the most frequent family."
        table={<DataTable columns={["Family", "Count"]} rows={[["Word boundary", 24]]} />}
      >
        <svg role="img" aria-label="bar chart" />
      </ChartFrame>,
    );
    expect(screen.getByRole("img", { name: "bar chart" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Show as table" }));
    expect(screen.getByRole("table")).toHaveTextContent("Word boundary24");
  });
});

describe("controls", () => {
  function SpeedDemo() {
    const [speed, setSpeed] = useState<"0.5" | "1">("1");
    return (
      <>
        <Segmented
          label="Speed"
          value={speed}
          onChange={setSpeed}
          options={[
            { id: "0.5", label: "0.5×" },
            { id: "1", label: "1×" },
          ]}
        />
        <p>speed {speed}</p>
      </>
    );
  }

  it("a segmented control is a labelled radio group", async () => {
    const { container } = renderPage(<SpeedDemo />);
    const group = screen.getByRole("radiogroup", { name: "Speed" });
    await userEvent.click(within(group).getByRole("radio", { name: "0.5×" }));
    expect(screen.getByText("speed 0.5")).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("a segmented control is one Tab stop, and the arrow keys move and check (radio pattern)", async () => {
    renderPage(
      <>
        <button type="button">before</button>
        <SpeedDemo />
        <button type="button">after</button>
      </>,
    );
    await userEvent.click(screen.getByRole("button", { name: "before" }));
    await userEvent.tab();
    expect(screen.getByRole("radio", { name: "1×" })).toHaveFocus(); // the checked one
    await userEvent.keyboard("{ArrowLeft}");
    expect(screen.getByRole("radio", { name: "0.5×" })).toBeChecked();
    expect(screen.getByText("speed 0.5")).toBeInTheDocument();
    await userEvent.tab();
    expect(screen.getByRole("button", { name: "after" })).toHaveFocus();
  });

  it("a destructive confirmation focuses Cancel first and confirms on demand", async () => {
    let confirmed = false;
    renderPage(
      <ConfirmDialog
        title="Delete this analysis?"
        isOpen
        onOpenChange={() => undefined}
        confirmLabel="Delete"
        destructive
        onConfirm={() => {
          confirmed = true;
        }}
      >
        The audio file on your disk is kept.
      </ConfirmDialog>,
    );
    const dialog = screen.getByRole("alertdialog", { name: "Delete this analysis?" });
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();
    await userEvent.click(within(dialog).getByRole("button", { name: "Delete" }));
    expect(confirmed).toBe(true);
  });
});

describe("Select", () => {
  it("shows only the chosen label in its trigger, not the item's description", () => {
    renderPage(
      <Select
        label="Speech model"
        items={[
          { id: "small", label: "small", description: "Fast; the default" },
          { id: "medium", label: "medium", description: "Slower, more accurate" },
        ]}
        value="small"
        onChange={() => {}}
      />,
    );
    const trigger = screen.getByRole("button", { name: /Speech model/ });
    expect(trigger).toHaveTextContent("small");
    expect(trigger).not.toHaveTextContent("Fast; the default");
  });

  it("names each option by its label and describes it with its description", async () => {
    renderPage(
      <Select
        label="Speech model"
        items={[
          { id: "small", label: "small", description: "Fast; the default" },
          { id: "medium", label: "medium", description: "Slower, more accurate" },
        ]}
        value="small"
        onChange={() => {}}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /Speech model/ }));
    const option = screen.getByRole("option", { name: "medium" });
    expect(option).toHaveAccessibleDescription("Slower, more accurate");
  });
});
