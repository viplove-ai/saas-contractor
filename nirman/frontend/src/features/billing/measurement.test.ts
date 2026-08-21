import { describe, expect, it } from 'vitest';
import { lineContents, shapeColumns, shapeForUnit, sheetTotal } from './types';

/*
  The grid computes the running total in the browser so the engineer sees the checksum answer
  while he types, rather than after a round trip. That makes this arithmetic a second
  implementation of MeasurementLine.computeContents, and a second implementation that
  disagrees with the first is worse than none — he would be told his totals agree and then be
  refused at the signature.

  So these cases are the same ones the server-side test asserts, to the same figures.
*/

describe('a measurement row multiplies only the dimensions given', () => {
  it('multiplies all five for a volume', () => {
    // 1 x 1 x 6.22 x 5.80 x 0.10
    expect(lineContents({ nos: 1, mult: 1, length: 6.22, breadth: 5.8, height: 0.1 })).toBe(3.61);
  });

  it('drops the height for an area', () => {
    expect(lineContents({ nos: 1, mult: 1, length: 4.5, breadth: 0.57 })).toBe(2.57);
  });

  it('drops breadth and height for a linear item', () => {
    expect(lineContents({ nos: 1, mult: 9, length: 1.56 })).toBe(14.04);
  });

  it('is just nos times count for an each-item', () => {
    expect(lineContents({ nos: 3, mult: 2 })).toBe(6);
  });

  /**
   * The rule the whole shape system rests on. A linear item has no breadth; sending zero
   * would claim the work had none and multiply the row away to nothing.
   */
  it('treats a missing dimension as not applicable, never as zero', () => {
    expect(lineContents({ nos: 1, mult: 1, length: 5, breadth: null })).toBe(5);
    expect(lineContents({ nos: 1, mult: 1, length: 5, breadth: 0 })).toBe(0);
  });

  it('signs a deduction negative', () => {
    expect(lineContents({ nos: 1, mult: 2, length: 1.2, breadth: 2.1, deduction: true }))
      .toBe(-5.04);
  });

  /**
   * Rounded per row rather than only at the total, because the printed sheet shows this
   * figure per row and the column has to add up to the total beside it. A page whose own
   * arithmetic looks wrong is a page the Assistant Engineer stops trusting.
   */
  it('rounds each row to two places, as the measurement book does', () => {
    expect(lineContents({ nos: 1, mult: 1, length: 1.005, breadth: 1 })).toBe(1.01);
  });
});

describe('the sheet total', () => {
  it('adds the rows including the deductions', () => {
    const total = sheetTotal([
      { nos: 1, mult: 1, length: 6.22, breadth: 5.8, height: 0.1 },
      { nos: 1, mult: 1, length: 4.5, breadth: 0.57 },
      { nos: 1, mult: 9, length: 1.56 },
      { nos: 3, mult: 2 },
      { nos: 1, mult: 2, length: 1.2, breadth: 2.1, deduction: true },
    ]);
    // 3.61 + 2.57 + 14.04 + 6.00 - 5.04 — the same figure the server test asserts.
    expect(total).toBe(21.18);
  });

  it('is zero for an empty sheet rather than NaN', () => {
    expect(sheetTotal([])).toBe(0);
  });
});

describe('the unit decides which boxes appear', () => {
  it.each([
    ['CUM', 'VOLUME', ['length', 'breadth', 'height']],
    ['SQM', 'AREA', ['length', 'breadth']],
    ['MTR', 'LINEAR', ['length']],
    ['KG', 'LINEAR', ['length']],
    ['NOS', 'COUNT', []],
  ])('%s measures as %s', (unit, shape, columns) => {
    expect(shapeForUnit(unit)).toBe(shape);
    expect(shapeColumns(shapeForUnit(unit))).toEqual(columns);
  });

  it('falls back to a count for a unit it does not know', () => {
    expect(shapeForUnit(undefined)).toBe('COUNT');
    expect(shapeForUnit('per bag of 50 kg cement used')).toBe('COUNT');
  });
});
