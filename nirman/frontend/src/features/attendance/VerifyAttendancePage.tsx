import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  CircularProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import { formatHours } from '../../shared/formatters';
import { useSelectedSite } from '../../shared/siteSelection';
import { StatusChip } from '../../shared/StatusChip';
import { useCorrectAttendance, useSites, useVerificationQueue, useVerifyAttendance } from './api';
import { CorrectAttendanceDialog } from './CorrectAttendanceDialog';
import type { AttendanceListRow, AttendanceStatus, WorkflowStatus } from './types';

const STATUS_LABEL: Record<AttendanceStatus, string> = {
  PRESENT: 'Present',
  HALF_DAY: 'Half day',
  ABSENT: 'Absent',
  LEAVE: 'Leave',
};

/** The queue opens on the last week, which is the window a weekly sign-off actually covers. */
const DEFAULT_WINDOW_DAYS = 7;

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

function daysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return isoDate(date);
}

/**
 * The engineer's verification queue. Everything submitted at a site over a date range,
 * signed off or sent back in one action.
 *
 * <p>Two decisions shape it. Verification is a batch operation because an engineer signs
 * off a week of a site at a time, not one man on one day — so the list is multi-select with
 * a select-all, and the action bar names the count it is about to act on. And rejection
 * demands a reason: the supervisor who gets the row back has to know what to fix, and the
 * reason is the only thing that travels with it.</p>
 *
 * <p>The queue shows hours rather than money on purpose. Rates are frozen onto the record
 * at the moment of verification, so before it there is no amount to show that would still
 * be true afterwards.</p>
 */
