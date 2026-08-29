import { describe, expect, it } from 'vitest';
import { headlineAmount, summarise } from './projectPortfolio';
import type { AdminProject, ProjectStatus } from './types';

function project(status: ProjectStatus, quotedCost?: number): AdminProject {
  return {
    id: `${status}-${quotedCost ?? 'none'}`,
    code: 'P',
    name: 'A project',
    status,
    quotedCost,
    version: 0,
  } as AdminProject;
}

describe('summarise', () => {
  it('bands the projects by where each contract stands', () => {
    const summary = summarise([
      project('ACTIVE', 1000),
      project('ACTIVE', 500),
      project('PLANNED', 2000),
      project('ON_HOLD', 300),
    ]);
    expect(summary.running).toEqual({ count: 2, value: 1500, unpriced: 0 });
    expect(summary.planned).toEqual({ count: 1, value: 2000, unpriced: 0 });
    expect(summary.onHold).toEqual({ count: 1, value: 300, unpriced: 0 });
    expect(summary.all).toEqual({ count: 4, value: 3800, unpriced: 0 });
  });

  it('reads completed and closed as one finished figure', () => {
    const summary = summarise([project('COMPLETED', 700), project('CLOSED', 300)]);
    expect(summary.finished).toEqual({ count: 2, value: 1000, unpriced: 0 });
  });

  it('counts a project with no quoted cost without totalling it', () => {
    const summary = summarise([project('ACTIVE', 1000), project('ACTIVE', undefined)]);
    expect(summary.running).toEqual({ count: 2, value: 1000, unpriced: 1 });
  });

  it('says how many rows it never saw', () => {
    expect(summarise([project('ACTIVE', 1)], 130).uncounted).toBe(129);
  });

  it('claims nothing uncounted when the page holds everything', () => {
    expect(summarise([project('ACTIVE', 1)], 1).uncounted).toBe(0);
    expect(summarise([project('ACTIVE', 1)]).uncounted).toBe(0);
  });

  it('answers an empty list with zeroes rather than nothing', () => {
    const summary = summarise([]);
    expect(summary.all).toEqual({ count: 0, value: 0, unpriced: 0 });
    expect(summary.running).toEqual({ count: 0, value: 0, unpriced: 0 });
  });
});

describe('headlineAmount', () => {
  it('reads crores as crores', () => {
    expect(headlineAmount(18500000)).toBe('\u20b91.85 Cr');
    expect(headlineAmount(10000000)).toBe('\u20b91 Cr');
  });

  it('reads lakhs as lakhs', () => {
    expect(headlineAmount(1250000)).toBe('\u20b912.5 L');
    expect(headlineAmount(100000)).toBe('\u20b91 L');
  });

  it('leaves anything under a lakh in rupees, and drops the paise', () => {
    expect(headlineAmount(99999.6)).toBe('\u20b91,00,000');
    expect(headlineAmount(40000)).toBe('\u20b940,000');
    expect(headlineAmount(0)).toBe('\u20b90');
  });
});
