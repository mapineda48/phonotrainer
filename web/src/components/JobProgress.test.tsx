import { screen } from "@testing-library/react";
import { act } from "react";
import { afterEach, describe, expect, it } from "vitest";

import { fakeJobsChannel, job, renderWith } from "../test/fixtures";
import type { Job } from "../types";
import { JobProgress } from "./JobProgress";

const runningJob: Job = {
  ...job,
  status: "running",
  percent: 8,
  last_message: "Downloading from YouTube… 8%",
  progress: [
    { at: "2026-07-26T12:00:01+00:00", message: "Queued → starting…" },
    { at: "2026-07-26T12:00:02+00:00", message: "Querying the URL…" },
    { at: "2026-07-26T12:00:03+00:00", message: "Downloading from YouTube… 8%" },
  ],
};

let channel: ReturnType<typeof fakeJobsChannel> | null = null;

afterEach(() => {
  channel?.channel.dispose();
  channel = null;
});

describe("JobProgress", () => {
  it("shows the log with the most recent line first", async () => {
    renderWith(<JobProgress job={runningJob} />);

    await screen.findByText("Log");
    const log = document.querySelector(".log")!;
    const lines = [...log.querySelectorAll("div")].map((div) => div.textContent);

    expect(lines).toEqual([
      "Downloading from YouTube… 8%",
      "Querying the URL…",
      "Queued → starting…",
    ]);
  });

  it("updates when the server pushes over the channel, with no polling", async () => {
    channel = fakeJobsChannel([{ ...runningJob, percent: 8 }]);
    renderWith(<JobProgress job={{ ...runningJob, progress: [], last_message: null }} />, {
      jobsChannel: channel.channel,
    });

    // the snapshot already carries the full job: the log appears unprompted
    expect((await screen.findAllByText("Downloading from YouTube… 8%")).length).toBeGreaterThan(0);

    act(() =>
      channel!.socket.push({
        type: "job",
        job: { ...runningJob, percent: 20, last_message: "Downloading from YouTube… 20%" },
      }),
    );
    expect(await screen.findByText("20%")).toBeInTheDocument();
  });
});
