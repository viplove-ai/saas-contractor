import { describe, expect, it } from 'vitest';
import { quotedBudget } from './quotedBudget';

describe('quotedBudget', () => {
  it('moves the estimate up by a quote above it', () => {
    expect(quotedBudget('10000000', '5')).toBe('10500000');
  });

  it('moves the estimate down by a quote below it', () => {
    expect(quotedBudget('10000000', '-12.5')).toBe('8750000');
  });

  it('leaves a bid at par at the contract value', () => {
    expect(quotedBudget('2500000', '0')).toBe('2500000');
  });

  it('keeps paise when the arithmetic makes them', () => {
    expect(quotedBudget('1000', '-3.333')).toBe('966.67');
  });

  it('says nothing while either box is empty', () => {
    expect(quotedBudget('10000000', '')).toBeNull();
    expect(quotedBudget('', '-5')).toBeNull();
    expect(quotedBudget(undefined, undefined)).toBeNull();
  });

  it('says nothing about a half-typed quote', () => {
    expect(quotedBudget('10000000', '-')).toBeNull();
  });

  it('says nothing about entries the form itself would refuse', () => {
    expect(quotedBudget('1,00,000', '-5')).toBeNull();
    expect(quotedBudget('10000000', '5.5.5')).toBeNull();
  });
});
