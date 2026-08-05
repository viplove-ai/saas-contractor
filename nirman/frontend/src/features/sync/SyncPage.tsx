import {
  Alert,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useLiveQuery } from 'dexie-react-hooks';
import { useState } from 'react';
import { offlineDb, type DraftRecord, type SyncStatus } from '../../offline/db';
import { discard, keepMine, listQueue, retryNow } from '../../offline/queue';
import { syncHandlers } from '../../offline/handlers';
import { useSync } from '../../offline/SyncProvider';
import { StatusChip, type RecordStatus } from '../../shared/StatusChip';
import { apiErrorDetail } from '../../shared/apiClient';

/**
 * What the device still owes the server.
 *
 * <p>The screen exists because "12 records not sent" on a banner is an alarm without a
 * remedy. Everything here is either waiting on its own — in which case the screen says so
 * and asks nothing — or is stuck on something a person has to decide, and those are put
 * first because they are the only rows that will still be here tomorrow if nobody
 * touches them.</p>
 */
export function SyncPage() {
  const { online, syncing, syncNow, lastSummary } = useSync();
  const rows = useLiveQuery(() => listQueue(), [], [] as DraftRecord[]);
  const uploads = useLiveQuery(() => offlineDb.uploads.toArray(), [], []);
  const [conflict, setConflict] = useState<DraftRecord | null>(null);

  const unsent = rows.filter((row) => row.status !== 'SYNCED');
  const sent = rows.filter((row) => row.status === 'SYNCED');
  const pendingPhotos = uploads.filter((upload) => upload.status !== 'SYNCED');

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Not sent yet</Typography>

      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Chip
          label={online ? 'Connected' : 'No connection'}
          color={online ? 'success' : 'warning'}
          variant="outlined"
        />
        <Chip label={`${unsent.length} waiting`} />
        {pendingPhotos.length > 0 && <Chip label={`${pendingPhotos.length} photo(s)`} />}
        <Button
          variant="contained"
          color="secondary"
          onClick={() => void syncNow()}
          disabled={syncing || !online}
          sx={{ minHeight: 48 }}
        >
          {syncing ? 'Sending…' : 'Send now'}
        </Button>
        {syncing && <CircularProgress size={20} />}
      </Stack>

      {!online && (
        <Alert severity="info">
          Nothing is lost. Everything below is stored on this phone and goes out by itself the
          moment there is a signal.
        </Alert>
      )}

      {lastSummary && lastSummary.sent > 0 && (
        <Alert severity="success">
          Sent {lastSummary.sent} record(s)
          {lastSummary.uploaded > 0 && ` and ${lastSummary.uploaded} photo(s)`}.
        </Alert>
      )}

      {unsent.length === 0 && (
        <Alert severity="success">Everything on this device has reached the server.</Alert>
      )}

      <Stack spacing={1}>
        {unsent.map((row) => (
          <QueueRow
            key={row.clientId}
            row={row}
            photos={uploads.filter((upload) => upload.clientId === row.clientId).length}
            onResolve={() => setConflict(row)}
          />
        ))}
      </Stack>

      {sent.length > 0 && (
        <>
          <Divider sx={{ pt: 2 }} />
          <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
            Sent recently
          </Typography>
          <Stack spacing={1}>
            {sent.map((row) => (
              <QueueRow key={row.clientId} row={row} photos={0} onResolve={() => {}} />
            ))}
          </Stack>
        </>
      )}

      {conflict && (
        <ConflictDialog record={conflict} onClose={() => setConflict(null)} />
      )}
    </Stack>
  );
}

function QueueRow({
  row,
  photos,
  onResolve,
}: {
  row: DraftRecord;
  photos: number;
  onResolve: () => void;
}) {
  return (
    <Paper elevation={0} sx={{ p: 1.5, border: 1, borderColor: 'divider' }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.5}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <div>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography fontWeight={600}>{row.summary}</Typography>
            <StatusChip status={chipStatus(row.status)} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            {row.businessDate}
            {photos > 0 && ` · ${photos} photo(s)`}
            {row.attempts > 0 && ` · tried ${row.attempts} time(s)`}
          </Typography>
          {row.lastError && (
            <Typography
              variant="body2"
              color={row.status === 'PENDING' ? 'text.secondary' : 'error.main'}
            >
              {row.lastError}
            </Typography>
          )}
        </div>

        {row.status === 'CONFLICT' && (
          <Button variant="contained" color="secondary" onClick={onResolve} sx={{ minHeight: 48 }}>
            Decide
          </Button>
        )}
        {row.status === 'FAILED' && (
          <Stack direction="row" spacing={1}>
            <Button
              variant="outlined"
              onClick={() => void retryNow(row.clientId)}
              sx={{ minHeight: 48 }}
            >
              Try again
            </Button>
            <Button
              color="error"
              onClick={() => void discard(row.clientId)}
              sx={{ minHeight: 48 }}
            >
              Discard
            </Button>
          </Stack>
        )}
      </Stack>
    </Paper>
  );
}

/**
 * The resolvable prompt behind the phase's exit criterion.
 *
 * <p>Two choices, and only two, because there is no third one a supervisor can act on from a
 * phone: keep what this device recorded, or drop it and leave what the server already has.
 * A field-by-field merge sounds better and is unusable — a man was present or he was not,
 * and a screen that offers to combine two answers to that is asking a question with no
 * meaning.</p>
 *
 * <p>Keeping this device's version needs a stated reason and is only offered where the
 * server has a door for it. Where it does not, the honest options are to discard this copy
 * or to go and correct the record on the server, and the dialog says so rather than
 * offering a button that would 409 again.</p>
 */
function ConflictDialog({ record, onClose }: { record: DraftRecord; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const forceable = Boolean(syncHandlers[record.entityType]?.force);

  const keep = async () => {
    setBusy(true);
    setError(null);
    try {
      await keepMine(record.clientId, reason.trim());
      onClose();
    } catch (e) {
      setError(apiErrorDetail(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose}>
      <DialogTitle>{record.summary}</DialogTitle>
      <DialogContent>
        <DialogContentText sx={{ mb: 2 }}>
          {record.lastError ?? 'The server already has a record this one collides with.'}
        </DialogContentText>
        {forceable ? (
          <TextField
            label="If it really is separate, say why"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            fullWidth
            multiline
            minRows={2}
            helperText="This goes on the record, so whoever approves it can see why it was booked twice."
          />
        ) : (
          <Alert severity="info">
            This one cannot be forced through — the server already has a record for the same
            day. Discard this copy and correct the record that is there.
          </Alert>
        )}
        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2, gap: 1, flexWrap: 'wrap' }}>
        <Button onClick={onClose} sx={{ minHeight: 48 }}>
          Leave it
        </Button>
        <Button
          color="error"
          onClick={() => {
            void discard(record.clientId);
            onClose();
          }}
          sx={{ minHeight: 48 }}
        >
          Discard mine
        </Button>
        {forceable && (
          <Button
            variant="contained"
            color="secondary"
            disabled={reason.trim().length === 0 || busy}
            onClick={() => void keep()}
            sx={{ minHeight: 48 }}
          >
            {busy ? 'Sending…' : 'Keep mine'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

function chipStatus(status: SyncStatus): RecordStatus {
  switch (status) {
    case 'SYNCED':
      return 'APPROVED';
    case 'CONFLICT':
      return 'CONFLICT';
    case 'FAILED':
      return 'REJECTED';
    default:
      return 'PENDING';
  }
}
