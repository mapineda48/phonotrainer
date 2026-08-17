import { renderHook } from "@testing-library/react";
import { act } from "react";
import { describe, expect, it, vi } from "vitest";

import { Clock, useTimeSelector } from "./clock";

describe("Clock", () => {
  it("notifies only when the time actually changes", () => {
    const clock = new Clock();
    const listener = vi.fn();
    clock.subscribe(listener);

    clock.set(1.5);
    clock.set(1.5);
    clock.set(2);

    expect(listener).toHaveBeenCalledTimes(2);
    expect(clock.getSnapshot()).toBe(2);
  });

  it("stops notifying after unsubscribing", () => {
    const clock = new Clock();
    const listener = vi.fn();
    clock.subscribe(listener)();
    clock.set(3);
    expect(listener).not.toHaveBeenCalled();
  });
});

describe("useTimeSelector", () => {
  it("re-renders only when the derived value changes, not on every tick", () => {
    const clock = new Clock();
    const renders = vi.fn();
    const { result } = renderHook(() => {
      renders();
      return useTimeSelector(clock, (time) => Math.floor(time));
    });

    const initial = renders.mock.calls.length;
    act(() => clock.set(0.1));
    act(() => clock.set(0.9)); // same whole second: it must not re-render
    expect(renders.mock.calls.length).toBe(initial);

    act(() => clock.set(1.2));
    expect(result.current).toBe(1);
    expect(renders.mock.calls.length).toBeGreaterThan(initial);
  });
});
