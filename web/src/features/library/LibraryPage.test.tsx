import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../api";
import { expectNoAxeViolations } from "../../test/axe";
import { job } from "../../test/fixtures";
import { renderPage } from "../../test/render";
import type { Job } from "../../types";
import { startTour } from "../tour";
import { LAST_JOB_KEY } from "./jobInfo";
import { LibraryPage } from "./LibraryPage";

vi.mock("../../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../../api")>();
  return {
    ...original,
    api: {
      ...original.api,
      deleteJob: vi.fn(),
      cancelJob: vi.fn(),
      createFromUrl: vi.fn(),
      importJob: vi.fn(),
      browse: vi.fn(),
    },
  };
});

vi.mock("../tour", () => ({ startTour: vi.fn(), TourOffer: () => null }));

const ready: Job = { ...job, id: "a", source: "Caroline breaks up.webm", created: "2026-07-26T12:00:00+00:00" };
const running: Job = {
  ...job,
  id: "b",
  source: "Randy toilets.webm",
  created: "2026-07-27T12:00:00+00:00",
  status: "running",
  percent: 40,
  has_analysis: false,
  has_report: false,
  summary: null,
  meta: null,
  options: { phone_engine: "timit61", separate_dialogue: true },
  progress: [
    { at: "2026-07-27T12:00:01+00:00", message: "Extracting audio…" },
    { at: "2026-07-27T12:00:02+00:00", message: "Transcribing with faster-whisper small…" },
    { at: "2026-07-27T12:00:03+00:00", message: "Segment 3/10: phones + alignment…" },
  ],
  last_message: "Segment 3/10: phones + alignment…",
};
const failed: Job = {
  ...job,
  id: "c",
  source: "Cut Off.mp4",
  created: "2026-07-25T12:00:00+00:00",
  status: "error",
  error: "HTTP Error 403: Forbidden",
  source_url: "https://youtu.be/abc",
  has_analysis: false,
  summary: null,
};

const row = (name: string | RegExp) => screen.getByRole("row", { name });

beforeEach(() => {
  vi.mocked(api.deleteJob).mockReset().mockResolvedValue(undefined);
  vi.mocked(api.cancelJob).mockReset().mockResolvedValue(running);
  vi.mocked(api.createFromUrl).mockReset().mockResolvedValue({ ...failed, id: "d", status: "queued" });
  vi.mocked(api.importJob).mockReset();
  vi.mocked(api.browse).mockReset();
  vi.mocked(startTour).mockReset();
});

