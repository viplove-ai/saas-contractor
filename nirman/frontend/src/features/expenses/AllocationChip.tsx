import { Chip, Tooltip } from '@mui/material';
import { formatAmount } from '../../shared/formatters';
import { tokens } from '../../app/theme';
import type { CostAllocation, Expense } from './types';

/**
 * Whose cost it is, said in one word beside the amount.
 *
 * <p>The word is the point. "Site" and "Company" are what the office argues about at month
 * end, and a register that shows only a total leaves somebody re-deciding every row from the
 * description. A split says both numbers, because "partly" is not an answer anybody can
 * reconcile against a bill.</p>
 *
 * <p>Deliberately not a {@link StatusChip} colour. Workflow status is a five-colour grammar a
 * supervisor learns once — approved, rejected, waiting — and whose cost this is says nothing
 * about whether it went through. Borrowing that palette would make an office bill read as a
 * problem with the bill.</p>
 */
export function AllocationChip({
  expense,
  size = 'small',
}: {
  expense: Pick<Expense, 'costAllocation' | 'siteCost' | 'companyCost' | 'allocationNote'>;
  size?: 'small' | 'medium';
}) {
  const chip = (
    <Chip
      label={allocationLabel(expense.costAllocation)}
      size={size}
      variant="outlined"
      sx={{
        fontWeight: 600,
        borderRadius: 1,
        borderColor: tokens.ink,
        color: expense.costAllocation === 'SITE' ? tokens.ink : tokens.signal,
      }}
    />
  );
  const detail =
    expense.costAllocation === 'SPLIT'
      ? `Site ${formatAmount(expense.siteCost)} · company ${formatAmount(expense.companyCost)}`
      : expense.allocationNote;
  return detail ? (
    <Tooltip title={detail}>
      <span>{chip}</span>
    </Tooltip>
  ) : (
    chip
  );
}

export function allocationLabel(allocation: CostAllocation): string {
  switch (allocation) {
    case 'COMPANY':
      return 'Company expense';
    case 'SPLIT':
      return 'Site and company';
    default:
      return 'Site expense';
  }
}
