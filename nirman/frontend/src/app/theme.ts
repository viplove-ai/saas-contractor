import { createTheme } from '@mui/material/styles';

/**
 * Design tokens for a tool used outdoors, in sunlight, on a phone that may be cracked.
 * High contrast, no thin greys, one accent reserved for actions that write data.
 * Every quantity, rate and amount is set in mono so digits align and misreads are rarer.
 */
export const tokens = {
  ink: '#14181D',
  surface: '#FFFFFF',
  muted: '#5A646E',
  line: '#DCE1E6',
  signal: '#C2410C',
  ok: '#15803D',
  warn: '#B45309',
  stop: '#B91C1C',
} as const;

/** Nothing a supervisor taps is smaller than this. */
export const TOUCH_TARGET = 48;

export const theme = createTheme({
  palette: {
    primary: { main: tokens.ink, contrastText: tokens.surface },
    secondary: { main: tokens.signal },
    success: { main: tokens.ok },
    warning: { main: tokens.warn },
    error: { main: tokens.stop },
    text: { primary: tokens.ink, secondary: tokens.muted },
    divider: tokens.line,
    background: { default: '#F7F8F9', paper: tokens.surface },
  },
  typography: {
    fontFamily: '"IBM Plex Sans", system-ui, sans-serif',
    h1: { fontSize: '1.75rem', fontWeight: 700, letterSpacing: '-0.02em' },
    h2: { fontSize: '1.375rem', fontWeight: 600, letterSpacing: '-0.01em' },
    h3: { fontSize: '1.125rem', fontWeight: 600 },
    button: { textTransform: 'none', fontWeight: 600, fontSize: '1rem' },
    // Applied to any numeric cell or field.
    caption: { fontFamily: '"IBM Plex Mono", monospace', fontWeight: 500 },
  },
  shape: { borderRadius: 6 },
  components: {
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: { root: { minHeight: TOUCH_TARGET, paddingInline: 20 } },
    },
    MuiTextField: { defaultProps: { fullWidth: true, size: 'medium' } },
    MuiOutlinedInput: { styleOverrides: { input: { minHeight: TOUCH_TARGET - 16 } } },
  },
});
