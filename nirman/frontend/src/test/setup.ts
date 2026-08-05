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
