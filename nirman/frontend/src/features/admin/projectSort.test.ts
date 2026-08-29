import { describe, expect, it } from 'vitest';
import { sortProjects } from './projectSort';
import type { AdminProject, ProjectStatus } from './types';

function project(code: string, fields: Partial<AdminProject> = {}): AdminProject {
  return {
    id: code,
    code,
    name: `${code} works`,
    status: 'ACTIVE' as ProjectStatus,
    version: 0,
    ...fields,
  };
}

const codesOf = (projects: AdminProject[]) => projects.map((project) => project.code);

describe('sortProjects', () => {
  it('orders by code both ways', () => {
    const projects = [project('KSN01'), project('ALM03'), project('BAG02')];
    expect(codesOf(sortProjects(projects, { column: 'code', direction: 'asc' })))
      .toEqual(['ALM03', 'BAG02', 'KSN01']);
    expect(codesOf(sortProjects(projects, { column: 'code', direction: 'desc' })))
      .toEqual(['KSN01', 'BAG02', 'ALM03']);
  });

  it('orders money and the quote as numbers, not as text', () => {
    const projects = [
      project('A', { quotedCost: 9000000 }),
      project('B', { quotedCost: 16187500 }),
      project('C', { quotedCost: 250000 }),
    ];
    expect(codesOf(sortProjects(projects, { column: 'quotedCost', direction: 'asc' })))
      .toEqual(['C', 'A', 'B']);

    const quotes = [
      project('A', { quotedPercent: -12.5 }),
      project('B', { quotedPercent: 4 }),
      project('C', { quotedPercent: -2 }),
    ];
    expect(codesOf(sortProjects(quotes, { column: 'quotedPercent', direction: 'asc' })))
      .toEqual(['A', 'C', 'B']);
  });

  it('sinks what is missing whichever way the arrow points', () => {
    const projects = [
      project('A', { quotedCost: 500 }),
      project('B'),
      project('C', { quotedCost: 900 }),
    ];
    // Unknown is not "cheapest", and not "dearest" either — it is unknown, and stays at the foot.
    expect(codesOf(sortProjects(projects, { column: 'quotedCost', direction: 'asc' })))
      .toEqual(['A', 'C', 'B']);
    expect(codesOf(sortProjects(projects, { column: 'quotedCost', direction: 'desc' })))
      .toEqual(['C', 'A', 'B']);
  });

  it('sorts the completion column by the date that column is showing', () => {
    const projects = [
      project('A', { expectedCompletionDate: '2026-12-31' }),
      // Finished early: the cell prints the actual date, so the sort has to read it too.
      project('B', { expectedCompletionDate: '2026-01-31', actualCompletionDate: '2027-02-01' }),
      project('C', { expectedCompletionDate: '2026-06-30' }),
    ];
    expect(codesOf(sortProjects(projects, { column: 'completion', direction: 'asc' })))
      .toEqual(['C', 'A', 'B']);
  });

  it('sorts status by the life of a contract rather than by its label', () => {
    const projects = [
      project('A', { status: 'CLOSED' }),
      project('B', { status: 'PLANNED' }),
      project('C', { status: 'ACTIVE' }),
      project('D', { status: 'COMPLETED' }),
      project('E', { status: 'ON_HOLD' }),
    ];
    expect(codesOf(sortProjects(projects, { column: 'status', direction: 'asc' })))
      .toEqual(['B', 'C', 'E', 'D', 'A']);
  });

  it('breaks a tie on the code, so the order does not shuffle between renders', () => {
    const projects = [
      project('KSN02', { expectedCompletionDate: '2026-06-30' }),
      project('ALM01', { expectedCompletionDate: '2026-06-30' }),
    ];
    expect(codesOf(sortProjects(projects, { column: 'completion', direction: 'asc' })))
      .toEqual(['ALM01', 'KSN02']);
    // Even descending: the tie-break is the code ascending, not the reverse of the sort.
    expect(codesOf(sortProjects(projects, { column: 'completion', direction: 'desc' })))
      .toEqual(['ALM01', 'KSN02']);
  });

  it('leaves the array it was given alone', () => {
    const projects = [project('KSN01'), project('ALM03')];
    sortProjects(projects, { column: 'code', direction: 'asc' });
    expect(codesOf(projects)).toEqual(['KSN01', 'ALM03']);
  });
});
