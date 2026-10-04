import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "../../api";
import { expectNoAxeViolations } from "../../test/axe";
import { job } from "../../test/fixtures";
import { fullReference, renderPage } from "../../test/render";
import type { Job } from "../../types";
import { NewAnalysisPage } from "./NewAnalysisPage";

vi.mock("../../api", async (importOriginal) => {
  const original = await importOriginal<typeof import("../../api")>();
  return {
    ...original,
    api: {
      ...original.api,
      createJob: vi.fn(),
      uploadJob: vi.fn(),
      createFromUrl: vi.fn(),
      importJob: vi.fn(),
      cancelJob: vi.fn(),
      browse: vi.fn(),
    },
  };
});

const defaults = fullReference.options.defaults;
const queued: Job = {
  ...job,
  id: "new",
  source: "ep1.webm",
  status: "queued",
  percent: 0,
  has_analysis: false,
  has_report: false,
  summary: null,
  meta: null,
  options: defaults,
  progress: [],
  last_message: null,
};

const render = () => renderPage(<NewAnalysisPage />, { reference: fullReference, path: "/new" });
const start = () => userEvent.click(screen.getByRole("button", { name: "Start analysis" }));
const typePath = (path: string) => userEvent.type(screen.getByRole("textbox", { name: "File path" }), path);

beforeEach(() => {
  for (const fn of [api.createJob, api.uploadJob, api.createFromUrl, api.importJob, api.cancelJob, api.browse]) {
    vi.mocked(fn).mockReset();
  }
  vi.mocked(api.createJob).mockResolvedValue(queued);
  vi.mocked(api.uploadJob).mockResolvedValue(queued);
  vi.mocked(api.createFromUrl).mockResolvedValue({ ...queued, source_url: "https://youtu.be/abc" });
});

