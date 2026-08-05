import { Alert, Box, Button, Drawer, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import type { MarkDraft, RosterEntry } from './types';

interface Props {
  entry: RosterEntry;
  draft?: MarkDraft | undefined;
  standardShiftHours: number;
  overtimeReasonRequiredAboveHours: number;
  onClose: () => void;
  onApply: (draft: MarkDraft) => void;
}

/** Nobody works a negative day, and nobody works more than one. */
const MAX_HOURS = 24;

/**
 * How long the man worked. One number, because that is the fact a supervisor actually has:
 * on a site where nobody carries a clock, "he did nine hours" is what is known, and a
 * check-in and a check-out were being invented to express it.
 *
 * <p>The split into regular and overtime is shown as it is typed, so the supervisor sees
 * the two hours of overtime he is about to record rather than discovering them after
 * verification. The server remains the authority; this is a courtesy, not a second
 * implementation of the rules.</p>
 */
export function WorkerHoursDrawer({
  entry,
  draft,
  standardShiftHours,
  overtimeReasonRequiredAboveHours,
  onClose,
  onApply,
}: Props) {
  const existing = entry.attendance;
  const [hours, setHours] = useState(
    String(draft?.enteredHours ?? existing?.enteredHours ?? standardShiftHours),
  );
  const [overtimeReason, setOvertimeReason] = useState(
    draft?.overtimeReason ?? existing?.overtimeReason ?? '',
  );

  const parsed = hours.trim() === '' ? Number.NaN : Number(hours);
  const invalid = Number.isNaN(parsed) || parsed < 0 || parsed > MAX_HOURS;
  const preview = previewHours(invalid ? 0 : parsed, standardShiftHours);
  const reasonMissing =
    preview.overtime > overtimeReasonRequiredAboveHours && overtimeReason.trim() === '';

  return (
    <Drawer anchor="bottom" open onClose={onClose}>
      <Box sx={{ p: 3, maxWidth: 560, mx: 'auto', width: '100%' }}>
        <Stack spacing={2.5}>
          <Box>
            <Typography variant="h6">{entry.workerName}</Typography>
            <Typography variant="body2" color="text.secondary">
              {entry.workerCode} · standard shift {standardShiftHours} h
            </Typography>
          </Box>

          <TextField
            label="Hours worked"
            type="number"
            value={hours}
            onChange={(e) => setHours(e.target.value)}
            inputProps={{ min: 0, max: MAX_HOURS, step: 0.5, inputMode: 'decimal' }}
            error={invalid}
            helperText={
              invalid ? `Enter the hours worked, between 0 and ${MAX_HOURS}.` : undefined
            }
            autoFocus
          />

          {!invalid && (
            <Alert severity="info" icon={false}>
              {preview.regular} h regular
              {preview.overtime > 0
                ? ` · ${preview.overtime} h overtime, everything past the ${standardShiftHours} h shift`
                : ' · no overtime'}
            </Alert>
          )}

          {preview.overtime > 0 && (
            <TextField
              label={
                reasonMissing
                  ? `Overtime reason (required above ${overtimeReasonRequiredAboveHours} h)`
                  : 'Overtime reason'
              }
              value={overtimeReason}
              onChange={(e) => setOvertimeReason(e.target.value)}
              error={reasonMissing}
              helperText={
                reasonMissing ? 'The server will refuse this row without a reason.' : undefined
              }
              multiline
              minRows={2}
            />
          )}

          <Stack direction="row" spacing={2} justifyContent="flex-end">
            <Button onClick={onClose} sx={{ minHeight: 48 }}>
              Cancel
            </Button>
            <Button
              variant="contained"
              color="secondary"
              disabled={invalid || reasonMissing}
              sx={{ minHeight: 48 }}
              onClick={() =>
                onApply({
                  status: 'PRESENT',
                  breakMinutes: draft?.breakMinutes ?? existing?.breakMinutes ?? 0,
                  enteredHours: parsed,
                  overtimeReason: overtimeReason.trim() || undefined,
                })
              }
            >
              Apply
            </Button>
          </Stack>
        </Stack>
      </Box>
    </Drawer>
  );
}

export interface HoursPreview {
  regular: number;
  overtime: number;
}

/**
 * Mirrors the server's AttendanceCalculator for display only — the server decides what is
 * stored, and disagreement here costs a surprise rather than a wrong wage.
 *
 * <p>Exported so it can be unit-tested against the same cases as the Java original. A copy
 * of somebody else's arithmetic drifts the moment nobody is watching it.</p>
 */
export function previewHours(worked: number, standardShiftHours: number): HoursPreview {
  const regular = Math.min(worked, standardShiftHours);
  const overtime = Math.max(0, worked - standardShiftHours);
  return { regular: round2(regular), overtime: round2(overtime) };
}

function round2(value: number): number {
  return Math.round(value * 100) / 100;
}
