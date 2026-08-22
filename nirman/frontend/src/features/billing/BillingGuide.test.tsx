import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { BillingGuide } from './BillingGuide';

/*
  The guide exists so an engineer never needs a handover session. That makes two things
  testable and worth pinning: it walks in the order the work is actually done, and it can be
  read to the end and reopened at the start — because somebody who closed it halfway through
  and comes back next week should not resume in the middle of a workflow he has forgotten.
*/

describe('the billing walkthrough', () => {
  it('starts at printing the paper, which is where the work starts', () => {
    render(<BillingGuide open onClose={vi.fn()} />);
    expect(screen.getByText(/Print the blank sheets/)).toBeInTheDocument();
  });

  it('walks paper to Excel in seven steps and ends on Done', async () => {
    const user = userEvent.setup();
    render(<BillingGuide open onClose={vi.fn()} />);

    const titles: string[] = [];
    for (let i = 0; i < 6; i += 1) {
      titles.push(screen.getByText(/^\d\. /).textContent ?? '');
      await user.click(screen.getByRole('button', { name: 'Next' }));
    }
    titles.push(screen.getByText(/^\d\. /).textContent ?? '');

    expect(titles).toHaveLength(7);
    expect(titles[0]).toMatch(/Print the blank sheets/);
    expect(titles[5]).toMatch(/Download the Excel/);
    expect(titles[6]).toMatch(/Submit, and pass/);
    expect(screen.getByRole('button', { name: 'Done' })).toBeInTheDocument();
  });

  /** Reopened next week, it should be at the beginning rather than where he abandoned it. */
  it('returns to the first step when it is closed', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(<BillingGuide open onClose={onClose} />);

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText(/Measure on site/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalled();
    expect(screen.getByText(/Print the blank sheets/)).toBeInTheDocument();
  });

  it('tells him what happens when the two totals disagree, not just which button to press', async () => {
    const user = userEvent.setup();
    render(<BillingGuide open onClose={vi.fn()} />);
    for (let i = 0; i < 3; i += 1) {
      await user.click(screen.getByRole('button', { name: 'Next' }));
    }
    expect(screen.getByText(/will not let you sign/)).toBeInTheDocument();
  });
});
