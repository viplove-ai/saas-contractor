import '@testing-library/jest-dom/vitest';
/*
  jsdom ships no IndexedDB, and from Phase 7 the offline queue is mounted under every screen —
  the banner counts unsent records on mount and the sync provider drains on mount. Without a
  store behind them, Dexie throws asynchronously and the failure lands on whichever test file
  happened to be running, which is a very hard thing to read.

  fake-indexeddb/auto installs an in-memory implementation on globalThis. Each test file gets
  its own module registry and therefore its own empty database, so the queue starts clean.
*/
import 'fake-indexeddb/auto';

/**
 * Vitest's jsdom environment builds a full `window` but leaves `localStorage` off it, so code
 * written for a browser — `localStorage.getItem(...)`, which is how the session layer stores
 * the refresh token and the cached profile — reads as undefined and fails with a TypeError
 * some distance from the cause.
 *
 * <p>A working implementation rather than a set of stubs, because what the session cache
 * relies on is precisely that a value written in one call is still there in the next, and a
 * stub that forgets would let a broken cache pass. jsdom's own is not reachable here: under
 * vitest `window` and `globalThis` are the same object, so there is nothing to delegate to.</p>
 */
if (typeof globalThis.localStorage === 'undefined') {
  class MemoryStorage implements Storage {
    private entries = new Map<string, string>();

    get length(): number {
      return this.entries.size;
    }
    key(index: number): string | null {
      return [...this.entries.keys()][index] ?? null;
    }
    getItem(key: string): string | null {
      return this.entries.get(key) ?? null;
    }
    setItem(key: string, value: string): void {
      // Storage coerces, and a test that stores a number should behave as the browser does.
      this.entries.set(String(key), String(value));
    }
    removeItem(key: string): void {
      this.entries.delete(key);
    }
    clear(): void {
      this.entries.clear();
    }
  }

  const storage = new MemoryStorage();
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage });
}

/**
 * jsdom implements neither half of the object-URL API, and the daily report's photograph card
 * makes one per thumbnail the moment a file is picked. Without these, any screen carrying that
 * card throws inside an effect and takes the whole screen's test file down with it — a long way
 * from the cause.
 *
 * <p>Stubs rather than a real implementation: nothing under test reads the bytes back out, and
 * a jsdom `<img>` never fetches the URL anyway. What the tests assert is that a thumbnail was
 * rendered for each file and that clicking one opens it.</p>
 */
URL.createObjectURL ??= () => 'blob:test';
URL.revokeObjectURL ??= () => {};

/**
 * jsdom has no ResizeObserver, and recharts' ResponsiveContainer constructs one on mount —
 * so any screen with a chart on it renders as an empty div and every assertion against that
 * screen fails for a reason that has nothing to do with the screen.
 *
 * <p>The stub reports a fixed box rather than nothing, because a container told it has zero
 * width renders no chart at all. The size is arbitrary: no test asserts on chart geometry,
 * only that the chart mounted and the figures around it are right.</p>
 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
  configurable: true,
  value: 800,
});
Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
  configurable: true,
  value: 400,
});
