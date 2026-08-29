import { describe, expect, it } from 'vitest';
import { budgetFor, formatQuotedPercent, quotedCost } from './projectFigures';

describe('quotedCost', () => {
  it('moves the estimate up by a quote above it', () => {
    expect(quotedCost('10000000', '5')).toBe('10500000');
  });

  it('moves the estimate down by a quote below it', () => {
    expect(quotedCost('10000000', '-12.5')).toBe('8750000');
  });

  it('leaves a bid at par at the contract value', () => {
    expect(quotedCost('2500000', '0')).toBe('2500000');
  });

  it('keeps paise when the arithmetic makes them', () => {
    expect(quotedCost('1000', '-3.333')).toBe('966.67');
  });

  it('says nothing while either box is empty', () => {
    expect(quotedCost('10000000', '')).toBeNull();
    expect(quotedCost('', '-5')).toBeNull();
    expect(quotedCost(undefined, undefined)).toBeNull();
  });

  it('says nothing about a half-typed quote', () => {
    expect(quotedCost('10000000', '-')).toBeNull();
  });

  it('says nothing about entries the form itself would refuse', () => {
    expect(quotedCost('1,00,000', '-5')).toBeNull();
    expect(quotedCost('10000000', '5.5.5')).toBeNull();
  });
});

describe('budgetFor', () => {
  it('sits a quarter below the quoted cost', () => {
    expect(budgetFor('10000000')).toBe('7500000');
  });

  it('follows the quoted cost rather than the contract value', () => {
    // A crore bid 12.5% below is 87.5 lakh, and three quarters of that is 65.625 lakh —
    // not three quarters of the crore.
    expect(budgetFor(quotedCost('10000000', '-12.5'))).toBe('6562500');
  });

  it('keeps paise when the quarter does not divide evenly', () => {
    expect(budgetFor('1000.10')).toBe('750.08');
  });

  it('says nothing when the quoted cost says nothing', () => {
    expect(budgetFor(null)).toBeNull();
    expect(budgetFor('')).toBeNull();
    expect(budgetFor(undefined)).toBeNull();
  });
});

describe('formatQuotedPercent', () => {
  it('keeps the sign, which is the whole content of the figure', () => {
    expect(formatQuotedPercent(-12.5)).toBe('-12.5%');
    expect(formatQuotedPercent(4)).toBe('+4%');
  });

  it('drops the zeros a numeric(7,3) column carries', () => {
    expect(formatQuotedPercent(-12.5)).toBe('-12.5%');
    expect(formatQuotedPercent(6.25)).toBe('+6.25%');
  });

  it('gives bidding at par its name rather than a zero', () => {
    expect(formatQuotedPercent(0)).toBe('At par');
  });

  it('says nothing about a project that carries no quote', () => {
    expect(formatQuotedPercent(null)).toBe('\u2014');
    expect(formatQuotedPercent(undefined)).toBe('\u2014');
  });
});
