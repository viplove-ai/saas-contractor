import {
  Alert,
  Box,
  Button,
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
import { Link } from 'react-router-dom';
import { inkEdge } from '../../app/sketch';
import { apiErrorDetail } from '../../shared/apiClient';
import { useSelectedSite } from '../../shared/siteSelection';
import { StatusChip, type RecordStatus } from '../../shared/StatusChip';
import { useRoster, useSaveAttendance, useSites, useSubmitAttendance } from './api';
import { WorkerHoursDrawer } from './WorkerHoursDrawer';
import type { AttendanceStatus, MarkDraft, RosterEntry } from './types';

/**
 * Two states, not four. Half day and leave still exist in the record — old rows carry them
 * and the register still reads them — but a supervisor on a slab now answers one question,
 * "did he come", and then says for how long.
 */
const STATUS_OPTIONS: { value: AttendanceStatus; label: string }[] = [
  { value: 'PRESENT', label: 'P' },
  { value: 'ABSENT', label: 'A' },
];

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/** A draft carries a stable id from the moment it is created, so retries stay idempotent. */
type Draft = MarkDraft & { id: string };

/**
 * The muster roll. One screen, one tap per worker, one save.
 *
 * <p>Three decisions shape it. Marking is a two-way toggle rather than a form, because the
 * common case is forty men present and a supervisor should not open forty dialogs to say
 * so. Every present day carries hours, but tapping P fills them with the site's standard
 * shift, so the one tap still stands and only the men who worked longer need opening — the
 * hours are always an explicit figure on the row, never an assumption buried in the server.
 * And nothing is sent until Save, so a tap on the wrong row costs a correction on screen
 * rather than a round trip.</p>
 */
export function MarkAttendancePage() {
  const [date, setDate] = useState(today());
  const [drafts, setDrafts] = useState<Map<string, Draft>>(new Map());
  const [openWorker, setOpenWorker] = useState<RosterEntry | null>(null);

  const sites = useSites();
  // The site the day screen was on, not whichever one sorts first.
  const [siteId, setSiteId] = useSelectedSite(sites.data);
  const roster = useRoster(siteId || undefined, date);
  const site = sites.data?.find((candidate) => candidate.id === siteId);
  // Named now rather than when the queue is read: by then this screen is gone and the sync
  // list would be showing a row of uuids.
  const save = useSaveAttendance(siteId, date, site ? `${site.code} ${site.name}` : 'site');
  const submit = useSubmitAttendance(siteId, date);

  // Unsaved marks belong to one site and day; changing either must not carry them over.
  useEffect(() => {
    setDrafts(new Map());
  }, [siteId, date]);

  // Memoised so the fallback empty array is not a fresh reference on every render, which
  // would invalidate the counts below each time.
  const entries = useMemo(() => roster.data?.entries ?? [], [roster.data]);
  const locked = roster.data?.periodLocked ?? false;
  const standardShiftHours = roster.data?.standardShiftHours ?? 0;

  const counts = useMemo(() => {
    let marked = 0;
    let present = 0;
    for (const entry of entries) {
      const status = drafts.get(entry.workerId)?.status ?? entry.attendance?.status;
      if (status) {
        marked++;
        if (status === 'PRESENT') present++;
      }
    }
    return { marked, present, total: entries.length };
  }, [entries, drafts]);

  /**
   * The hours a present day starts with: whatever is already on the row, else the site's
   * standard shift. Marking absent drops them — there are no hours in a day not worked, and
   * a figure left behind would be sent as if there were.
   */
  const hoursFor = (
    status: AttendanceStatus,
    existing: Draft | undefined,
    entry: RosterEntry,
  ): number | undefined => {
    if (status !== 'PRESENT') return undefined;
    return existing?.enteredHours ?? entry.attendance?.enteredHours ?? standardShiftHours;
  };

  const setStatus = (entry: RosterEntry, status: AttendanceStatus | null) => {
    if (!status) return;
    setDrafts((current) => {
      const next = new Map(current);
      const existing = next.get(entry.workerId);
      next.set(entry.workerId, {
        id: existing?.id ?? entry.attendance?.id ?? crypto.randomUUID(),
        status,
        checkInTime: existing?.checkInTime ?? entry.attendance?.checkInTime,
        checkOutTime: existing?.checkOutTime ?? entry.attendance?.checkOutTime,
        breakMinutes: existing?.breakMinutes ?? entry.attendance?.breakMinutes ?? 0,
        enteredHours: hoursFor(status, existing, entry),
        overtimeReason: existing?.overtimeReason ?? entry.attendance?.overtimeReason,
      });
      return next;
    });
  };

  const markAllPresent = () => {
    setDrafts((current) => {
      const next = new Map(current);
      for (const entry of entries) {
        if (entry.attendance && !isEditable(entry)) continue;
        const existing = next.get(entry.workerId);
        next.set(entry.workerId, {
          id: existing?.id ?? entry.attendance?.id ?? crypto.randomUUID(),
          status: 'PRESENT',
          breakMinutes: existing?.breakMinutes ?? 0,
          checkInTime: existing?.checkInTime,
          checkOutTime: existing?.checkOutTime,
          enteredHours: hoursFor('PRESENT', existing, entry),
          overtimeReason: existing?.overtimeReason,
        });
      }
      return next;
    });
  };

  const saved = save.data;
  const sent = saved && saved.outcome === 'SENT' ? saved.result : null;
  const queued = saved && saved.outcome === 'QUEUED' ? saved : null;
  const rejected = sent?.outcomes.filter((o) => o.outcome === 'REJECTED') ?? [];

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Mark attendance</Typography>

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
          label="Date"
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
          InputLabelProps={{ shrink: true }}
        />
      </Stack>

      {roster.isLoading && <CircularProgress />}
      {roster.isError && <Alert severity="error">{apiErrorDetail(roster.error)}</Alert>}

      {locked && (
        <Alert severity="warning">
          This month is closed for attendance. Nothing can be entered or changed.
        </Alert>
      )}

      {/*
        An empty muster is not an error and not a dead end — it is a supervisor one screen
        away from the thing he has to do first. So it says who is missing, where they are
        taken on, and carries the way there, rather than leaving him to find the Workers page
        himself.
      */}
      {roster.data && entries.length === 0 && (
        <Paper elevation={0} sx={{ ...inkEdge(0), p: 2.5 }}>
          <Stack spacing={1.5} alignItems="flex-start">
            <Typography variant="h3">Nobody to mark yet</Typography>
            <Typography color="text.secondary">
              No worker is posted to {site ? `${site.code} — ${site.name}` : 'this site'} on{' '}
              {date}. Take your men on under Workers and they appear on this muster the same
              day.
            </Typography>
            <Button
              variant="contained"
              color="secondary"
              component={Link}
              to="/workers"
              sx={{ minHeight: 48 }}
            >
              Add workers
            </Button>
          </Stack>
        </Paper>
      )}

      {entries.length > 0 && (
        <>
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap">
            <Chip label={`${counts.marked} of ${counts.total} marked`} />
            <Chip label={`${counts.present} present`} color="success" variant="outlined" />
            <Button onClick={markAllPresent} disabled={locked} sx={{ minHeight: 48 }}>
              Mark all present
            </Button>
          </Stack>

          <Stack spacing={1}>
            {entries.map((entry) => {
              const draft = drafts.get(entry.workerId);
              const status = draft?.status ?? entry.attendance?.status ?? null;
              const editable = !locked && isEditable(entry);
              const hours = draft
                ? draft.enteredHours
                : (entry.attendance?.enteredHours ?? entry.attendance?.workedHours);
              const overtime =
                hours != null && status === 'PRESENT'
                  ? Math.max(0, hours - standardShiftHours)
                  : 0;
              return (
                <Paper
                  key={entry.workerId}
                  elevation={0}
                  sx={{ p: 1.5, border: 1, borderColor: 'divider' }}
                >
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={1.5}
                    alignItems={{ sm: 'center' }}
                    justifyContent="space-between"
                  >
                    <Box sx={{ minWidth: 0, flexGrow: 1 }}>
                      <Typography fontWeight={600} noWrap>
                        {entry.workerName}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        {entry.workerCode}
                        {status === 'PRESENT' && hours != null && (
                          <> · {hours} h worked</>
                        )}
                        {overtime > 0 && <> · {round2(overtime)} h OT</>}
                      </Typography>
                    </Box>

                    <Stack direction="row" spacing={1} alignItems="center">
                      {entry.attendance && (
                        <StatusChip status={chipStatus(entry.attendance.workflowStatus)} />
                      )}
                      <ToggleButtonGroup
                        exclusive
                        value={status}
                        onChange={(_, value) => setStatus(entry, value as AttendanceStatus)}
                        disabled={!editable}
                        size="small"
                      >
                        {STATUS_OPTIONS.map((option) => (
                          <ToggleButton
                            key={option.value}
                            value={option.value}
                            sx={{ minWidth: 48, minHeight: 48, fontWeight: 700 }}
                          >
                            {option.label}
                          </ToggleButton>
                        ))}
                      </ToggleButtonGroup>
                      {/*
                        The hours are the button. A supervisor changing a nine-hour day
                        should not have to guess that something called "Hours" is where the
                        nine lives, and on the common day the label is the confirmation
                        that the standard shift was taken.
                      */}
                      <Button
                        onClick={() => setOpenWorker(entry)}
                        disabled={!editable || status !== 'PRESENT'}
                        sx={{ minHeight: 48, minWidth: 72 }}
                      >
                        {status === 'PRESENT' && hours != null ? `${hours} h` : 'Hours'}
                      </Button>
                    </Stack>
                  </Stack>
                </Paper>
              );
            })}
          </Stack>

          {save.isError && <Alert severity="error">{apiErrorDetail(save.error)}</Alert>}
          {rejected.length > 0 && (
            <Alert severity="warning">
              <Typography fontWeight={600}>
                {rejected.length} row(s) were not saved:
              </Typography>
              {rejected.map((outcome) => (
                <Typography key={outcome.id} variant="body2">
                  {nameFor(entries, outcome.workerId)} — {outcome.reason}
                </Typography>
              ))}
            </Alert>
          )}
          {sent && rejected.length === 0 && (
            <Alert severity="success">
              Saved {sent.accepted}
              {sent.unchanged > 0 && `, ${sent.unchanged} already recorded`}.
            </Alert>
          )}
          {/*
            Not an error, and it must not read like one. The marks are on the phone under the
            same ids they would have gone out with, so the only thing that has not happened
            yet is the sending — and that happens by itself.
          */}
          {queued && (
            <Alert severity="info">
              No connection. {queued.marks} mark(s) are saved on this phone and go out by
              themselves when there is a signal.
            </Alert>
          )}
          {submit.isSuccess && (
            <Alert severity="success">
              Sent {submit.data.submitted} record(s) for verification.
            </Alert>
          )}
          {submit.isError && <Alert severity="error">{apiErrorDetail(submit.error)}</Alert>}

          <Stack direction="row" spacing={2}>
            <Button
              variant="contained"
              color="secondary"
              disabled={locked || drafts.size === 0 || save.isPending}
              onClick={() => save.mutate(drafts)}
              sx={{ minHeight: 48 }}
            >
              {save.isPending ? 'Saving…' : `Save ${drafts.size} mark(s)`}
            </Button>
            <Button
              variant="outlined"
              disabled={locked || submit.isPending}
              onClick={() => submit.mutate()}
              sx={{ minHeight: 48 }}
            >
              {submit.isPending ? 'Sending…' : 'Send for verification'}
            </Button>
          </Stack>
        </>
      )}

      {openWorker && roster.data && (
        <WorkerHoursDrawer
          entry={openWorker}
          draft={drafts.get(openWorker.workerId)}
          standardShiftHours={roster.data.standardShiftHours}
          overtimeReasonRequiredAboveHours={roster.data.overtimeReasonRequiredAboveHours}
          onClose={() => setOpenWorker(null)}
          onApply={(draft) => {
            setDrafts((current) => {
              const next = new Map(current);
              next.set(openWorker.workerId, {
                ...draft,
                id:
                  current.get(openWorker.workerId)?.id ??
                  openWorker.attendance?.id ??
                  crypto.randomUUID(),
              });
              return next;
            });
            setOpenWorker(null);
          }}
        />
      )}
    </Stack>
  );
}

/** Trims the float noise a subtraction leaves behind — 9 − 7 is 2, not 2.0000000000000004. */
function round2(value: number): number {
  return Math.round(value * 100) / 100;
}

/**
 * Draft, rejected and submitted rows may still be changed; the server enforces the same
 * rule. Submitted counts because nothing is frozen or paid until an engineer verifies it,
 * so a supervisor who spots his own mistake can fix it instead of asking to have it
 * rejected back to him first. Verified and locked rows are out of his hands.
 */
function isEditable(entry: RosterEntry): boolean {
  const status = entry.attendance?.workflowStatus;
  return !status || status === 'DRAFT' || status === 'REJECTED' || status === 'SUBMITTED';
}

function chipStatus(status: string): RecordStatus {
  switch (status) {
    case 'SUBMITTED':
      return 'SUBMITTED';
    case 'VERIFIED':
    case 'LOCKED':
      return 'VERIFIED';
    case 'REJECTED':
      return 'REJECTED';
    default:
      return 'DRAFT';
  }
}

function nameFor(entries: RosterEntry[], workerId: string): string {
  return entries.find((e) => e.workerId === workerId)?.workerName ?? workerId;
}
