import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useCreateVendor, useUpdateVendor } from './api';
import { vendorSchema, type VendorForm } from './schema';
import { VENDOR_TYPE_LABEL, type Vendor, type VendorType } from './types';

interface Props {
  open: boolean;
  /** Null onboards a new supplier; a vendor edits that one. */
  vendor: Vendor | null;
  onClose: () => void;
}

const TYPES = Object.keys(VENDOR_TYPE_LABEL) as VendorType[];

/**
 * Onboarding a dealer, and keeping his details current.
 *
 * <p>Three things are required and the rest is not, which is the whole shape of the screen:
 * a lorry turns up from a supplier nobody has onboarded, and refusing to record him until
 * somebody produces his GSTIN is how a delivery goes unbooked. A code, a name and what kind
 * of supplier he is gets him on the register; the bank details and the tax numbers are the
 * office's to add when the paperwork catches up.</p>
 *
 * <p>The code cannot be changed afterwards. Every delivery and every bill he has ever sent
 * hangs off this row, and a renamed code is a supplier whose history nobody can follow.</p>
 */
export function VendorFormDialog({ open, vendor, onClose }: Props) {
  const editing = vendor !== null;
  const createVendor = useCreateVendor();
  const updateVendor = useUpdateVendor();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<VendorForm>({
    resolver: zodResolver(vendorSchema),
    defaultValues: emptyVendor(),
  });

  // The dialog stays mounted between openings, so each opening reloads its own supplier —
  // otherwise the second one edited would open on the first one's values.
  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    reset(
      vendor
        ? {
            name: vendor.name,
            vendorType: vendor.vendorType,
            contactPerson: vendor.contactPerson ?? '',
            mobile: vendor.mobile ?? '',
            email: vendor.email ?? '',
            address: vendor.address ?? '',
            gstin: vendor.gstin ?? '',
            pan: vendor.pan ?? '',
            bankAccountNo: vendor.bankAccountNo ?? '',
            bankIfsc: vendor.bankIfsc ?? '',
            creditDays: vendor.creditDays,
            active: vendor.active,
          }
        : emptyVendor(),
    );
  }, [open, vendor, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      if (vendor) {
        await updateVendor.mutateAsync({ ...values, id: vendor.id, version: vendor.version });
      } else {
        await createVendor.mutateAsync(values);
      }
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{editing ? `Edit ${vendor.code}` : 'Onboard a supplier'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          {/*
            The code is shown, never asked for. It is permanent — every delivery and bill
            from him hangs off it — and it is the server's to mint, from what he supplies and
            what he is called.
          */}
          {editing && (
            <Typography variant="body2" color="text.secondary">
              Code <strong>{vendor.code}</strong> · assigned when he was onboarded, and
              permanent: every delivery and bill from him hangs off it.
            </Typography>
          )}
          <TextField
            label="Firm’s name"
            error={!!errors.name}
            helperText={errors.name?.message}
            {...register('name')}
          />
          <Controller
            control={control}
            name="vendorType"
            render={({ field }) => (
              <TextField {...field} select label="What he supplies">
                {TYPES.map((type) => (
                  <MenuItem key={type} value={type}>
                    {VENDOR_TYPE_LABEL[type]}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />

          <Divider textAlign="left">
            <Typography variant="overline" color="text.secondary">
              How you reach him
            </Typography>
          </Divider>
          <TextField
            label="Contact person (optional)"
            error={!!errors.contactPerson}
            helperText={errors.contactPerson?.message}
            {...register('contactPerson')}
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Mobile (optional)"
              inputMode="tel"
              fullWidth
              error={!!errors.mobile}
              helperText={errors.mobile?.message}
              {...register('mobile')}
            />
            <TextField
              label="Email (optional)"
              type="email"
              fullWidth
              error={!!errors.email}
              helperText={errors.email?.message}
              {...register('email')}
            />
          </Stack>
          <TextField
            label="Address (optional)"
            multiline
            minRows={2}
            error={!!errors.address}
            helperText={errors.address?.message}
            {...register('address')}
          />

          <Divider textAlign="left">
            <Typography variant="overline" color="text.secondary">
              Tax, bank and terms
            </Typography>
          </Divider>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="GSTIN (optional)"
              fullWidth
              inputProps={{ style: { textTransform: 'uppercase' } }}
              error={!!errors.gstin}
              helperText={errors.gstin?.message ?? 'Needed before his tax can be claimed back.'}
              {...register('gstin')}
            />
            <TextField
              label="PAN (optional)"
              fullWidth
              inputProps={{ style: { textTransform: 'uppercase' } }}
              error={!!errors.pan}
              helperText={errors.pan?.message}
              {...register('pan')}
            />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Bank account (optional)"
              fullWidth
              error={!!errors.bankAccountNo}
              helperText={errors.bankAccountNo?.message}
              {...register('bankAccountNo')}
            />
            <TextField
              label="IFSC (optional)"
              fullWidth
              inputProps={{ style: { textTransform: 'uppercase' } }}
              error={!!errors.bankIfsc}
              helperText={errors.bankIfsc?.message}
              {...register('bankIfsc')}
            />
          </Stack>
          <TextField
            label="Credit days"
            type="number"
            inputProps={{ min: 0, max: 365, step: 1 }}
            error={!!errors.creditDays}
            helperText={
              errors.creditDays?.message ?? 'From his bill to when it is due. 0 is cash on delivery.'
            }
            {...register('creditDays')}
          />

          {editing && (
            <Controller
              control={control}
              name="active"
              render={({ field }) => (
                <FormControlLabel
                  control={
                    <Switch checked={field.value} onChange={(_, next) => field.onChange(next)} />
                  }
                  label={
                    field.value
                      ? 'Active — can be named on deliveries and paid'
                      : 'Inactive — no new deliveries or payments; his history stays'
                  }
                />
              )}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          {editing ? 'Save changes' : 'Onboard supplier'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function emptyVendor(): VendorForm {
  return {
    name: '',
    vendorType: 'MATERIAL',
    contactPerson: '',
    mobile: '',
    email: '',
    address: '',
    gstin: '',
    pan: '',
    bankAccountNo: '',
    bankIfsc: '',
    creditDays: 0,
    active: true,
  };
}
