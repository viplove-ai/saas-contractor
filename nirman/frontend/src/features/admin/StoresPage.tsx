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
import { Link, useSearchParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useAuth } from '../auth/AuthContext';
import { useAdminSites, useDeleteStore, useStores } from './api';
import { StoreFormDialog } from './StoreFormDialog';
import type { AdminStore } from './types';

/**
 * The stores register: where material is kept, per site.
 *
 * <p>Most of what this screen shows, nobody typed. Every site is given a store the moment it
 * is created, named after the site, because a store is not a decision anybody was making —
 * it is the shed by the gate, and a site without one meets its first lorry with an empty
 * store picker on the receive screen.</p>
 *
 * <p>What the screen is <em>for</em> is the site that needs more than one. A cement store and
 * a separate steel yard are two balances and have to be two stores; one covering both would
 * report a quantity that is in neither place. So this is where the second store is added, the
 * machine-made name is corrected, and a lockup that is finished with is taken out of use.</p>
 *
 * <p>Deleting is the narrow case, and deliberately so: the server refuses any store the stock
 * ledger has ever touched. What can go is a store nothing was recorded against — the one
 * added twice on a Monday. Everything else goes inactive, which keeps its ledger readable and
 * stops new documents naming it.</p>
 */
export function StoresPage() {
  const { hasPermission } = useAuth();
  /**
   * The site filter lives in the URL, for the reason the sites register's project filter
   * does: a narrowed register is a link that survives a reload and can be sent to somebody.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const siteId = searchParams.get('siteId') ?? '';
  const setSiteId = (id: string) => {
    setSearchParams(id === '' ? {} : { siteId: id }, { replace: true });
  };

  const [editing, setEditing] = useState<AdminStore | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const sites = useAdminSites('');
  const stores = useStores(siteId);
  const deleteStore = useDeleteStore();
  const canWrite = hasPermission('site:write');
  const canDelete = hasPermission('site:delete');

  const openNew = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (store: AdminStore) => {
    setEditing(store);
    setFormOpen(true);
  };

  const remove = async (store: AdminStore) => {
    setDeleteError(null);
    try {
      await deleteStore.mutateAsync(store.id);
    } catch (error) {
      // Refusing is the normal answer here — a store with a ledger cannot go — so the reason
      // is shown on the screen rather than in a dialog that has already closed.
      setDeleteError(apiErrorDetail(error));
    }
  };

  const columns: RecordColumn<AdminStore>[] = [
    {
      key: 'store',
      header: 'Store',
      card: 'title',
      cell: (store) => (
        <>
          <Typography fontWeight={600}>{store.code}</Typography>
          <Typography variant="body2" color="text.secondary">
            {store.name}
          </Typography>
        </>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (store) =>
        store.active ? (
          <Chip
            size="small"
            color={store.defaultStore ? 'success' : 'default'}
            label={store.defaultStore ? 'Default' : 'In use'}
          />
        ) : (
          <Chip size="small" variant="outlined" label="Not in use" />
        ),
    },
    {
      key: 'site',
      header: 'Site',
      cell: (store) => (
        <>
          <Typography variant="body2">{store.siteCode ?? '—'}</Typography>
          <Typography variant="caption" color="text.secondary">
            {store.siteName ?? ''}
          </Typography>
        </>
      ),
    },
    {
      key: 'location',
      header: 'Where it is',
      cell: (store) => (
        <Typography variant="body2" color={store.location ? 'text.primary' : 'text.secondary'}>
          {store.location || 'Not noted'}
        </Typography>
      ),
    },
  ];

  if (canWrite) {
    columns.push({
      key: 'actions',
      header: 'Actions',
      align: 'right',
      card: 'actions',
      cell: (store) => (
        <>
          <Button size="small" onClick={() => openEdit(store)}>
            Edit
          </Button>
          {canDelete && (
            <Button size="small" color="error" onClick={() => void remove(store)}>
              Delete
            </Button>
          )}
        </>
      ),
    });
  }

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography variant="h1">Stores</Typography>
          <Typography color="text.secondary">
            Where material is kept. Every site is given one when it is created — add another
            here if a site keeps its cement and its steel apart.
          </Typography>
        </Stack>
        {canWrite && (
          <Button
            variant="contained"
            color="secondary"
            onClick={openNew}
            // A store belongs to a site, so with none on file the button would only open a
            // form that cannot be submitted.
            disabled={sites.data?.length === 0}
          >
            Add a store
          </Button>
        )}
      </Stack>

      <TextField
        select
        label="Site"
        value={siteId}
        onChange={(event) => setSiteId(event.target.value)}
        sx={{ maxWidth: 320 }}
      >
        <MenuItem value="">All sites</MenuItem>
        {(sites.data ?? []).map((site) => (
          <MenuItem key={site.id} value={site.id}>
            {site.code} — {site.name}
          </MenuItem>
        ))}
      </TextField>

      {deleteError && (
        <Alert severity="error" onClose={() => setDeleteError(null)}>
          {deleteError}
        </Alert>
      )}

      {stores.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {stores.isError && <Alert severity="error">{apiErrorDetail(stores.error)}</Alert>}

      {stores.data && (
        <RecordTable
          columns={columns}
          rows={stores.data}
          rowKey={(store) => store.id}
          ariaLabel="Stores"
          empty={
            <Typography color="text.secondary">
              {sites.data?.length === 0 ? (
                <>
                  A store belongs to a site, and there are none yet —{' '}
                  <Link to="/sites">add a site first</Link>, and it will arrive with its own
                  store.
                </>
              ) : (
                'No stores here yet.'
              )}
            </Typography>
          }
        />
      )}

      <StoreFormDialog
        open={formOpen}
        store={editing}
        siteId={siteId}
        onClose={() => setFormOpen(false)}
      />
    </Stack>
  );
}
