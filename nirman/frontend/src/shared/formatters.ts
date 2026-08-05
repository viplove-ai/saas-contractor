/**
 * Indian number formatting. A contractor reads 12,50,000 and not 1,250,000, and getting
 * this wrong makes every amount on every screen feel foreign.
 */
const inr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 2,
});

const qty = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 3 });

export function formatAmount(value: number | null | undefined): string {
  return value == null ? '—' : inr.format(value);
}

export function formatQuantity(value: number | null | undefined, unit?: string): string {
  if (value == null) return '—';
  return unit ? `${qty.format(value)} ${unit}` : qty.format(value);
}

export function formatHours(value: number | null | undefined): string {
  return value == null ? '—' : `${value.toFixed(2)} h`;
}
