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
import { useAuth } from '../auth/AuthContext';
import {
  useCorrectFieldVendor,
  useCreateVendor,
  useNameVendor,
  useUpdateVendor,
} from './api';
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
 *
 * <p><b>The field gets a shorter version of the same form.</b> Somebody holding only
 * {@code masterdata:provisional:supplier} — the supervisor watching the lorry unload — sees
 * the firm, what it supplies and how to reach it, and none of the tax, bank and terms
 * section: that is the office's, and it is the whole of the difference between naming a
 * thing and valuing it. He gets no active switch either, and what he onboards arrives
 * active, because a supplier being named is a supplier being used. His save goes to the
 * field endpoints, which cannot reach those fields at all — the form hiding them is the
 * courtesy; the server refusing them is the rule.</p>
 */
export function VendorFormDialog({ open, vendor, onClose }: Props) {
  const editing = vendor !== null;
  const { hasPermission } = useAuth();
  const office = hasPermission('vendor:write');
  const createVendor = useCreateVendor();
  const updateVendor = useUpdateVendor();
  const nameVendor = useNameVendor();
  const correctVendor = useCorrectFieldVendor();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<VendorForm>({
    resolver: zodResolver(vendorSchema),
    defaultValues: emptyVendor(),
  });

  const supplies = watch('vendorType');

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
            suppliesNote: vendor.suppliesNote ?? '',
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
    // What the field may say about him, and the only thing sent when the field is saying it.
    const field = {
      name: values.name,
      vendorType: values.vendorType,
      suppliesNote: values.suppliesNote,
      contactPerson: values.contactPerson,
      mobile: values.mobile,
      email: values.email,
      address: values.address,
    };
    try {
      if (vendor) {
        await (office
          ? updateVendor.mutateAsync({ ...values, id: vendor.id, version: vendor.version })
          : correctVendor.mutateAsync({ ...field, id: vendor.id, version: vendor.version }));
      } else {
        await (office ? createVendor.mutateAsync(values) : nameVendor.mutateAsync(field));
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
          {/*
            The sentence beside "Something else". The five kinds will never fit the
            scaffolding hire, the surveyor and the man with the water tanker — and a register
            row reading "Other" tells the next reader only what he already knew, which is that
            none of the four fitted. So the box appears with that answer and is required with
            it. It is not shown otherwise, because a note sitting behind a kind that no longer
            displays it is a second answer nobody can see; the server drops it on the way past.
          */}
          {supplies === 'OTHER' && (
            <TextField
              label="What does he supply?"
              placeholder="Scaffolding on hire, survey work, water tankers…"
              error={!!errors.suppliesNote}
              helperText={
                errors.suppliesNote?.message ??
                'In your own words. This is what the register will call him.'
              }
              inputProps={{ maxLength: 120 }}
              {...register('suppliesNote')}
            />
          )}

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

          {/*
            Tax, bank and terms — the office's half, and shown to the office alone. A GSTIN
            typed at a gate is one that money is later paid against, and a credit period
            guessed there is one a supplier's ledger is later argued from.
          */}
          {office && (
            <>
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
            </>
          )}

          {!office && (
            <Typography variant="body2" color="text.secondary">
              His tax numbers, bank details and credit terms are the office's to fill in. He
              can be named on deliveries and on a day's labour as soon as this is saved.
            </Typography>
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
    suppliesNote: '',
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
