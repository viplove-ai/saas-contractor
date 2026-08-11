import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormHelperText,
  MenuItem,
  Stack,
  Switch,
  TextField,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAdminSites, useCreateStore, useUpdateStore } from './api';
import { storeSchema, type StoreForm } from './schema';
import type { AdminStore } from './types';

interface Props {
  open: boolean;
  /** Null adds a store; a store edits that one. */
  store: AdminStore | null;
  /** Preselected when the register is already narrowed to one site. */
  siteId?: string;
  onClose: () => void;
}

/**
 * Adding a second store to a site, or correcting the one it was given.
 *
 * <p>Nobody uses this to create the <em>first</em> store — a site arrives with one. It is for
 * the site that turns out to keep the cement in the shed and the steel on the far side of
 * the plot, which is two balances and has to be two stores, because one balance covering
 * both would report a quantity that is in neither place.</p>
 */
export function StoreFormDialog({ open, store, siteId, onClose }: Props) {
  const editing = store !== null;
  const sites = useAdminSites('');
  const createStore = useCreateStore();
  const updateStore = useUpdateStore();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<StoreForm>({
    resolver: zodResolver(storeSchema),
    defaultValues: {
      siteId: '',
      code: '',
      name: '',
      location: '',
      defaultStore: false,
      active: true,
    },
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    reset(
      store
        ? {
            siteId: store.siteId,
            code: store.code,
            name: store.name,
            location: store.location ?? '',
            defaultStore: store.defaultStore,
            active: store.active,
          }
        : {
            siteId: siteId ?? '',
            code: '',
            name: '',
            location: '',
            defaultStore: false,
            active: true,
          },
    );
  }, [open, store, siteId, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      if (store) {
        await updateStore.mutateAsync({ ...values, id: store.id, version: store.version });
      } else {
        await createStore.mutateAsync(values);
      }
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{editing ? `Edit ${store.code}` : 'Add a store'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <Controller
            control={control}
            name="siteId"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Site"
                // A store cannot move between sites: its ledger is the record of what stood
                // at that gate, and moving it would rewrite where material has been.
                disabled={editing}
                error={!!errors.siteId}
                helperText={errors.siteId?.message}
              >
                {(sites.data ?? []).map((site) => (
                  <MenuItem key={site.id} value={site.id}>
                    {site.code} — {site.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />

          <TextField
            label="Store code"
            error={!!errors.code}
            helperText={errors.code?.message ?? 'Short and unique, e.g. KSN-A-STEEL.'}
            {...register('code')}
          />
          <TextField
            label="Store name"
            error={!!errors.name}
            helperText={errors.name?.message}
            {...register('name')}
          />
          <TextField
            label="Where it is (optional)"
            error={!!errors.location}
            helperText={errors.location?.message ?? 'e.g. north gate, behind the batching plant.'}
            {...register('location')}
          />

          <Controller
            control={control}
            name="defaultStore"
            render={({ field }) => (
              <div>
                <FormControlLabel
                  control={<Switch checked={field.value} onChange={field.onChange} />}
                  label="The site's default store"
                />
                <FormHelperText>
                  Where the receive and issue screens open. Turning this on takes it off
                  whichever store holds it now — a site has exactly one.
                </FormHelperText>
              </div>
            )}
          />

          {editing && (
            <Controller
              control={control}
              name="active"
              render={({ field }) => (
                <div>
                  <FormControlLabel
                    control={<Switch checked={field.value} onChange={field.onChange} />}
                    label="In use"
                  />
                  <FormHelperText>
                    Turn this off for a store that is finished with. Its ledger and balances
                    stay readable; no new receipt or issue can name it.
                  </FormHelperText>
                </div>
              )}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          {editing ? 'Save' : 'Add store'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
