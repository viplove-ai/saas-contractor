import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { useAuth } from '../auth/AuthContext';
import { useSiteDirectory, useSkillCategories, useUpdateWorker } from './api';
import { editWorkerSchema, type EditWorkerForm } from './schema';
import { EMPLOYMENT_LABEL, WAGE_TYPE_LABEL, type Worker } from './types';

interface Props {
  worker: Worker | null;
  onClose: () => void;
  /** Opens the rate revision for this man. Offered only to somebody who may set pay. */
  onRevise?: ((worker: Worker) => void) | undefined;
  /** Opens the transfer for this man. */
  onTransfer?: ((worker: Worker) => void) | undefined;
  /** Opens the share — lending him to a second site while he stays on the first. */
  onShare?: ((worker: Worker) => void) | undefined;
}

/**
 * Correcting a man's particulars — the other half of taking him on.
 *
 * <p>The gate form asks for four things and defaults the rest, on the reasoning that nothing
 * else is answered correctly by somebody standing at a gate. This is where the rest is
 * answered: what he is engaged as, when he joined, and whether he is still here. The two
 * fields both forms share carry the same rules, because a correction that accepted a blank
 * name would be a way round the check at the gate.</p>
 *
 * <p><b>Pay is deliberately not a field here.</b> A rate is revised and never edited — the
 * old one is closed the day before the new one opens, so that a month already settled cannot
 * be repriced — and putting it in a form whose whole nature is to overwrite would undo that.
 * What the form carries instead is the rate in force and a button to the revision, so that
 * "what is he on, and change it" is one door rather than two on a row that was running out
 * of the card. The posting is reached the same way and for the same reason: a transfer is a
 * new allocation from a date, not an edit of the old one. The wage <em>basis</em> is a field,
 * but only for someone who may set pay: per day or per month decides what the number beside
 * it means, so changing it is a pay decision even though it is not a number.</p>
 *
 * <p>Marked Inactive takes him off the roll and is not deletion: his months keep their wages
 * and still count towards what the site cost. It is also not permanent — a man stood down
 * between pours and a man who has gone for good are the same act here, undone by putting the
 * field back to Active — which is why the last day beside it is asked for and not required.
 * For a man who really has gone it is the date his final settlement is reckoned against; for
 * a man back on Monday there is no such date to give.</p>
 */
