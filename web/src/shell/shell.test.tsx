import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { BREAKPOINTS } from "../hooks/useMediaQuery";
import { AppRoutes } from "../routes";
import { expectNoAxeViolations } from "../test/axe";
import { phoneScreen, setScreen } from "../test/media";
import { fullReference, renderPage } from "../test/render";
import { startTour } from "../features/tour";
import { AppShell } from "./AppShell";

vi.mock("../features/tour", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../features/tour")>()),
  startTour: vi.fn(),
}));

const app = (
  <AppShell>
    <AppRoutes />
  </AppShell>
);

describe("AppShell", () => {
  it("has a skip link, a labelled main navigation and one <main>", () => {
    renderPage(app, { path: "/learn", reference: fullReference });
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
    const nav = screen.getByRole("navigation", { name: "Main" });
    for (const name of ["Library", "Learn", "Practice", "Insights", "Settings"]) {
      expect(within(nav).getByRole("link", { name })).toBeInTheDocument();
    }
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });

  it("marks the current section and navigates client-side", async () => {
    const { history } = renderPage(app, { path: "/learn", reference: fullReference });
    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).getByRole("link", { name: "Learn" })).toHaveAttribute("aria-current", "page");

    await userEvent.click(within(nav).getByRole("link", { name: "Practice" }));
    expect(history.at(-1)).toBe("/practice");
    expect(await screen.findByRole("heading", { level: 1, name: "Practice" })).toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Practice" })).toHaveAttribute("aria-current", "page");
  });

  it("collapses the rail to icons but keeps every name", async () => {
    renderPage(app, { path: "/learn", reference: fullReference });
    await userEvent.click(screen.getByRole("button", { name: "Collapse the menu" }));
    const nav = screen.getByRole("navigation", { name: "Main" });
    expect(within(nav).getByRole("link", { name: "Insights" })).toBeInTheDocument();
    expect(within(nav).queryByText("Insights")).not.toBeInTheDocument();
  });

  it("lists the keyboard shortcuts from Help", async () => {
    renderPage(app, { path: "/learn", reference: fullReference });
    await userEvent.click(screen.getByRole("button", { name: "Help" }));
    await userEvent.click(await screen.findByRole("menuitem", { name: "Keyboard shortcuts" }));
    const dialog = await screen.findByRole("dialog", { name: "Keyboard shortcuts" });
    expect(within(dialog).getByText("replay the selected word")).toBeInTheDocument();
  });

  it("restarts the tour from Help and has it hand the focus back to Help, not to the menu", async () => {
    renderPage(app, { path: "/learn", reference: fullReference });
    const help = screen.getByRole("button", { name: "Help" });
    await userEvent.click(help);
    await userEvent.click(await screen.findByRole("menuitem", { name: "Restart the tour" }));
    expect(startTour).toHaveBeenCalledWith({ returnFocus: help });
  });

  it("explains the engines from the backend's own notes", async () => {
    renderPage(app, { path: "/learn", reference: fullReference });
    await userEvent.click(screen.getByRole("button", { name: "Help" }));
    await userEvent.click(await screen.findByRole("menuitem", { name: "About the engines" }));
    const dialog = await screen.findByRole("dialog", { name: "About the phone engines" });
    expect(within(dialog).getByText(fullReference.options.phone_engine_notes!.timit61)).toBeInTheDocument();
  });

  it("says so when a page does not exist", () => {
    renderPage(app, { path: "/nowhere", reference: fullReference });
    expect(screen.getByRole("heading", { level: 1, name: "This page does not exist" })).toBeInTheDocument();
  });
});

