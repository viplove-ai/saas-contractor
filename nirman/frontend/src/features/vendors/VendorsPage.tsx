import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import { useVendors } from './api';
import { VendorFormDialog } from './VendorFormDialog';
import { VENDOR_TYPE_LABEL, type Vendor, type VendorType } from './types';

/**
 * The supplier register.
 *
 * <p>Everything about a dealer was already in the system except a screen to put it on: the
 * table has carried his GSTIN and his bank details since the first migration, and the only
 * way to fill them in was a SQL prompt. This is that screen, and the row leads to his
 * account — what he has sent, what he has billed, and what has gone out to him.</p>
 */
export function VendorsPage() {
  const { hasPermission } = useAuth();
  const [q, setQ] = useState('');
  const [type, setType] = useState('');
  const [editing, setEditing] = useState<Vendor | null>(null);
  const [formOpen, setFormOpen] = useState(false);

  const vendors = useVendors(q, type);
  /*
    Two ways to be allowed to write on this register and they are not the same act. The
    office (vendor:write) sets the tax numbers, the bank account and the credit period; the
    field (masterdata:provisional:supplier) says what the firm is called, what it supplies
    and how to reach it. Both open the same dialog, which shows each of them its own half —
    see VendorFormDialog. The account, which is money, stays behind the money permission,
    so the supervisor's rows lead nowhere he may not go.
  */
  const canWrite =
    hasPermission('vendor:write') || hasPermission('masterdata:provisional:supplier');
  const canSeeMoney = hasPermission('vendor:balance:manage');

  const openNew = () => {
    setEditing(null);
    setFormOpen(true);
  };

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography variant="h1">Suppliers</Typography>
          <Typography color="text.secondary">
            Dealers, subcontractors and transporters — how to reach them, what they have sent,
            and where each account stands.
          </Typography>
        </Stack>
        {canWrite && (
          <Button variant="contained" color="secondary" onClick={openNew}>
            Onboard a supplier
          </Button>
        )}
      </Stack>

      {/*
        Both filters carry an explicit flex basis, and on a desk neither of them may be left
        to work one out for itself.

        The theme makes every TextField `fullWidth`, so a field with the default
        `flex-basis: auto` measures itself as the whole row. That is what the Kind picker did:
        it took all 950px of a desktop row, leaving no free space for the Search box beside it
        — which asks for `flex: 1`, a basis of zero, and therefore rendered exactly zero pixels
        wide. A search field that is invisible until the window is narrow enough to stack the
        two is not a subtle piece of misalignment.

        Only from `sm` up, where the row exists. Stacked on a phone the fields run down the
        page and full width is right; a basis there would be setting their height.
      */}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          label="Search"
          value={q}
          onChange={(event) => setQ(event.target.value)}
          placeholder="Name or code"
          sx={{ flex: { sm: '1 1 0' }, maxWidth: { sm: 320 } }}
        />
        <TextField
          select
          label="Kind"
          value={type}
          onChange={(event) => setType(event.target.value)}
          sx={{ flex: { sm: '0 0 200px' } }}
        >
          <MenuItem value="">All kinds</MenuItem>
          {(Object.keys(VENDOR_TYPE_LABEL) as VendorType[]).map((option) => (
            <MenuItem key={option} value={option}>
              {VENDOR_TYPE_LABEL[option]}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      {vendors.isLoading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}
      {vendors.isError && <Alert severity="error">{apiErrorDetail(vendors.error)}</Alert>}

      {/* Phone: a card each. The register has six columns and the last is Actions, which on
          a 375px screen sits off the right edge of a table nobody thinks to scroll. */}
      {vendors.data && (
        <Stack
          component="ul"
          aria-label="Suppliers"
          spacing={2}
          sx={{ display: { xs: 'flex', md: 'none' }, listStyle: 'none', m: 0, p: 0 }}
        >
          {vendors.data.map((vendor) => (
            <Paper
              key={vendor.id}
              component="li"
              elevation={0}
              sx={{ p: 2, border: 1, borderColor: 'divider' }}
            >
              <Stack spacing={1}>
                <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                  <Box sx={{ minWidth: 0 }}>
                    <Typography fontWeight={600}>{vendor.name}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {vendor.code} · {VENDOR_TYPE_LABEL[vendor.vendorType]}
                    </Typography>
                  </Box>
                  {!vendor.active && <Chip size="small" label="Inactive" />}
                  {vendor.provisional && <Chip size="small" label="Details pending" />}
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {contactLine(vendor)}
                </Typography>
                <Stack direction="row" spacing={1}>
                  {canSeeMoney && (
                    <Button size="small" component={Link} to={`/vendors/${vendor.id}`}>
                      Account
                    </Button>
                  )}
                  {canWrite && (
                    <Button
                      size="small"
                      onClick={() => {
                        setEditing(vendor);
                        setFormOpen(true);
                      }}
                    >
                      Edit
                    </Button>
                  )}
                </Stack>
              </Stack>
            </Paper>
          ))}
          {vendors.data.length === 0 && (
            <Typography component="li" color="text.secondary">
              No suppliers on the register yet.
            </Typography>
          )}
        </Stack>
      )}

      {vendors.data && (
        <Paper
          elevation={0}
          sx={{
            display: { xs: 'none', md: 'block' },
            border: 1,
            borderColor: 'divider',
            overflowX: 'auto',
          }}
        >
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Supplier</TableCell>
                <TableCell>Kind</TableCell>
                {/*
                  The person and the number, in two columns, where the GSTIN and the credit
                  period used to be. Both of those are read once a year by one person; the
                  question this register is actually opened with is "who do I ring about the
                  lorry", and it was answered by a column that ran the two together and by
                  two columns nobody was reading. The GSTIN and the terms are still on his
                  row — they are in the edit dialog and on his account — and this is the
                  list, which is for finding him.
                */}
                <TableCell>Contact person</TableCell>
                <TableCell>Phone</TableCell>
                <TableCell align="right">Actions</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {vendors.data.map((vendor) => (
                <TableRow key={vendor.id} hover>
                  <TableCell>
                    <Typography fontWeight={600}>{vendor.name}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {vendor.code}
                      {!vendor.active && ' · inactive'}
                      {/* Named from the field: he can be used, and the office still owes him
                          a GSTIN before his tax can be claimed back. */}
                      {vendor.provisional && ' · details pending'}
                    </Typography>
                  </TableCell>
                  <TableCell>{VENDOR_TYPE_LABEL[vendor.vendorType]}</TableCell>
                  {/* An em dash, not a blank: "nobody has entered it" is worth reading as
                      a gap rather than as an empty cell somebody might take for none. */}
                  <TableCell>{vendor.contactPerson || '—'}</TableCell>
                  <TableCell>{vendor.mobile || '—'}</TableCell>
                  <TableCell align="right">
                    <Stack direction="row" spacing={1} justifyContent="flex-end">
                      {canSeeMoney && (
                        <Button size="small" component={Link} to={`/vendors/${vendor.id}`}>
                          Account
                        </Button>
                      )}
                      {canWrite && (
                        <Button
                          size="small"
                          onClick={() => {
                            setEditing(vendor);
                            setFormOpen(true);
                          }}
                        >
                          Edit
                        </Button>
                      )}
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
              {vendors.data.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5}>
                    <Typography color="text.secondary">
                      No suppliers on the register yet.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </Paper>
      )}

      <VendorFormDialog
        open={formOpen}
        vendor={editing}
        onClose={() => setFormOpen(false)}
      />
    </Stack>
  );
}

/** The person and the number, or whichever of the two is on file. */
function contactLine(vendor: Vendor): string {
  return [vendor.contactPerson, vendor.mobile].filter(Boolean).join(' · ') || '—';
}
