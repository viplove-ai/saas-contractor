import { tokens } from '../../app/theme';
import type { SecurityStatus, SecurityType } from './types';

/**
 * Colour for the treasury screens.
 *
 * <p>The four kinds of deposit get four hues rather than four shades of one, because they are
 * not degrees of the same thing — earnest money is gone in a fortnight and a performance
 * guarantee is gone for three years, and a reader scanning the release calendar is asking
 * which is which, not which is bigger. Hues are far apart on the wheel and close in value, so
 * the set survives a phone in sunlight and reads on the warm paper the app is set on.</p>
 *
 * <p>Status is a separate scale on purpose. A deposit's kind never changes and its state
 * changes four times, so folding them into one colour would mean the same FDR moving through
 * four colours and telling the reader nothing about what it is.</p>
 */
export const TYPE_COLOR: Record<SecurityType, string> = {
  /** Ochre — short-lived money, out and back inside a month. */
  EMD: '#B45309',
  /** Indigo — the long hold, and usually the largest single figure on the screen. */
  PERFORMANCE_GUARANTEE: '#1D4E89',
  /** Plum — what a deep bid adds on top. Far from indigo, because these two sit side by side. */
  ADDITIONAL_PG: '#8B2E6B',
  /** Teal — the one that was never lodged, and the only one that is not a bank's problem. */
  SECURITY_DEPOSIT: '#0F766E',
};

/** Pale grounds for chips and bars, same order. */
export const TYPE_TINT: Record<SecurityType, string> = {
  EMD: '#FBF0E2',
  PERFORMANCE_GUARANTEE: '#E7EDF5',
  ADDITIONAL_PG: '#F6E8F1',
  SECURITY_DEPOSIT: '#E3F1EF',
};

export const TYPE_LABEL: Record<SecurityType, string> = {
  EMD: 'Earnest money',
  PERFORMANCE_GUARANTEE: 'Performance guarantee',
  ADDITIONAL_PG: 'Additional guarantee',
  SECURITY_DEPOSIT: 'Security deposit',
};

/** The short form, for a column head or a tight chip. */
export const TYPE_SHORT: Record<SecurityType, string> = {
  EMD: 'EMD',
  PERFORMANCE_GUARANTEE: 'PG',
  ADDITIONAL_PG: 'APG',
  SECURITY_DEPOSIT: 'SD',
};

export const STATUS_STYLE: Record<SecurityStatus, { label: string; fg: string; bg: string }> = {
  /** Known to be required and not yet placed — money the company still has to find. */
  DUE: { label: 'To lodge', fg: tokens.signal, bg: '#FDEDE6' },
  LODGED: { label: 'Held', fg: '#1D4E89', bg: '#E7EDF5' },
  RELEASED: { label: 'Released', fg: tokens.ok, bg: '#E7F5EC' },
  FORFEITED: { label: 'Forfeited', fg: tokens.stop, bg: '#FDECEC' },
};

export const INSTRUMENT_LABEL: Record<string, string> = {
  FDR: 'Fixed deposit',
  BANK_GUARANTEE: 'Bank guarantee',
  DD: 'Demand draft',
  CASH: 'Cash',
  BILL_RETENTION: 'Withheld from bills',
};

/**
 * How close a release is, as a colour.
 *
 * <p>Overdue and "matures before it is due back" share the red, because they need the same
 * telephone call. Everything past ninety days is deliberately uncoloured: a screen where every
 * row is tinted has no alarm left to raise.</p>
 */
export function urgencyColor(daysToRelease: number | null | undefined): string {
  if (daysToRelease == null) return tokens.muted;
  if (daysToRelease < 0) return tokens.stop;
  if (daysToRelease <= 30) return tokens.warn;
  if (daysToRelease <= 90) return tokens.annotation;
  return tokens.muted;
}

/** "Overdue by 12 days", "in 4 days", "in 8 months". */
export function releaseWording(daysToRelease: number | null | undefined): string {
  if (daysToRelease == null) return '—';
  if (daysToRelease < 0) {
    const late = -daysToRelease;
    return late === 1 ? 'Overdue by a day' : `Overdue by ${late} days`;
  }
  if (daysToRelease === 0) return 'Due today';
  if (daysToRelease === 1) return 'In a day';
  if (daysToRelease < 60) return `In ${daysToRelease} days`;
  const months = Math.round(daysToRelease / 30);
  return months < 24 ? `In ${months} months` : `In ${Math.round(months / 12)} years`;
}