describe("AppShell on a narrow screen", () => {
  it("collapses the navigation into a Menu button that opens it in a drawer", async () => {
    phoneScreen();
    const { container, history } = renderPage(app, { path: "/learn", reference: fullReference });
    // the skip link stays first, and the page keeps the whole width: no rail
    expect(screen.getByRole("link", { name: "Skip to content" })).toHaveAttribute("href", "#main");
    expect(screen.queryByRole("navigation", { name: "Main" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("main")).toHaveLength(1);

    const menu = screen.getByRole("button", { name: "Menu" });
    await userEvent.click(menu);
    const drawer = await screen.findByRole("dialog", { name: "Menu" });
    const nav = within(drawer).getByRole("navigation", { name: "Main" });
    for (const name of ["Library", "Learn", "Practice", "Insights", "Settings"]) {
      expect(within(nav).getByRole("link", { name })).toBeInTheDocument();
    }
    expect(within(nav).getByRole("link", { name: "Learn" })).toHaveAttribute("aria-current", "page");
    await expectNoAxeViolations(container);

    // choosing a page goes there, closes the drawer and hands the focus back to Menu
    await userEvent.click(within(nav).getByRole("link", { name: "Practice" }));
    expect(history.at(-1)).toBe("/practice");
    expect(await screen.findByRole("heading", { level: 1, name: "Practice" })).toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "Menu" })).not.toBeInTheDocument();
    await waitFor(() => expect(menu).toHaveFocus());
  });

  it("closes the drawer with Esc and returns the focus to the Menu button", async () => {
    phoneScreen();
    renderPage(app, { path: "/learn", reference: fullReference });
    const menu = screen.getByRole("button", { name: "Menu" });
    await userEvent.click(menu);
    await screen.findByRole("dialog", { name: "Menu" });
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Menu" })).not.toBeInTheDocument();
    await waitFor(() => expect(menu).toHaveFocus());
  });

  it("opens Help's dialogs and the tour from the drawer once it has closed", async () => {
    phoneScreen();
    renderPage(app, { path: "/learn", reference: fullReference });
    const menu = screen.getByRole("button", { name: "Menu" });

    await userEvent.click(menu);
    await userEvent.click(
      within(await screen.findByRole("dialog", { name: "Menu" })).getByRole("button", { name: "Help" }),
    );
    await userEvent.click(await screen.findByRole("menuitem", { name: "Keyboard shortcuts" }));
    const shortcuts = await screen.findByRole("dialog", { name: "Keyboard shortcuts" });
    expect(screen.queryByRole("dialog", { name: "Menu" })).not.toBeInTheDocument();
    await userEvent.click(within(shortcuts).getByRole("button", { name: "Close" }));

    await userEvent.click(menu);
    await userEvent.click(
      within(await screen.findByRole("dialog", { name: "Menu" })).getByRole("button", { name: "Help" }),
    );
    await userEvent.click(await screen.findByRole("menuitem", { name: "Restart the tour" }));
    // the tour starts after the drawer is gone and hands the focus back to Menu
    await waitFor(() => expect(startTour).toHaveBeenCalledWith({ returnFocus: menu }));
  });

  it("switches between the rail and the Menu button when the window is resized", async () => {
    const resize = setScreen([]);
    renderPage(app, { path: "/learn", reference: fullReference });
    expect(screen.getByRole("navigation", { name: "Main" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Menu" })).not.toBeInTheDocument();

    resize([BREAKPOINTS.compactNav]);
    expect(screen.queryByRole("navigation", { name: "Main" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Menu" })).toBeInTheDocument();
  });
});

describe("Settings page", () => {
  it("switches palette, pattern emphasis, text size and appearance on <html>", async () => {
    const { container } = renderPage(app, { path: "/settings", reference: fullReference });
    const html = document.documentElement;

    await userEvent.click(await screen.findByRole("radio", { name: /Color-vision friendly/ }));
    expect(html).toHaveAttribute("data-palette", "cvd");
    expect(html).toHaveAttribute("data-patterns", "on");
    expect(screen.getByRole("switch", { name: /Show patterns as well as colors/ })).toBeChecked();

    await userEvent.click(screen.getByRole("switch", { name: /Show patterns as well as colors/ }));
    expect(html).toHaveAttribute("data-patterns", "off");
    await userEvent.click(screen.getByRole("button", { name: "Follow the Colors setting again" }));
    expect(html).toHaveAttribute("data-patterns", "on");

    const textSize = screen.getByRole("radiogroup", { name: "Text size" });
    await userEvent.click(within(textSize).getByRole("radio", { name: "Larger" }));
    expect(html).toHaveAttribute("data-text", "larger");

    const appearance = screen.getByRole("radiogroup", { name: "Appearance" });
    await userEvent.click(within(appearance).getByRole("radio", { name: "Dark" }));
    expect(html).toHaveAttribute("data-theme", "dark");

    await expectNoAxeViolations(container);
  });

  it("shows the four families under each simulated color blindness", async () => {
    renderPage(app, { path: "/settings", reference: fullReference });
    await screen.findByRole("heading", { level: 1, name: "Settings" });
    for (const name of ["Protanopia", "Deuteranopia", "Tritanopia"]) {
      expect(screen.getByText(name)).toBeInTheDocument();
    }
    // each family keeps its name and icon in every preview, not only its color
    expect(screen.getAllByText(fullReference.families[1].label).length).toBeGreaterThanOrEqual(5);
  });

  it("the quick Display popover in the rail changes the same settings", async () => {
    renderPage(app, { path: "/learn", reference: fullReference });
    await userEvent.click(screen.getByRole("button", { name: "Display" }));
    const dialog = await screen.findByRole("dialog", { name: "Display" });
    await userEvent.click(within(dialog).getByRole("radio", { name: "Color-vision friendly" }));
    expect(document.documentElement).toHaveAttribute("data-palette", "cvd");
  });
});
