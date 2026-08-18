/**
 * The accent colour of the day.
 *
 * <p>Sanatan practice gives each weekday to a graha and each graha a colour, and a site book
 * kept by hand would have been written in whatever ink was on the table that morning. This is
 * the same idea with the ink chosen for us: the action button and the phone's own top bar take
 * the day's colour, and everything else on the screen does not.</p>
 *
 * <p><b>What does not move.</b> {@code tokens.signal} stays where it is. It carries two jobs in
 * this app — the accent, and the colour of a thing that wants attention (a record needing
 * review, a security awaiting lodgement, the "material consumed" band on the cost chart) — and
 * only the first of those is about today. A "needs review" chip that turns green on Wednesday
 * has stopped meaning anything, and a chart series that changes hue with the day you happen to
 * open it makes two weeks impossible to compare. So the day colour is a separate token and the
 * status colour keeps the name it had.</p>
 *
 * <p><b>Why CSS variables and not a rebuilt theme.</b> Putting the day in React state would give
 * {@code createTheme} a new identity every midnight, and six hundred-odd {@code sx} call sites
 * would re-resolve behind it for a colour change. These are custom properties written once onto
 * the document element, so the theme object is still the module constant it always was, emotion
 * still compiles one class for the button, and the day never enters React at all.</p>
 *
 * <p>The values are never fed to MUI's palette for the same reason they are cheap: MUI augments
 * a palette colour by computing light and dark variants off it, which means parsing it, and
 * {@code var(--accent-600)} is not a colour anything can parse. Every use goes through a style
 * override or an SVG attribute, where the browser resolves it.</p>
 */
import { tokens } from './theme';

export interface DayAccent {
  /** The weekday in the tradition, for the title attribute and for tests to read. */
  readonly day: string;
  /** The graha the colour comes from. */
  readonly graha: string;
  /** Chip, banded row, day header. A wash on the paper ground — never a boundary. */
  readonly tint: string;
  /** The hue as it is usually named. Decoration only; it is too light to carry text. */
  readonly base: string;
  /** The action button. Every one of these clears 4.5:1 against the surface. */
  readonly action: string;
  /** Chrome — the phone's top bar — and text set on {@link tint}. */
  readonly deep: string;
  /**
   * The drawn edge around the action button.
   *
   * <p>Ink everywhere but Monday and Saturday. Chandra's colour is white and Shani's is black:
   * one gives a fill too dark to take an ink border (1.46:1 against the indigo — the border and
   * the drop shadow simply vanish, and the button that is meant to look drawn looks printed),
   * and the other a graphite barely better. On those two days the edge is drawn in paper
   * instead, so it is still an edge, just light on dark.</p>
   */
  readonly edge: string;
}

/** Seven, and the type says seven: `getDay` is a `number` to the compiler and a weekday to us. */
type Week = readonly [DayAccent, DayAccent, DayAccent, DayAccent, DayAccent, DayAccent, DayAccent];

/** Indexed by {@link Date#getDay} — 0 is Sunday, as the week is counted here and there. */
export const DAY_ACCENTS: Week = [
  { day: 'Ravivar', graha: 'Surya', tint: '#FBE7DC', base: '#E8531F', action: '#B23C10', deep: '#7A2A0C', edge: tokens.ink },
  { day: 'Somvar', graha: 'Chandra', tint: '#FAFAF7', base: '#C8CCD4', action: '#4A4E57', deep: '#2C3036', edge: tokens.paper },
  { day: 'Mangalvar', graha: 'Mangal', tint: '#FAE0E0', base: '#D62828', action: '#A81F1F', deep: '#6E1414', edge: tokens.ink },
  { day: 'Budhvar', graha: 'Budh', tint: '#DFF3E5', base: '#2E9E4F', action: '#1F7A3C', deep: '#14532D', edge: tokens.ink },
  { day: 'Guruvar', graha: 'Brihaspati', tint: '#FCF0D2', base: '#F0A500', action: '#8A5E00', deep: '#5E3F00', edge: tokens.ink },
  { day: 'Shukravar', graha: 'Shukra', tint: '#FBE8EF', base: '#EFA2BE', action: '#A83A63', deep: '#7D2E4D', edge: tokens.ink },
  { day: 'Shanivar', graha: 'Shani', tint: '#E2E5EF', base: '#29335C', action: '#29335C', deep: '#10142B', edge: tokens.paper },
] as const;

export function accentFor(date: Date): DayAccent {
  // `getDay` is a number as far as the compiler knows, and Sunday is the honest thing to be
  // when a date is somehow not one of seven days.
  return DAY_ACCENTS[date.getDay()] ?? DAY_ACCENTS[0];
}

/**
 * Writes the day onto the document element, and onto the {@code theme-color} meta with it.
 *
 * <p>The meta tag is the only way this reaches the phone's top bar. The manifest's own
 * {@code theme_color} is read at install and never again — the same trap the icon filenames in
 * index.html carry a note about — so it stays at the brand colour and is not touched here. And
 * it only lands on Android: iOS is set to a {@code default} status bar, which takes the page
 * behind it whatever this says.</p>
 */
export function applyDayAccent(date: Date = new Date()): DayAccent {
  const accent = accentFor(date);
  const style = document.documentElement.style;
  style.setProperty('--accent-50', accent.tint);
  style.setProperty('--accent-400', accent.base);
  style.setProperty('--accent-600', accent.action);
  style.setProperty('--accent-800', accent.deep);
  style.setProperty('--accent-edge', accent.edge);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', accent.deep);
  return accent;
}

/**
 * Turns the colour over at midnight, and again whenever the phone comes back.
 *
 * <p>The timer alone is not enough and the reason is the one the sync provider already knows: a
 * phone asleep in a pocket at midnight does not run timeouts on time, and a supervisor opening
 * the app at six in the morning would be looking at yesterday's colour. So the day is re-read on
 * every foreground as well, and the timer is only the case where the app is open and watched
 * across the hour.</p>
 *
 * <p>Midnight, not sunrise. The Hindu day turns at sunrise and this one turns at twelve, which
 * makes it the civil date wearing the day's colour rather than the tithi — an honest thing to be,
 * and not a thing to call panchang.</p>
 */
export function startDayAccentClock(): () => void {
  let timer: ReturnType<typeof setTimeout>;

  const tick = () => {
    applyDayAccent();
    const now = new Date();
    const midnight = new Date(now);
    midnight.setHours(24, 0, 0, 0);
    // A second past, so a clock a hair fast cannot land back on the day it just left.
    timer = setTimeout(tick, midnight.getTime() - now.getTime() + 1000);
  };

  const onVisible = () => {
    if (document.visibilityState === 'visible') {
      clearTimeout(timer);
      tick();
    }
  };

  tick();
  document.addEventListener('visibilitychange', onVisible);
  return () => {
    clearTimeout(timer);
    document.removeEventListener('visibilitychange', onVisible);
  };
}
