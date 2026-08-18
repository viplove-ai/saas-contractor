import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DAY_ACCENTS, accentFor, applyDayAccent, startDayAccentClock } from './dayAccent';
import { tokens } from './theme';

const readVar = (name: string) => document.documentElement.style.getPropertyValue(name);
const themeColour = () => document.querySelector('meta[name="theme-color"]')?.getAttribute('content');

beforeEach(() => {
  document.head.innerHTML = '<meta name="theme-color" content="#C2410C" />';
  document.documentElement.removeAttribute('style');
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the accent of the day', () => {
  it('gives every weekday its graha colour, Sunday first', () => {
    // 2026-08-16 is a Sunday, so this walks Ravivar through Shanivar in order.
    const week = Array.from({ length: 7 }, (_, i) => accentFor(new Date(2026, 7, 16 + i)).graha);
    expect(week).toEqual(['Surya', 'Chandra', 'Mangal', 'Budh', 'Brihaspati', 'Shukra', 'Shani']);
  });

  it('writes the day onto the document and the top bar', () => {
    const accent = applyDayAccent(new Date(2026, 7, 19)); // Budhvar

    expect(accent.graha).toBe('Budh');
    expect(readVar('--accent-600')).toBe('#1F7A3C');
    expect(readVar('--accent-50')).toBe('#DFF3E5');
    // Chrome takes the deep stop, not the button's, so the bar reads as chrome.
    expect(readVar('--accent-800')).toBe('#14532D');
    expect(themeColour()).toBe('#14532D');
  });

  it('draws the edge in paper on the two days whose colour is the ink', () => {
    const inverted = DAY_ACCENTS.filter((a) => a.edge === tokens.paper).map((a) => a.graha);
    expect(inverted).toEqual(['Chandra', 'Shani']);
    expect(DAY_ACCENTS.filter((a) => a.edge === tokens.ink)).toHaveLength(5);
  });

  it('turns the colour over at midnight', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 7, 22, 23, 59, 30)); // Shanivar, half a minute left

    const stop = startDayAccentClock();
    expect(readVar('--accent-600')).toBe('#29335C');

    vi.advanceTimersByTime(31_000);
    expect(readVar('--accent-600')).toBe('#B23C10'); // Ravivar
    expect(themeColour()).toBe('#7A2A0C');

    stop();
  });

  it('re-reads the day when the phone comes back, since a sleeping phone runs no timers', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 7, 22, 23, 0, 0)); // Shanivar

    const stop = startDayAccentClock();
    expect(readVar('--accent-600')).toBe('#29335C');

    // Asleep across midnight: the clock moved but the timeout never fired on time.
    vi.setSystemTime(new Date(2026, 7, 23, 6, 30, 0));
    document.dispatchEvent(new Event('visibilitychange'));

    expect(readVar('--accent-600')).toBe('#B23C10');
    stop();
  });

  it('stops listening when torn down', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 7, 22, 12, 0, 0));

    const stop = startDayAccentClock();
    stop();
    document.documentElement.removeAttribute('style');
    document.dispatchEvent(new Event('visibilitychange'));

    expect(readVar('--accent-600')).toBe('');
  });
});