describe("LibraryPage", () => {
  it("teaches the first steps when there are no analyses, and offers the tour", async () => {
    const { container } = renderPage(<LibraryPage />);

    expect(await screen.findByRole("heading", { name: "Your library is empty" })).toBeInTheDocument();
    expect(screen.getByText(/1\. Add a clip/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Analyze your first clip/ })).toHaveAttribute("href", "/new");

    await userEvent.click(screen.getByRole("button", { name: "Take the 1-minute tour" }));
    expect(startTour).toHaveBeenCalledTimes(1);
    await expectNoAxeViolations(container);
  });

  it("the New analysis button is the tour's anchor", async () => {
    const { container } = renderPage(<LibraryPage />, { jobs: [ready] });
    await screen.findByRole("grid", { name: "Your analyses" });
    const anchor = container.querySelector('[data-tour="library-new"]');
    expect(anchor).not.toBeNull();
    expect(within(anchor as HTMLElement).getByRole("link", { name: /New analysis/ })).toHaveAttribute(
      "href",
      "/new",
    );
  });

  it("lists every analysis with its status in words, and where a running one is", async () => {
    const { container } = renderPage(<LibraryPage />, { jobs: [ready, running, failed] });
    await screen.findByRole("grid", { name: "Your analyses" });

    expect(within(row(/Caroline/)).getByText("Ready")).toBeInTheDocument();
    expect(within(row(/Randy/)).getByText("Analyzing")).toBeInTheDocument();
    expect(within(row(/Randy/)).getByText("40 % · Hear the phones · phrase 3 of 10")).toBeInTheDocument();
    expect(within(row(/Cut Off/)).getByText("Error")).toBeInTheDocument();
    expect(within(row(/Cut Off/)).getByText(/403: Forbidden/)).toBeInTheDocument();
    expect(screen.getByText("3 analyses")).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("newest first by default; the order can change", async () => {
    renderPage(<LibraryPage />, { jobs: [ready, running, failed] });
    await screen.findByRole("grid");
    const names = () => screen.getAllByRole("row").map((r) => r.textContent ?? "");
    expect(names()[0]).toMatch(/Randy/);
    expect(names()[2]).toMatch(/Cut Off/);

    await userEvent.click(screen.getByRole("button", { name: /Sort/ }));
    await userEvent.click(screen.getByRole("option", { name: /Name/ }));
    expect(names().map((n) => n.match(/Caroline|Cut Off|Randy/)?.[0])).toEqual(["Caroline", "Cut Off", "Randy"]);
  });

  it("search narrows the list and can be cleared", async () => {
    renderPage(<LibraryPage />, { jobs: [ready, running, failed] });
    await screen.findByRole("grid");

    await userEvent.type(screen.getByRole("searchbox", { name: "Search analyses" }), "randy");
    expect(screen.getAllByRole("row")).toHaveLength(1);
    expect(screen.getByText("1 of 3 analyses")).toBeInTheDocument();

    await userEvent.clear(screen.getByRole("searchbox", { name: "Search analyses" }));
    await userEvent.type(screen.getByRole("searchbox", { name: "Search analyses" }), "zzz");
    expect(screen.getByText(/No analysis matches/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Clear the search" }));
    expect(screen.getAllByRole("row")).toHaveLength(3);
  });

  it("opening a row goes to its analysis and remembers it", async () => {
    const { history } = renderPage(<LibraryPage />, { jobs: [ready] });
    await screen.findByRole("grid");

    await userEvent.click(screen.getByText("Caroline breaks up.webm"));
    expect(history.at(-1)).toBe("/analysis/a");
    expect(window.localStorage.getItem(LAST_JOB_KEY)).toBe("a");
  });

  it("offers to continue the analysis viewed last", async () => {
    window.localStorage.setItem(LAST_JOB_KEY, "a");
    const { history } = renderPage(<LibraryPage />, { jobs: [ready, running] });
    const heading = await screen.findByRole("heading", { name: "Continue where you left off" });
    const card = heading.closest("section") as HTMLElement;
    expect(within(card).getByText("Caroline breaks up.webm")).toBeInTheDocument();
    await userEvent.click(within(card).getByRole("button", { name: "Open" }));
    expect(history.at(-1)).toBe("/analysis/a");
  });

  it("updates live as the server pushes changes", async () => {
    const { jobs } = renderPage(<LibraryPage />, { jobs: [running] });
    await screen.findByText("Analyzing");

    act(() => jobs.socket.push({ type: "job", job: { ...running, status: "done", percent: 100 } }));
    expect(await within(row(/Randy/)).findByText("Ready")).toBeInTheDocument();

    act(() => jobs.socket.push({ type: "deleted", id: running.id }));
    expect(await screen.findByRole("heading", { name: "Your library is empty" })).toBeInTheDocument();
  });

  it("deletes only after a confirmation that says what is kept", async () => {
    renderPage(<LibraryPage />, { jobs: [ready] });
    await screen.findByRole("grid");

    await userEvent.click(screen.getByRole("button", { name: /More actions for Caroline/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: /Delete/ }));
    const dialog = screen.getByRole("alertdialog");
    expect(within(dialog).getByText(/original video or audio file is not touched/)).toBeInTheDocument();

    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(api.deleteJob).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: /More actions for Caroline/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: /Delete/ }));
    await userEvent.click(within(screen.getByRole("alertdialog")).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(api.deleteJob).toHaveBeenCalledWith("a"));
  });

  it("an imported analysis is only removed from the list", async () => {
    renderPage(<LibraryPage />, { jobs: [{ ...ready, imported: true }] });
    await screen.findByRole("grid");
    await userEvent.click(screen.getByRole("button", { name: /More actions for Caroline/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: /Remove from the list/ }));
    expect(within(screen.getByRole("alertdialog")).getByText(/stays on disk/)).toBeInTheDocument();
  });

  it("a running analysis can be cancelled from its menu", async () => {
    renderPage(<LibraryPage />, { jobs: [running] });
    await screen.findByRole("grid");
    await userEvent.click(screen.getByRole("button", { name: /More actions for Randy/ }));
    await userEvent.click(screen.getByRole("menuitem", { name: /Cancel the analysis/ }));
    await waitFor(() => expect(api.cancelJob).toHaveBeenCalledWith("b"));
  });

  it("retrying a failed download starts it again and drops the failed row", async () => {
    renderPage(<LibraryPage />, { jobs: [failed] });
    await screen.findByRole("grid");
    await userEvent.click(within(row(/Cut Off/)).getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(api.createFromUrl).toHaveBeenCalledWith(failed.source_url, failed.options, false));
    await waitFor(() => expect(api.deleteJob).toHaveBeenCalledWith("c"));
  });

  it("imports existing results from a folder and opens them", async () => {
    vi.mocked(api.browse).mockResolvedValue({
      path: "/home/me",
      parent: null,
      home: "/home/me",
      dirs: [
        { name: "out", path: "/home/me/out", has_analysis: true },
        { name: "videos", path: "/home/me/videos", has_analysis: false },
      ],
      files: [],
    });
    vi.mocked(api.importJob).mockResolvedValue({ ...ready, id: "imp", imported: true });
    const { history } = renderPage(<LibraryPage />, { jobs: [ready] });
    await screen.findByRole("grid");

    await userEvent.click(screen.getByRole("button", { name: "Import results…" }));
    const tree = await screen.findByRole("treegrid", { name: "Folders with results" });
    await userEvent.click(within(tree).getByText("out"));

    await waitFor(() => expect(api.importJob).toHaveBeenCalledWith("/home/me/out"));
    await waitFor(() => expect(history.at(-1)).toBe("/analysis/imp"));
  });
});
