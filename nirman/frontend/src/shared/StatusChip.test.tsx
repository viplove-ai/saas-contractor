import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusChip, type RecordStatus } from './StatusChip';

/**
 * The status chip is the one visual constant across all six modules, so its labels are part
 * of the interface contract rather than incidental copy. This also exercises the React
 * Testing Library setup end to end.
 */
describe('StatusChip', () => {
  it.each<[RecordStatus, string]>([
    ['DRAFT', 'Draft'],
    ['PENDING', 'Pending sync'],
    ['SUBMITTED', 'Submitted'],
    ['VERIFIED', 'Verified'],
    ['APPROVED', 'Approved'],
    ['REJECTED', 'Rejected'],
    ['CONFLICT', 'Needs review'],
  ])('renders %s as "%s"', (status, label) => {
    render(<StatusChip status={status} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });

  it('says "Needs review" rather than "Conflict" — a supervisor is told what to do', () => {
    render(<StatusChip status="CONFLICT" />);
    expect(screen.getByText('Needs review')).toBeInTheDocument();
    expect(screen.queryByText(/conflict/i)).not.toBeInTheDocument();
  });
});
