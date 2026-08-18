import { createTheme } from '@mui/material/styles';

/**
 * Design tokens for a tool used outdoors, in sunlight, on a phone that may be cracked.
 * High contrast, no thin greys, one accent reserved for actions that write data.
 * Every quantity, rate and amount is set in mono so digits align and misreads are rarer.
 *
 * <p>The surface is now warm paper rather than cool grey, and edges are drawn rather than
 * printed — the app reads as the site book it replaces. Nothing about legibility was traded
 * for it: ink, signal and the five status colours are unchanged, the paper is lighter than
 * the grey it replaces (contrast on ink went up, not down), and the handwriting font is
 * confined to headings and margin notes. No figure, label, field or button is ever set in it.</p>
 */
export const tokens = {
  ink: '#14181D',
  /** Card and field fill. Off-white rather than white so the paper reads as paper. */
  surface: '#FFFDF7',
  /** The page behind the cards. */
  paper: '#F7F3E9',
  /** Rails, banded rows and the app's own chrome. */
  paperDeep: '#EFE9DA',
  muted: '#5A646E',
  line: '#DCE1E6',
  /** Margin notes and register labels — a pencil note on paper, never a UI label. */
  annotation: '#8F6A3F',
  /**
   * Attention, and only attention: a record needing review, a security awaiting lodgement, the
   * material band on the cost chart. This used to be the action colour too — that half moved to
   * the day accent in `dayAccent.ts`, and the two must not be merged back. A status that changes
   * colour with the weekday is a status that says nothing.
   */
  signal: '#C2410C',
  ok: '#15803D',
  warn: '#B45309',
  stop: '#B91C1C',
} as const;

/** Nothing a supervisor taps is smaller than this. */
export const TOUCH_TARGET = 48;

/** Handwriting. Headings and margin notes only — see the note on `tokens`. */
export const HAND = '"Kalam", "Segoe Print", cursive';

export const theme = createTheme({
  palette: {
    primary: { main: tokens.ink, contrastText: tokens.surface },
    secondary: { main: tokens.signal, contrastText: tokens.surface },
    success: { main: tokens.ok },
    warning: { main: tokens.warn },
    error: { main: tokens.stop },
    text: { primary: tokens.ink, secondary: tokens.muted },
    divider: tokens.line,
    background: { default: tokens.paper, paper: tokens.surface },
  },
  typography: {
    fontFamily: '"IBM Plex Sans", system-ui, sans-serif',
    // h1 and h2 are hand-set: they are the only text on a screen that is allowed to be.
    h1: { fontFamily: HAND, fontSize: '2rem', fontWeight: 700, lineHeight: 1.08, letterSpacing: 0 },
    h2: { fontFamily: HAND, fontSize: '1.5rem', fontWeight: 700, lineHeight: 1.12 },
    h3: { fontSize: '1.125rem', fontWeight: 600 },
    button: { textTransform: 'none', fontWeight: 600, fontSize: '1rem' },
    // Applied to any numeric cell or field.
    caption: { fontFamily: '"IBM Plex Mono", monospace', fontWeight: 500 },
    /**
     * The register label above a group — "WAITING ON YOU · 3". Mono and tracked out, so a
     * label is never mistaken for a value.
     */
    overline: {
      fontFamily: '"IBM Plex Mono", monospace',
      fontSize: '0.72rem',
      fontWeight: 700,
      letterSpacing: '0.12em',
      lineHeight: 1.4,
    },
  },
  shape: { borderRadius: 6 },
  components: {
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          minHeight: TOUCH_TARGET,
          paddingInline: 20,
          border: `1.6px solid ${tokens.ink}`,
          // An inked edge: never twice the same, never far from a rectangle.
          borderRadius: '12px 7px 13px 8px / 8px 13px 7px 12px',
        },
        /*
          The one control that writes data carries the drawn drop shadow as well — and it is the
          one control that takes the colour of the day. The values come from `dayAccent.ts` as
          custom properties rather than from the palette, because MUI augments a palette colour
          by parsing it, and `var(--accent-600)` is not something anything can parse. The
          fallbacks are the brand orange, so a document that somehow paints before the boot
          script has run is the app as it always looked, not a button with no fill.
        */
        containedSecondary: {
          backgroundColor: 'var(--accent-600, #C2410C)',
          borderColor: 'var(--accent-edge, #14181D)',
          boxShadow: '3px 4px 0 var(--accent-edge, #14181D)',
          '&:hover': { backgroundColor: 'var(--accent-800, #7A2A0C)' },
          '&:active': { boxShadow: 'none', transform: 'translate(2px, 3px)' },
        },
        outlined: { backgroundColor: tokens.surface },
        text: { border: 'none' },
      },
    },
    MuiPaper: {
      styleOverrides: {
        outlined: {
          border: `1.6px solid ${tokens.ink}`,
          borderRadius: '14px 8px 15px 9px / 9px 15px 8px 14px',
          boxShadow: '3px 4px 0 rgba(20,24,29,0.10)',
        },
      },
    },
    MuiTextField: { defaultProps: { fullWidth: true, size: 'medium' } },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          backgroundColor: tokens.surface,
          borderRadius: '11px 6px 13px 7px / 7px 13px 6px 11px',
          '& .MuiOutlinedInput-notchedOutline': { borderColor: tokens.ink, borderWidth: 1.6 },
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: tokens.ink },
        },
        input: { minHeight: TOUCH_TARGET - 16 },
      },
    },
    MuiChip: { styleOverrides: { root: { borderRadius: 6 } } },
    MuiAlert: {
      styleOverrides: {
        root: { border: '1.4px solid currentColor', borderRadius: '10px 6px 11px 7px / 7px 11px 6px 10px' },
      },
    },
  },
});
