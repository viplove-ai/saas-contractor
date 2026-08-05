import '@testing-library/jest-dom/vitest';

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
