import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../api";
import { expectNoAxeViolations } from "../../test/axe";
import { renderPage } from "../../test/render";
import type { Browse } from "../../types";
import { PathBrowser } from "./PathBrowser";

vi.mock("../../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../../api")>();
  return { ...original, api: { ...original.api, browse: vi.fn() } };
});

const home: Browse = {
  path: "/home/me",
  parent: "/home",
  home: "/home/me",
  dirs: [
    { name: "videos", path: "/home/me/videos" },
    { name: "out", path: "/home/me/out", has_analysis: true },
  ],
  files: [{ name: "intro.mp3", path: "/home/me/intro.mp3", size: 1024 }],
};
const videos: Browse = {
  path: "/home/me/videos",
  parent: "/home/me",
  home: "/home/me",
  dirs: [],
  files: [{ name: "ep1.webm", path: "/home/me/videos/ep1.webm", size: 4096 }],
};
const listings: Record<string, Browse> = {
  "/home/me": home,
  "/home/me/videos": videos,
  "/home": { path: "/home", parent: null, home: "/home/me", dirs: [{ name: "me", path: "/home/me" }], files: [] },
};

beforeEach(() => {
  vi.mocked(api.browse)
    .mockReset()
    .mockImplementation(async (path?: string) => listings[path ?? "/home/me"]);
});

describe("PathBrowser", () => {
  it("opens folders on demand and picks a file inside", async () => {
    const onPick = vi.fn();
    const { container } = renderPage(<PathBrowser mode="media" onPick={onPick} />);
    const tree = await screen.findByRole("treegrid", { name: "Folders and media files" });
    // the tree renders before the listing arrives: wait for its rows
    expect(await within(tree).findByText("intro.mp3")).toBeInTheDocument();
    expect(api.browse).toHaveBeenCalledTimes(1);

    const folder = within(tree).getByRole("row", { name: "videos" });
    expect(folder).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(folder);
    await waitFor(() => expect(folder).toHaveAttribute("aria-expanded", "true"));
    expect(api.browse).toHaveBeenCalledWith("/home/me/videos");

    await userEvent.click(await within(tree).findByText("ep1.webm"));
    expect(onPick).toHaveBeenCalledWith("/home/me/videos/ep1.webm");
    await expectNoAxeViolations(container);
  });

  it("works from the keyboard: arrows open a folder, Enter picks", async () => {
    const onPick = vi.fn();
    renderPage(<PathBrowser mode="media" onPick={onPick} />);
    const tree = await screen.findByRole("treegrid");
    await within(tree).findByRole("row", { name: "videos" });

    await userEvent.tab(); // the "Up one folder" button
    await userEvent.tab(); // into the tree, on its first row
    expect(within(tree).getByRole("row", { name: "videos" })).toHaveFocus();
    await userEvent.keyboard("{ArrowRight}");
    await within(tree).findByText("ep1.webm");
    await userEvent.keyboard("{ArrowDown}{Enter}");
    expect(onPick).toHaveBeenCalledWith("/home/me/videos/ep1.webm");
  });

  it("in folder mode, only folders with results are picked; others just open", async () => {
    const onPick = vi.fn();
    renderPage(<PathBrowser mode="dir" onPick={onPick} />);
    const tree = await screen.findByRole("treegrid", { name: "Folders with results" });
    expect(await within(tree).findByText(/has results/)).toBeInTheDocument();
    expect(within(tree).queryByText("intro.mp3")).not.toBeInTheDocument();

    await userEvent.click(within(tree).getByText("videos"));
    expect(onPick).not.toHaveBeenCalled();
    expect(await within(tree).findByText("No folders here.")).toBeInTheDocument();

    await userEvent.click(within(tree).getByText("out"));
    expect(onPick).toHaveBeenCalledWith("/home/me/out");
  });

  it("goes up one folder", async () => {
    renderPage(<PathBrowser mode="media" onPick={vi.fn()} />);
    await within(await screen.findByRole("treegrid")).findByRole("row", { name: "videos" });
    await userEvent.click(screen.getByRole("button", { name: "Up one folder" }));
    expect(await screen.findByText("/home")).toBeInTheDocument();
    expect(api.browse).toHaveBeenLastCalledWith("/home");
  });

  it("explains a folder it cannot open", async () => {
    vi.mocked(api.browse).mockRejectedValue(new Error("outside the allowed folders"));
    renderPage(<PathBrowser mode="media" onPick={vi.fn()} />);
    expect(await screen.findByText("outside the allowed folders")).toBeInTheDocument();
  });
});
