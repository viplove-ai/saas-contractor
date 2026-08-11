import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from './apiClient';

interface Props {
  open: boolean;
  /** "project" or "site" — used in the prose, so it goes in lower case. */
  kind: string;
  /** What is being removed, in the words on the list: "KSN01 — Kausani Guest House". */
  label: string;
  /** Sites that will go down with a project, if any. Empty for a site of its own. */
  cascade?: string[];
  /**
   * What deletion means for this kind of record, when it is not the register's own story.
   * A project or a site goes to the deleted list and can be put back; a daily report frees
   * its day instead, and saying "an administrator can restore it" there would be a lie.
   */
  description?: string;
  onConfirm: (reason: string) => Promise<unknown>;
  onClose: () => void;
}

/**
 * Confirming a deletion, and writing down why.
 *
 * <p>The reason is required rather than encouraged. Six months on, a project that vanished
 * without an explanation is indistinguishable from data loss, and the person asking what
 * happened is never the person who did it — so the box is the only way past this dialog.</p>
 *
 * <p>The server's refusal is shown here rather than as a toast, because a refusal is nearly
 * always the interesting answer: it arrives carrying the count of what is recorded at the
 * site, which is the fact that changes the admin's mind about deleting it at all.</p>
 */
export function DeleteRecordDialog({
  open,
  kind,
  label,
  cascade = [],
  description,
  onConfirm,
  onClose,
}: Props) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // A dialog reopened for a different row must not carry the last one's typing or its error.
  useEffect(() => {
    if (open) {
      setReason('');
      setError(null);
      setBusy(false);
    }
  }, [open]);

  const submit = async () => {
    if (reason.trim() === '') {
      setError('Say why this is being deleted. It is the only record of the decision.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onConfirm(reason.trim());
      onClose();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Delete {label}?</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <DialogContentText>
            {description ??
              'It comes off the lists and the dashboards, and stays in the deleted register where an administrator can put it back. Nothing is erased.'}
          </DialogContentText>

          {cascade.length > 0 && (
            <Alert severity="warning">
              {cascade.length === 1
                ? 'Its site goes with it: '
                : `Its ${cascade.length} sites go with it: `}
              {cascade.join(', ')}. Restoring the {kind} brings them back.
            </Alert>
          )}

          {error && <Alert severity="error">{error}</Alert>}

          <TextField
            label="Why is it being deleted?"
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            required
            multiline
            minRows={2}
            inputProps={{ maxLength: 500 }}
            placeholder="Duplicate of KSN01 — entered twice on 3 March"
            autoFocus
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          Keep it
        </Button>
        <Button onClick={() => void submit()} color="error" variant="contained" disabled={busy}>
          Delete {kind}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
