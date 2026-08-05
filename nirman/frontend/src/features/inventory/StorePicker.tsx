import { MenuItem, Stack, TextField } from '@mui/material';
import { useEffect } from 'react';
import { useSites, useStores } from './api';

interface Props {
  siteId: string;
  storeId: string;
  date: string;
  onSiteChange: (siteId: string) => void;
  onStoreChange: (storeId: string) => void;
  onDateChange: (date: string) => void;
  dateLabel: string;
}

/**
 * Site, store and date — the three things every inventory document starts with.
 *
 * <p>Both pickers default themselves. Most supervisors reach exactly one site with exactly
 * one store, and making them choose from a list of one before they can type anything is a
 * tap spent on nothing.</p>
 */
export function StorePicker({
  siteId,
  storeId,
  date,
  onSiteChange,
  onStoreChange,
  onDateChange,
  dateLabel,
}: Props) {
  const sites = useSites();
  const stores = useStores(siteId || undefined);

  useEffect(() => {
    if (!siteId && sites.data?.length) {
      onSiteChange(sites.data[0]!.id);
    }
  }, [sites.data, siteId, onSiteChange]);

  useEffect(() => {
    if (!storeId && stores.data?.length) {
      const preferred = stores.data.find((store) => store.defaultStore) ?? stores.data[0]!;
      onStoreChange(preferred.id);
    }
  }, [stores.data, storeId, onStoreChange]);

  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
      <TextField
        select
        label="Site"
        value={siteId}
        onChange={(e) => {
          onSiteChange(e.target.value);
          onStoreChange('');
        }}
        sx={{ minWidth: 200 }}
      >
        {(sites.data ?? []).map((site) => (
          <MenuItem key={site.id} value={site.id}>
            {site.code} — {site.name}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        select
        label="Store"
        value={storeId}
        onChange={(e) => onStoreChange(e.target.value)}
        sx={{ minWidth: 200 }}
      >
        {(stores.data ?? []).map((store) => (
          <MenuItem key={store.id} value={store.id}>
            {store.name}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        label={dateLabel}
        type="date"
        value={date}
        onChange={(e) => onDateChange(e.target.value)}
        InputLabelProps={{ shrink: true }}
      />
    </Stack>
  );
}
