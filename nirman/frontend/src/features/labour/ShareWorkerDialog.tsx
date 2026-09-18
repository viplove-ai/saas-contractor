import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import {
  useAllocations,
  useEndPosting,
  useMySites,
  useShareWorker,
  useSiteDirectory,
} from './api';
import type { Worker } from './types';

interface Props {
  worker: Worker | null;
  onClose: () => void;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Lending a man to a second site while he stays on the first.
 *
 * <p>The transfer is a handover: he leaves one roll for another. This is the other thing
 * that happens on a site — the mason whose mornings are the main block's and whose
 * afternoons are the annexe's — and until V64 the register had no way to say it, so he was
 * marked on one roll and worked on two, and the second site's labour cost was the first's.
 * From the date he is on both rosters, and each site marks him a half day; the server refuses
 * anything that comes to more than a day across the two.</p>
 *
 * <p><b>Whose sites he can be shared with depends on who is asking.</b> A supervisor lends
 * him among the sites he himself supervises — the list is his own postings — because a share
 * keeps the man on his roll and he answers for both. The office sees every site and lends him
 * anywhere. The server draws the same line; the list only spares the caller a refusal.</p>
 *
 * <p>The postings he holds today are listed above the form, each with its own way out: a
 * share is ended on a last day, and his other sites keep him. His last posting cannot be
 * ended here — that is a transfer or a standing-down, both of which say where he went.</p>
 */
export function ShareWorkerDialog({ worker, onClose }: Props) {
  const { user } = useAuth();
  const seesAllSites = user?.allSites ?? false;
  const directory = useSiteDirectory();
  const mySites = useMySites();
  const postings = useAllocations(worker?.id);
  const share = useShareWorker();
  const end = useEndPosting();

  const [siteId, setSiteId] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(today());
  const [ending, setEnding] = useState<{ allocationId: string; lastDay: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const label = (id: string): string => {
    const site = directory.data?.find((entry) => entry.id === id);
    return site ? `${site.code} — ${site.name}` : '—';
  };

  const open = (postings.data ?? []).filter((posting) => !posting.effectiveTo);
  const standing = new Set(open.map((posting) => posting.siteId));
  const candidates = (seesAllSites
    ? (directory.data ?? []).filter((site) => site.status !== 'CLOSED')
    : (mySites.data ?? [])
  ).filter((site) => !standing.has(site.id));

  async function lend() {
    if (!worker) {
      return;
    }
    setError(null);
    try {
      await share.mutateAsync({ workerId: worker.id, siteId, effectiveFrom });
      setSiteId('');
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  async function endShare() {
    if (!worker || !ending) {
      return;
    }
    setError(null);
    try {
      await end.mutateAsync({ workerId: worker.id, ...ending });
      setEnding(null);
    } catch (cause) {
      setError(apiErrorDetail(cause));
    }
  }

  return (
    <Dialog open={worker !== null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Share {worker?.fullName}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <DialogContentText>
            He stays on every roll he is on and gains another. Each site marks him a half day;
            a whole day at one and anything at the other is refused.
          </DialogContentText>

          <Stack spacing={1}>
            <Typography variant="overline" color="text.secondary">
              Standing on today
            </Typography>
            {open.map((posting) => (
              <Stack
                key={posting.id}
                direction="row"
                alignItems="center"
                justifyContent="space-between"
                spacing={1}
              >
                <Stack spacing={0}>
                  <Typography variant="body2">{label(posting.siteId)}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    since {posting.effectiveFrom}
                  </Typography>
                </Stack>
                {ending?.allocationId === posting.id ? (
                  <Stack direction="row" spacing={1} alignItems="center">
                    <TextField
                      label="Last day"
                      type="date"
                      size="small"
                      value={ending.lastDay}
                      onChange={(e) => setEnding({ ...ending, lastDay: e.target.value })}
                      InputLabelProps={{ shrink: true }}
                    />
                    <Button size="small" onClick={() => setEnding(null)}>
                      Keep
                    </Button>
                    <Button
                      size="small"
                      color="error"
                      variant="contained"
                      disabled={end.isPending}
                      onClick={endShare}
                    >
                      End it
                    </Button>
                  </Stack>
                ) : (
                  /*
                    His last posting is not offered a way out: a man must stand somewhere,
                    and the server refuses it with the same sentence the button would show.
                  */
                  open.length > 1 && (
                    <Button
                      size="small"
                      onClick={() => setEnding({ allocationId: posting.id, lastDay: today() })}
                    >
                      End posting
                    </Button>
                  )
                )}
              </Stack>
            ))}
            {postings.isSuccess && open.length === 0 && (
              <Typography variant="body2" color="text.secondary">
                Not posted anywhere. Post him to a site first; sharing is for a man who already
                stands somewhere.
              </Typography>
            )}
          </Stack>

          <Divider />

          <TextField
            select
            label="Also send him to"
            value={siteId}
            onChange={(e) => setSiteId(e.target.value)}
            helperText={
              seesAllSites
                ? 'Any site in the company, on any project.'
                : candidates.length === 0
                  ? 'He already stands on every site you supervise.'
                  : 'The other sites you supervise.'
            }
          >
            {candidates.map((site) => (
              <MenuItem key={site.id} value={site.id}>
                {site.code} — {site.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="From"
            type="date"
            value={effectiveFrom}
            onChange={(e) => setEffectiveFrom(e.target.value)}
            InputLabelProps={{ shrink: true }}
            helperText="His first day on the other site's roll."
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Close</Button>
        <Button
          variant="contained"
          color="secondary"
          disabled={!siteId || !effectiveFrom || open.length === 0 || share.isPending}
          onClick={lend}
        >
          Share
        </Button>
      </DialogActions>
    </Dialog>
  );
}
