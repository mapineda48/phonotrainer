import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { driver } from "driver.js";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { TOUR } from "../../didactic/tour-ids";
import { expectNoAxeViolations } from "../../test/axe";
import { renderPage } from "../../test/render";
import { buildDriveSteps, startTour, stripPopupAria, TOUR_KEY, TourOffer } from "./index";
import { resolveSteps, TOUR_STEPS } from "./steps";

vi.mock("driver.js", () => ({
  driver: vi.fn(() => ({ drive: vi.fn(), destroy: vi.fn(), isActive: () => true })),
}));

function anchors(...ids: string[]) {
  return render(
    <div>
      {ids.map((id) => (
        <div key={id} data-tour={id}>
          {id}
        </div>
      ))}
    </div>,
  );
}

beforeEach(() => vi.mocked(driver).mockClear());
afterEach(() => document.documentElement.removeAttribute("data-motion"));

describe("tour steps", () => {
  it("highlights what is on screen, centers the essentials and skips the rest", () => {
    anchors(TOUR.nav, TOUR.learnNav);
    const steps = resolveSteps();
    const titles = steps.map((s) => s.step.title);
    expect(titles).toContain("Find your way");
    expect(titles).toContain("A lesson for every word"); // centered: must not be missed
    expect(titles).toContain("Colors that suit your eyes");
    expect(titles).not.toContain("The transcript"); // no analysis open: skipped
    expect(titles).not.toContain("Analyze a clip");
    expect(steps.find((s) => s.step.title === "Find your way")?.element).not.toBeNull();
    expect(steps.find((s) => s.step.title === "A lesson for every word")?.element).toBeNull();
    expect(steps[0].step.title).toBe("Welcome to PhonoTrainer");
    expect(steps.at(-1)?.step.title).toBe("You're ready");
  });

  it("uses every anchor when the whole workspace is on screen", () => {
    anchors(...Object.values(TOUR));
    expect(resolveSteps()).toHaveLength(TOUR_STEPS.length);
    expect(buildDriveSteps().filter((step) => step.element)).toHaveLength(
      TOUR_STEPS.filter((step) => step.anchor).length,
    );
  });
});

describe("startTour", () => {
  it("drives driver.js with the steps of this screen and remembers the tour was taken", () => {
    anchors(TOUR.nav);
    startTour();
    expect(driver).toHaveBeenCalledTimes(1);
    const config = vi.mocked(driver).mock.calls[0][0]!;
    expect(config.steps?.length).toBe(resolveSteps().length);
    expect(config.animate).toBe(true);
    expect(localStorage.getItem(TOUR_KEY)).toBe("taken");
    const instance = vi.mocked(driver).mock.results[0].value;
    expect(instance.drive).toHaveBeenCalled();
  });

  it("does not leave popup ARIA on what it highlights (not allowed on a landmark or a plain box)", () => {
    const { container } = anchors(TOUR.nav);
    startTour();
    const config = vi.mocked(driver).mock.calls[0][0]!;
    // what driver.js does to the highlighted element
    const element = container.querySelector('[data-tour="nav"]')!;
    element.classList.add("driver-active-element");
    element.setAttribute("aria-haspopup", "dialog");
    element.setAttribute("aria-expanded", "true");
    element.setAttribute("aria-controls", "driver-popover-content");
    config.onHighlighted?.(element, config.steps![0], {} as never);
    expect(element).not.toHaveAttribute("aria-expanded");
    expect(element).not.toHaveAttribute("aria-haspopup");
    expect(element).not.toHaveAttribute("aria-controls");
    stripPopupAria(); // idempotent
  });

  it("hands the focus back to the given element when it ends (Esc, ×, Done)", async () => {
    const { container } = anchors(TOUR.nav);
    const help = document.createElement("button");
    help.textContent = "Help";
    container.appendChild(help);
    startTour({ returnFocus: help });
    const config = vi.mocked(driver).mock.calls[0][0]!;
    config.onDestroyStarted?.(undefined, config.steps![0], {} as never);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(help).toHaveFocus();
  });

  it("does not animate when motion is reduced", () => {
    document.documentElement.setAttribute("data-motion", "reduce");
    startTour();
    const config = vi.mocked(driver).mock.calls[0][0]!;
    expect(config.animate).toBe(false);
    expect(config.smoothScroll).toBe(false);
  });
});

describe("TourOffer", () => {
  it("offers the tour once and remembers a “not now”", async () => {
    const user = userEvent.setup();
    const { container, unmount } = renderPage(<TourOffer />);
    expect(screen.getByRole("region", { name: "Guided tour" })).toBeInTheDocument();
    await expectNoAxeViolations(container);
    await user.click(screen.getByRole("button", { name: "Not now" }));
    expect(screen.queryByRole("region", { name: "Guided tour" })).not.toBeInTheDocument();
    expect(localStorage.getItem(TOUR_KEY)).toBe("dismissed");
    unmount();
    renderPage(<TourOffer />);
    expect(screen.queryByRole("region", { name: "Guided tour" })).not.toBeInTheDocument();
  });

  it("starts the tour and gets out of the way", async () => {
    const user = userEvent.setup();
    renderPage(<TourOffer />);
    await user.click(screen.getByRole("button", { name: "Take the tour" }));
    expect(driver).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("region", { name: "Guided tour" })).not.toBeInTheDocument();
  });
});
