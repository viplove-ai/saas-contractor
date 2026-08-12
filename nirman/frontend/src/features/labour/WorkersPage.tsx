import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useSelectedSite } from '../../shared/siteSelection';
import { useAuth } from '../auth/AuthContext';
import { useMySites, useSiteDirectory, useWorkers } from './api';
import { OnboardWorkerDialog } from './OnboardWorkerDialog';
import { TransferWorkerDialog } from './TransferWorkerDialog';
import { WAGE_TYPE_LABEL, type Worker } from './types';

/**
 * The men on your sites: who is here, what they are paid, and where they go next.
 *
 * <p>This is the supervisor's own screen, not an office register. He takes a man on at the
 * gate and sends one to another site from the same list — the two things he actually does
 * with labour — and everything else about a worker stays with the office.</p>
 *
 * <p>Nobody appears here who is not posted to one of your sites today, which is also who
 * you can mark. A man you transfer away drops off this list on his start date at the other
 * site, and that is the handover.</p>
 */
export function WorkersPage() {
  const { hasPermission } = useAuth();
  const [search, setSearch] = useState('');
  const [onboarding, setOnboarding] = useState(false);
  const [transferring, setTransferring] = useState<Worker | null>(null);

  const mySites = useMySites();
  // The site the rest of the app is on, and "all my sites" still available beside it.
  const [siteId, setSiteId] = useSelectedSite(mySites.data, { allowAll: true });
  const directory = useSiteDirectory();
  const workers = useWorkers(siteId, search);
  const canWrite = hasPermission('worker:write');
  // A man is taken on *at* a site, so with no posting there is nowhere to put him. Wait for
  // the answer before deciding that — an empty list while loading is not the same as none.
  const noPosting = mySites.isSuccess && mySites.data.length === 0;

  const siteLabel = (id: string | undefined): string => {
    if (!id) {
      return 'Not posted';
    }
    return directory.data?.find((site) => site.id === id)?.code ?? '—';
  };

  const columns: RecordColumn<Worker>[] = [
    {
      key: 'worker',
      header: 'Worker',
      card: 'title',
      cell: (worker) => (
        <>
          <Typography fontWeight={600}>{worker.fullName}</Typography>
          <Typography variant="body2" color="text.secondary">
            {worker.workerCode}
            {worker.mobile ? ` · ${worker.mobile}` : ''}
          </Typography>
        </>
      ),
    },
    { key: 'site', header: 'Site', cell: (worker) => siteLabel(worker.currentSiteId) },
    {
      key: 'rate',
      header: 'Rate',
      align: 'right',
      cell: (worker) =>
        worker.currentWageRate ? (
          <Stack spacing={0}>
            <Typography variant="caption">
              {formatAmount(worker.currentWageRate.normalRate)}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {WAGE_TYPE_LABEL[worker.wageType]}
            </Typography>
          </Stack>
        ) : (
          // Not an error, but it does mean his days will carry no money until the office
          // sets one — worth saying rather than showing a dash.
          <Typography variant="body2" color="text.secondary">
            Not set by office
          </Typography>
        ),
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (worker) => (
        <Chip
          size="small"
          color={worker.active ? 'success' : 'default'}
          variant={worker.active ? 'filled' : 'outlined'}
          label={worker.active ? 'Working' : 'Left'}
        />
      ),
    },
    ...(canWrite
      ? [
          {
            key: 'actions',
            header: 'Actions',
            align: 'right' as const,
            card: 'actions' as const,
            cell: (worker: Worker) => (
              <Button
                size="small"
                onClick={() => setTransferring(worker)}
                disabled={!worker.currentSiteId}
              >
                Transfer
              </Button>
            ),
          },
        ]
      : []),
  ];

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography variant="h1">Workers</Typography>
          <Typography color="text.secondary">
            The men posted to your sites today — the same men you can mark.
          </Typography>
        </Stack>
        {canWrite && (
          <Button
            variant="contained"
            color="secondary"
            onClick={() => setOnboarding(true)}
            disabled={noPosting}
          >
            Take on a worker
          </Button>
        )}
      </Stack>

      {canWrite && noPosting && (
        <Alert severity="info">
          You are not posted to a site yet, so there is nowhere to take a man on. Ask the
          office to post you to your site, then sign out and in again.
        </Alert>
      )}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Search by name or number"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(event) => setSiteId(event.target.value)}
          sx={{ minWidth: 240 }}
        >
          <MenuItem value="">All my sites</MenuItem>
          {(mySites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {workers.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {workers.isError && <Alert severity="error">{apiErrorDetail(workers.error)}</Alert>}

      {workers.data && (
        <RecordTable
          columns={columns}
          rows={workers.data.content}
          rowKey={(worker) => worker.id}
          ariaLabel="Workers"
          empty={
            <Typography color="text.secondary">
              {/* With no posting the banner above has already said why. */}
              {noPosting
                ? 'Nobody to show until you are posted to a site.'
                : 'Nobody on your sites yet. Take on a worker to start marking attendance.'}
            </Typography>
          }
        />
      )}

      <OnboardWorkerDialog
        open={onboarding}
        defaultSiteId={siteId}
        onClose={() => setOnboarding(false)}
      />
      <TransferWorkerDialog worker={transferring} onClose={() => setTransferring(null)} />
    </Stack>
  );
}
