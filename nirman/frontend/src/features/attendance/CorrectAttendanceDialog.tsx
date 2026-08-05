import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { previewHours } from './WorkerHoursDrawer';
import type { AttendanceListRow } from './types';

interface Props {
  row: AttendanceListRow;
  standardShiftHours: number;
  pending: boolean;
  error: unknown;
  onClose: () => void;
  onApply: (input: { enteredHours: number; correctionReason: string }) => void;
}

const MAX_HOURS = 24;

/**
 * Amending a day the worker has already been paid for.
 *
 * <p>Deliberately heavier than the mark screen's hours drawer. A reason is required and the
 * dialog says plainly what the change is worth, because this is not a correction on a
 * notepad — the server posts the difference to the worker's ledger the moment it is
 * applied, and the person doing it should see the number before it moves.</p>
 */
export function CorrectAttendanceDialog({
  row,
  standardShiftHours,
  pending,
  error,
  onClose,
  onApply,
}: Props) {
  const before = row.enteredHours ?? row.workedHours;
  const [hours, setHours] = useState(String(before));
  const [reason, setReason] = useState('');

  const parsed = hours.trim() === '' ? Number.NaN : Number(hours);
  const invalid = Number.isNaN(parsed) || parsed < 0 || parsed > MAX_HOURS;
  const preview = previewHours(invalid ? 0 : parsed, standardShiftHours);
  const unchanged = !invalid && parsed === before;
  const reasonMissing = reason.trim().length === 0;

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Correct {row.workerName ?? 'this row'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            {row.attendanceDate} · verified at {before} h · standard shift {standardShiftHours} h
          </Typography>

          <TextField
            label="Hours worked"
            type="number"
            value={hours}
            onChange={(e) => setHours(e.target.value)}
            inputProps={{ min: 0, max: MAX_HOURS, step: 0.5, inputMode: 'decimal' }}
            error={invalid}
            helperText={invalid ? `Enter the hours worked, between 0 and ${MAX_HOURS}.` : undefined}
            autoFocus
          />

          {!invalid && (
            <Alert severity={unchanged ? 'info' : 'warning'} icon={false}>
              {unchanged ? (
                'Same as the verified figure — nothing to correct.'
              ) : (
                <>
                  {preview.regular} h regular · {preview.overtime} h overtime. The difference
                  against the verified {before} h posts to {row.workerName ?? 'the worker'}’s
                  ledger at the rate this day was verified on.
                </>
              )}
            </Alert>
          )}

          <TextField
            label="Reason for the correction"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            error={reasonMissing && !unchanged}
            helperText="Recorded against the row for good. Say what the evidence is."
            multiline
            minRows={2}
            inputProps={{ maxLength: 2000 }}
          />

          {Boolean(error) && <Alert severity="error">{apiErrorDetail(error)}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} sx={{ minHeight: 48 }}>
          Cancel
        </Button>
        <Button
          variant="contained"
          color="secondary"
          disabled={invalid || unchanged || reasonMissing || pending}
          sx={{ minHeight: 48 }}
          onClick={() => onApply({ enteredHours: parsed, correctionReason: reason.trim() })}
        >
          {pending ? 'Applying…' : 'Apply correction'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
