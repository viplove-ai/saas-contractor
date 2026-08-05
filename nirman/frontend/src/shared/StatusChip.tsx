import { Chip } from '@mui/material';
import { tokens } from '../app/theme';

export type RecordStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'SUBMITTED'
  | 'VERIFIED'
  | 'APPROVED'
  | 'REJECTED'
  | 'CONFLICT';

/**
 * The one visual constant across all six modules. Same shape, same position, same five
 * colours everywhere, so a supervisor learns the grammar once and reads it on any screen.
 */
const STATUS_STYLE: Record<RecordStatus, { label: string; fg: string; bg: string }> = {
  DRAFT: { label: 'Draft', fg: tokens.muted, bg: '#EEF1F3' },
  PENDING: { label: 'Pending sync', fg: tokens.warn, bg: '#FEF3E2' },
  SUBMITTED: { label: 'Submitted', fg: tokens.ink, bg: '#E8EDF2' },
  VERIFIED: { label: 'Verified', fg: tokens.ok, bg: '#E7F5EC' },
  APPROVED: { label: 'Approved', fg: tokens.ok, bg: '#E7F5EC' },
  REJECTED: { label: 'Rejected', fg: tokens.stop, bg: '#FDECEC' },
  CONFLICT: { label: 'Needs review', fg: tokens.signal, bg: '#FDEDE6' },
};

export function StatusChip({ status }: { status: RecordStatus }) {
  const style = STATUS_STYLE[status];
  return (
    <Chip
      label={style.label}
      size="small"
      sx={{ color: style.fg, bgcolor: style.bg, fontWeight: 600, borderRadius: 1 }}
    />
  );
}