describe("NewAnalysisPage", () => {
  it("explains each engine honestly, license included, and preselects the default", async () => {
    const { container } = render();

    const engines = screen.getByRole("radiogroup", { name: "Phone engine" });
    expect(within(engines).getByRole("radio", { name: /TIMIT-61/ })).toBeChecked();
    expect(within(engines).getByText(/non-commercial research/)).toBeInTheDocument();
    expect(within(engines).getByText(/tends to hear the dictionary form/)).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("cannot start before a clip is chosen", async () => {
    render();
    expect(screen.getByRole("button", { name: "Start analysis" })).toBeDisabled();
    await typePath("/home/me/ep1.webm");
    expect(screen.getByRole("button", { name: "Start analysis" })).toBeEnabled();
  });

  it("sends the backend's defaults: TIMIT-61, the engine's attraction, dialogue separated", async () => {
    render();
    await typePath("/home/me/ep1.webm");
    await start();

    await waitFor(() => expect(api.createJob).toHaveBeenCalled());
    expect(vi.mocked(api.createJob).mock.calls[0]).toEqual([
      "/home/me/ep1.webm",
      { whisper_model: "small", phone_engine: "timit61", language: "en", attraction: null, separate_dialogue: true },
    ]);
  });

  it("every option can be changed: engine, attraction (three-way), separation, speech model", async () => {
    render();
    await userEvent.click(screen.getByRole("radio", { name: /espeak/ }));
    await userEvent.click(
      within(screen.getByRole("radiogroup", { name: "Phonetic attraction" })).getByRole("radio", { name: "Off" }),
    );
    await userEvent.click(screen.getByRole("switch", { name: /Separate the dialogue/ }));
    await userEvent.click(screen.getByRole("button", { name: /Speech model/ }));
    // each model is named by itself and described by its trade-off
    const medium = screen.getByRole("option", { name: "medium" });
    expect(medium).toHaveAccessibleDescription("most accurate, slowest");
    await userEvent.click(medium);
    expect(screen.getByRole("button", { name: /Speech model/ })).not.toHaveTextContent("slowest");
    await typePath("/home/me/ep1.webm");
    await start();

    await waitFor(() => expect(api.createJob).toHaveBeenCalled());
    expect(vi.mocked(api.createJob).mock.calls[0][1]).toEqual({
      whisper_model: "medium",
      phone_engine: "espeak",
      language: "en",
      attraction: false,
      separate_dialogue: false,
    });
  });

  it("analyzes a YouTube link, optionally audio only", async () => {
    render();
    await userEvent.click(screen.getByRole("tab", { name: /YouTube link/ }));
    await userEvent.type(screen.getByRole("textbox", { name: "YouTube URL" }), "https://youtu.be/abc");
    await userEvent.click(screen.getByRole("switch", { name: /audio only/ }));
    await start();

    await waitFor(() => expect(api.createFromUrl).toHaveBeenCalledWith("https://youtu.be/abc", defaults, true));
  });

  it("uploads a chosen file", async () => {
    const { container } = render();
    await userEvent.click(screen.getByRole("tab", { name: /Upload a file/ }));
    const file = new File(["data"], "clip.mp3", { type: "audio/mpeg" });
    await userEvent.upload(container.querySelector('input[type="file"]') as HTMLInputElement, file);

    expect(screen.getByText("clip.mp3")).toBeInTheDocument();
    await start();
    await waitFor(() => expect(api.uploadJob).toHaveBeenCalledWith(file, defaults));
  });

  it("refuses a file that is not video or audio", async () => {
    const user = userEvent.setup({ applyAccept: false });
    const { container } = render();
    await user.click(screen.getByRole("tab", { name: /Upload a file/ }));
    await user.upload(
      container.querySelector('input[type="file"]') as HTMLInputElement,
      new File(["x"], "notes.pdf", { type: "application/pdf" }),
    );
    expect(screen.getByText(/does not look like a video or audio file/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start analysis" })).toBeDisabled();
  });

  it("a file can be picked by browsing this computer", async () => {
    vi.mocked(api.browse).mockResolvedValue({
      path: "/home/me",
      parent: null,
      home: "/home/me",
      dirs: [],
      files: [{ name: "ep1.webm", path: "/home/me/ep1.webm", size: 2048 }],
    });
    render();
    await userEvent.click(screen.getByRole("button", { name: "Browse…" }));
    const tree = await screen.findByRole("treegrid", { name: "Folders and media files" });
    await userEvent.click(within(tree).getByText("ep1.webm"));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "File path" })).toHaveValue("/home/me/ep1.webm");
  });

  it("says why the analysis could not start", async () => {
    vi.mocked(api.createJob).mockRejectedValue(new Error("The file does not exist"));
    render();
    await typePath("/nope.webm");
    await start();
    expect(await screen.findByText("The file does not exist")).toBeInTheDocument();
  });

  it("then shows the pipeline's steps live, announcing each new stage", async () => {
    const { jobs, container } = render();
    await typePath("/home/me/ep1.webm");
    await start();

    expect(await screen.findByRole("heading", { name: "Analysis started" })).toBeInTheDocument();
    const steps = screen.getByRole("list", { name: "Analysis steps" });
    expect(within(steps).getByText("Separate the dialogue")).toBeInTheDocument();
    expect(screen.getByText("Waiting in line.")).toBeInTheDocument();
    await expectNoAxeViolations(container);

    act(() =>
      jobs.socket.push({
        type: "job",
        job: {
          ...queued,
          status: "running",
          percent: 21,
          progress: [{ at: "t", message: "Transcribing with faster-whisper small…" }],
          last_message: "Transcribing with faster-whisper small…",
        },
      }),
    );
    expect(await screen.findByText("Now: Transcribe the words.")).toBeInTheDocument();
    const current = within(steps).getAllByRole("listitem").find((li) => li.getAttribute("aria-current") === "step");
    expect(current).toHaveTextContent(/Transcribe the words — in progress/);

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(api.cancelJob).toHaveBeenCalledWith("new");

    act(() => jobs.socket.push({ type: "job", job: { ...queued, status: "done", percent: 100 } }));
    expect(await screen.findByRole("heading", { name: "The analysis is ready" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open the analysis" })).toHaveAttribute("href", "/analysis/new");
  });

  it("“Analyze another clip” goes back to an empty form", async () => {
    render();
    await typePath("/home/me/ep1.webm");
    await start();
    await screen.findByRole("heading", { name: "Analysis started" });
    await userEvent.click(screen.getByRole("button", { name: "Analyze another clip" }));
    expect(screen.getByRole("heading", { name: "New analysis" })).toBeInTheDocument();
  });

  it("imported results open straight away", async () => {
    vi.mocked(api.browse).mockResolvedValue({
      path: "/home/me",
      parent: null,
      home: "/home/me",
      dirs: [{ name: "out", path: "/home/me/out", has_analysis: true }],
      files: [],
    });
    vi.mocked(api.importJob).mockResolvedValue({ ...job, id: "imp", imported: true });
    const { history } = render();
    await userEvent.click(screen.getByRole("button", { name: "Import results…" }));
    await userEvent.click(within(await screen.findByRole("treegrid")).getByText("out"));
    await waitFor(() => expect(history.at(-1)).toBe("/analysis/imp"));
  });
});
