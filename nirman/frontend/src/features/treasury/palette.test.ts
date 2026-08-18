import { describe, expect, it } from 'vitest';
import { tokens } from '../../app/theme';
import { TYPE_COLOR, releaseWording, urgencyColor } from './palette';

describe('urgencyColor', () => {
  it('reds an overdue release and an unset date is not an alarm', () => {
    expect(urgencyColor(-1)).toBe(tokens.stop);
    // No date is a gap in the record, not a deposit in trouble — colouring it red would put
    // every half-entered row into the same bucket as a genuinely late one.
    expect(urgencyColor(null)).toBe(tokens.muted);
    expect(urgencyColor(undefined)).toBe(tokens.muted);
  });

  it('warns inside a month and stops colouring past a quarter', () => {
    expect(urgencyColor(0)).toBe(tokens.warn);
    expect(urgencyColor(30)).toBe(tokens.warn);
    expect(urgencyColor(31)).toBe(tokens.annotation);
    expect(urgencyColor(90)).toBe(tokens.annotation);
    // A screen where every row is tinted has no alarm left to raise.
    expect(urgencyColor(91)).toBe(tokens.muted);
  });
});

describe('releaseWording', () => {
  it('says how late, in the singular where that is the number', () => {
    expect(releaseWording(-1)).toBe('Overdue by a day');
    expect(releaseWording(-12)).toBe('Overdue by 12 days');
  });

  it('counts days close in and months further out, so nothing reads as "in 730 days"', () => {
    expect(releaseWording(0)).toBe('Due today');
    expect(releaseWording(1)).toBe('In a day');
    expect(releaseWording(45)).toBe('In 45 days');
    expect(releaseWording(90)).toBe('In 3 months');
    expect(releaseWording(730)).toBe('In 2 years');
  });

  it('has no date to give when the deposit is not lodged', () => {
    expect(releaseWording(null)).toBe('—');
  });
});

describe('the four kinds', () => {
  it('are four distinct hues, because they are four holding periods and not four sizes', () => {
    const colours = Object.values(TYPE_COLOR);
    expect(new Set(colours).size).toBe(colours.length);
  });
});