export function VerifyAttendancePage() {
  const { hasPermission } = useAuth();
  const [from, setFrom] = useState(daysAgo(DEFAULT_WINDOW_DAYS));
  const [to, setTo] = useState(isoDate(new Date()));
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [remarks, setRemarks] = useState('');
  const [viewing, setViewing] = useState<WorkflowStatus>('SUBMITTED');
  const [correcting, setCorrecting] = useState<AttendanceListRow | null>(null);

  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data);
  const queue = useVerificationQueue(siteId || undefined, from, to, viewing);
  const verify = useVerifyAttendance();
  const correct = useCorrectAttendance();

  // A selection belongs to one site, window and list; changing any must not carry ids over.
  useEffect(() => {
    setSelected(new Set());
  }, [siteId, from, to, viewing]);

  const rows = useMemo(() => queue.data?.content ?? [], [queue.data]);

  // Rows leave the queue as they are verified. Pruning here rather than in the mutation's
  // onSuccess keeps the count in the action bar honest even if the list changed underneath
  // us — a second engineer working the same site, say.
  useEffect(() => {
    setSelected((current) => {
      if (current.size === 0) return current;
      const live = new Set(rows.map((row) => row.id));
      const next = new Set([...current].filter((id) => live.has(id)));
      return next.size === current.size ? current : next;
    });
  }, [rows]);

  const signingOff = viewing === 'SUBMITTED';
  // Correcting a paid day is a stronger act than signing one off, and a stricter permission.
  const canCorrect = hasPermission('attendance:correct');
  // The shift length comes from the site rather than the row: overtime starts after it, and
  // a row that is already verified has no roster call behind it to carry it.
  const standardShiftHours =
    sites.data?.find((site) => site.id === siteId)?.standardShiftHours ?? 0;
  const rangeInvalid = from > to;
  const allSelected = rows.length > 0 && selected.size === rows.length;
  const rejectNeedsReason = remarks.trim().length === 0;
  const busy = verify.isPending;

  const toggle = (id: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    setSelected((current) =>
      current.size === rows.length ? new Set() : new Set(rows.map((row) => row.id)),
    );
  };

  const act = (action: 'VERIFY' | 'REJECT') => {
    verify.mutate(
      { ids: [...selected], action, remarks: remarks.trim() || undefined },
      { onSuccess: () => setRemarks('') },
    );
  };

  if (!hasPermission('attendance:verify')) {
    return (
      <Alert severity="warning">
        Verifying attendance is not part of your role. An engineer or the administrator signs
        off submitted muster rolls.
      </Alert>
    );
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Verify attendance</Typography>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
          sx={{ minWidth: 200 }}
        >
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="From"
          type="date"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
        <TextField
          label="To"
          type="date"
          value={to}
          onChange={(e) => setTo(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
      </Stack>

      <ToggleButtonGroup
        exclusive
        value={viewing}
        onChange={(_, value) => value && setViewing(value as WorkflowStatus)}
        size="small"
      >
        <ToggleButton value="SUBMITTED" sx={{ minHeight: 48, px: 2 }}>
          Awaiting verification
        </ToggleButton>
        <ToggleButton value="VERIFIED" sx={{ minHeight: 48, px: 2 }}>
          Verified
        </ToggleButton>
      </ToggleButtonGroup>

      {rangeInvalid && (
        <Alert severity="warning">The From date is after the To date, so nothing can match.</Alert>
      )}

      {queue.isLoading && <CircularProgress />}
      {queue.isError && <Alert severity="error">{apiErrorDetail(queue.error)}</Alert>}

      {queue.data && rows.length === 0 && (
        <Alert severity="info">
          {signingOff
            ? `Nothing is waiting for verification at this site between ${from} and ${to}.`
            : `Nothing has been verified at this site between ${from} and ${to}.`}
        </Alert>
      )}

      {rows.length > 0 && (
        <>
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
            <Chip
              label={`${rows.length} ${signingOff ? 'awaiting verification' : 'verified'}`}
            />
            {signingOff && selected.size > 0 && (
              <Chip label={`${selected.size} selected`} color="secondary" variant="outlined" />
            )}
            {signingOff && (
              <Button onClick={toggleAll} disabled={busy} sx={{ minHeight: 48 }}>
                {allSelected ? 'Clear selection' : 'Select all'}
              </Button>
            )}
          </Stack>

          {queue.data && queue.data.totalElements > rows.length && (
            <Alert severity="info">
              Showing the first {rows.length} of {queue.data.totalElements} rows. Narrow the
              date range to see the rest.
            </Alert>
          )}

          <Stack spacing={1}>
            {rows.map((row) => (
              <Paper
                key={row.id}
                elevation={0}
                sx={{ p: 1.5, border: 1, borderColor: 'divider' }}
              >
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  spacing={1.5}
                  alignItems={{ sm: 'center' }}
                  justifyContent="space-between"
                >
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ minWidth: 0 }}>
                    {signingOff && (
                      <Checkbox
                        checked={selected.has(row.id)}
                        onChange={() => toggle(row.id)}
                        disabled={busy}
                        inputProps={{ 'aria-label': `Select ${describe(row)}` }}
                        sx={{ width: 48, height: 48 }}
                      />
                    )}
                    <Box sx={{ minWidth: 0 }}>
                      <Typography fontWeight={600} noWrap>
                        {row.workerName ?? row.workerId}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        {row.attendanceDate} · {STATUS_LABEL[row.status]} ·{' '}
                        {formatHours(row.workedHours)} worked
                        {row.overtimeHours > 0 && <> · {formatHours(row.overtimeHours)} OT</>}
                      </Typography>
                      {row.overtimeReason && (
                        <Typography variant="body2" color="text.secondary">
                          OT reason: {row.overtimeReason}
                        </Typography>
                      )}
                    </Box>
                  </Stack>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <StatusChip status={signingOff ? 'SUBMITTED' : 'VERIFIED'} />
                    {!signingOff && canCorrect && (
                      <Button
                        onClick={() => setCorrecting(row)}
                        sx={{ minHeight: 48 }}
                      >
                        Correct
                      </Button>
                    )}
                  </Stack>
                </Stack>
              </Paper>
            ))}
          </Stack>

          {signingOff && verify.isError && (
            <Alert severity="error">{apiErrorDetail(verify.error)}</Alert>
          )}
          {signingOff && verify.isSuccess && (
            <Alert severity="success">
              {verify.data.action === 'VERIFY'
                ? `Verified ${verify.data.affected} record(s). Wages are frozen and posted to the worker ledger.`
                : `Sent ${verify.data.affected} record(s) back to the supervisor.`}
            </Alert>
          )}

          {signingOff && (
            <>
              <TextField
                label="Remarks"
                value={remarks}
                onChange={(e) => setRemarks(e.target.value)}
                multiline
                minRows={2}
                inputProps={{ maxLength: 500 }}
                helperText="Required to send rows back, so the supervisor knows what to correct."
              />

              <Stack direction="row" spacing={2}>
                <Button
                  variant="contained"
                  color="secondary"
                  disabled={selected.size === 0 || busy}
                  onClick={() => act('VERIFY')}
                  sx={{ minHeight: 48 }}
                >
                  {busy ? 'Working…' : `Verify ${selected.size} record(s)`}
                </Button>
                <Button
                  variant="outlined"
                  color="error"
                  disabled={selected.size === 0 || busy || rejectNeedsReason}
                  onClick={() => act('REJECT')}
                  sx={{ minHeight: 48 }}
                >
                  {busy ? 'Working…' : `Send back ${selected.size} record(s)`}
                </Button>
              </Stack>
            </>
          )}

          {!signingOff && correct.isSuccess && (
            <Alert severity="success">
              Correction applied. The difference has been posted to the worker's ledger.
            </Alert>
          )}
        </>
      )}

      {correcting && (
        <CorrectAttendanceDialog
          row={correcting}
          standardShiftHours={standardShiftHours}
          pending={correct.isPending}
          error={correct.error}
          onClose={() => setCorrecting(null)}
          onApply={({ enteredHours, correctionReason }) =>
            correct.mutate(
              {
                id: correcting.id,
                enteredHours,
                overtimeReason: correcting.overtimeReason,
                correctionReason,
                version: correcting.version,
              },
              { onSuccess: () => setCorrecting(null) },
            )
          }
        />
      )}
    </Stack>
  );
}

function describe(row: AttendanceListRow): string {
  return `${row.workerName ?? row.workerId} on ${row.attendanceDate}`;
}