export function EditWorkerDialog({ worker, onClose, onRevise, onTransfer, onShare }: Props) {
  const { hasPermission } = useAuth();
  const skills = useSkillCategories();
  const directory = useSiteDirectory();
  const update = useUpdateWorker();
  const [serverError, setServerError] = useState<string | null>(null);
  const canSetPay = hasPermission('wage:write');
  // Every site he stands on, as codes: a shared man has two, and both are where he is.
  const postedAt = (worker?.currentSiteIds ?? [])
    .map((id) => directory.data?.find((site) => site.id === id))
    .map((site) => (site ? `${site.code} — ${site.name}` : '—'));

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<EditWorkerForm>({
    resolver: zodResolver(editWorkerSchema),
    defaultValues: {
      fullName: '',
      mobile: '',
      skillCategoryId: '',
      employmentType: 'CONTRACT',
      wageType: 'DAILY',
      joiningDate: '',
      active: 'active',
      exitDate: '',
    },
  });

  // The last day only exists for a man who has gone, so the field follows the answer above it.
  const status = useWatch({ control, name: 'active' });

  useEffect(() => {
    if (!worker) {
      return;
    }
    setServerError(null);
    reset({
      fullName: worker.fullName,
      mobile: worker.mobile ?? '',
      skillCategoryId: worker.skillCategoryId ?? '',
      employmentType: worker.employmentType,
      wageType: worker.wageType,
      joiningDate: worker.joiningDate ?? '',
      active: worker.active ? 'active' : 'inactive',
      exitDate: worker.exitDate ?? '',
    });
  }, [worker, reset]);

  const submit = handleSubmit(async (values) => {
    if (!worker) {
      return;
    }
    setServerError(null);
    const working = values.active === 'active';
    try {
      await update.mutateAsync({
        id: worker.id,
        fullName: values.fullName,
        mobile: values.mobile,
        skillCategoryId: values.skillCategoryId || undefined,
        employmentType: values.employmentType,
        // Not on the form, and sent back as it stands: the update replaces the row, so
        // leaving these out would clear a man's bank details for changing his trade.
        labourSupplierId: worker.labourSupplierId,
        wageType: values.wageType,
        joiningDate: values.joiningDate || undefined,
        // A man put back to Active keeps no last day, or he is on the roll and gone at once.
        exitDate: working ? undefined : values.exitDate || undefined,
        aadhaarLast4: worker.aadhaarLast4,
        bankAccountNo: worker.bankAccountNo,
        bankIfsc: worker.bankIfsc,
        bankName: worker.bankName,
        active: working,
        version: worker.version,
      });
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={worker !== null} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Edit {worker?.workerCode}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          {/*
            What he is paid and where he stands, each with the act that changes it. Neither is
            saved by this form — both are revisions with a date, opened from here and not
            typed over.
          */}
          {worker && (onRevise || onTransfer) && (
            <Paper variant="outlined" sx={{ p: 1.5 }}>
              <Stack spacing={1.5}>
                {onRevise && (
                  <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1}>
                    <Stack spacing={0}>
                      <Typography variant="overline" color="text.secondary">
                        Rate
                      </Typography>
                      <Typography variant="body2">
                        {worker.currentWageRate
                          ? `${formatAmount(worker.currentWageRate.normalRate)} · ${WAGE_TYPE_LABEL[worker.wageType].toLowerCase()}`
                          : 'No rate yet — his days carry no amount'}
                      </Typography>
                    </Stack>
                    <Button size="small" onClick={() => onRevise(worker)}>
                      {worker.currentWageRate ? 'Revise rate' : 'Set rate'}
                    </Button>
                  </Stack>
                )}
                {onRevise && onTransfer && <Divider />}
                {onTransfer && (
                  <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1}>
                    <Stack spacing={0}>
                      <Typography variant="overline" color="text.secondary">
                        Posted at
                      </Typography>
                      {postedAt.length === 0 ? (
                        <Typography variant="body2">Not posted anywhere</Typography>
                      ) : (
                        postedAt.map((site) => (
                          <Typography key={site} variant="body2">
                            {site}
                          </Typography>
                        ))
                      )}
                      {postedAt.length > 1 && (
                        <Typography variant="caption" color="text.secondary">
                          shared — a half day at each
                        </Typography>
                      )}
                    </Stack>
                    <Stack direction="row" spacing={0.5}>
                      {onShare && (
                        <Button
                          size="small"
                          disabled={postedAt.length === 0}
                          onClick={() => onShare(worker)}
                        >
                          Share
                        </Button>
                      )}
                      <Button
                        size="small"
                        disabled={!worker.currentSiteId}
                        onClick={() => onTransfer(worker)}
                      >
                        Transfer
                      </Button>
                    </Stack>
                  </Stack>
                )}
              </Stack>
            </Paper>
          )}

          <TextField
            label="Full name"
            autoFocus
            error={!!errors.fullName}
            helperText={errors.fullName?.message}
            {...register('fullName')}
          />
          <TextField
            label="Mobile"
            inputMode="tel"
            error={!!errors.mobile}
            helperText={errors.mobile?.message ?? 'How the site reaches him.'}
            {...register('mobile')}
          />

          <Controller
            control={control}
            name="skillCategoryId"
            render={({ field }) => (
              <TextField {...field} select label="Trade">
                <MenuItem value="">Not set</MenuItem>
                {(skills.data ?? [])
                  .filter((skill) => skill.active || skill.id === worker?.skillCategoryId)
                  .map((skill) => (
                    <MenuItem key={skill.id} value={skill.id}>
                      {skill.name}
                    </MenuItem>
                  ))}
              </TextField>
            )}
          />

          <Controller
            control={control}
            name="employmentType"
            render={({ field }) => (
              <TextField {...field} select label="Engaged as">
                {(['PERMANENT', 'CONTRACT', 'CASUAL'] as const).map((type) => (
                  <MenuItem key={type} value={type}>
                    {EMPLOYMENT_LABEL[type]}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />

          {canSetPay && (
            <Controller
              control={control}
              name="wageType"
              render={({ field }) => (
                <TextField
                  {...field}
                  select
                  label="Paid by"
                  helperText="What his rate is a rate of. Changing it does not change the rate."
                >
                  {(['DAILY', 'HOURLY', 'MONTHLY'] as const).map((type) => (
                    <MenuItem key={type} value={type}>
                      {WAGE_TYPE_LABEL[type]}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          )}

          <TextField
            label="Joining date"
            type="date"
            InputLabelProps={{ shrink: true }}
            {...register('joiningDate')}
          />

          <Controller
            control={control}
            name="active"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Status"
                helperText="Inactive takes him off the attendance list and keeps every day he worked. Put him back to Active whenever he returns."
              >
                <MenuItem value="active">Active</MenuItem>
                <MenuItem value="inactive">Inactive</MenuItem>
              </TextField>
            )}
          />

          {status === 'inactive' && (
            <TextField
              label="Last day (optional)"
              type="date"
              InputLabelProps={{ shrink: true }}
              error={!!errors.exitDate}
              helperText={
                errors.exitDate?.message ??
                'Only if he has gone for good — leave it empty for a man you expect back.'
              }
              {...register('exitDate')}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          Save changes
        </Button>
      </DialogActions>
    </Dialog>
  );
}
