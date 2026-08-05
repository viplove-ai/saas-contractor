import { describe, expect, it } from 'vitest';
import { formatAmount, formatHours, formatQuantity } from './formatters';

/**
 * Indian digit grouping is a correctness requirement, not a preference: a contractor reads
 * 12,50,000 and misreads 1,250,000. These lock the grouping and the em-dash empty state so a
 * missing amount can never render as a confident "0".
 */
describe('formatAmount', () => {
  it('groups in lakhs, not thousands', () => {
    // Assert on the digits rather than the whole string: the currency symbol and any
    // separating space vary with the ICU build, the grouping does not.
    expect(formatAmount(1250000)).toContain('12,50,000');
    expect(formatAmount(10000000)).toContain('1,00,00,000');
    expect(formatAmount(1000)).toContain('1,000');
  });

  it('renders a missing amount as an em dash, never as zero', () => {
    expect(formatAmount(null)).toBe('—');
    expect(formatAmount(undefined)).toBe('—');
  });

  it('keeps a real zero distinct from a missing value', () => {
    expect(formatAmount(0)).not.toBe('—');
    expect(formatAmount(0)).toContain('0');
  });

  it('carries paise', () => {
    expect(formatAmount(1234.5)).toContain('1,234.5');
  });
});

describe('formatQuantity', () => {
  it('appends the unit when one is given', () => {
    expect(formatQuantity(150, 'BAG')).toBe('150 BAG');
  });

  it('omits the unit when none is given', () => {
    expect(formatQuantity(150)).toBe('150');
  });

  it('keeps three decimals, matching the quantity precision in the schema', () => {
    expect(formatQuantity(1234.5678)).toBe('1,234.568');
  });

  it('renders a missing quantity as an em dash', () => {
    expect(formatQuantity(null)).toBe('—');
    expect(formatQuantity(undefined, 'BAG')).toBe('—');
  });
});

describe('formatHours', () => {
  it('always shows two decimals so hours columns align', () => {
    expect(formatHours(8)).toBe('8.00 h');
    expect(formatHours(7.5)).toBe('7.50 h');
  });

  it('renders a missing value as an em dash', () => {
    expect(formatHours(null)).toBe('—');
  });
});
