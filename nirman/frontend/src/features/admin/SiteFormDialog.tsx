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
import { useCreateSite, useProjects, useUpdateSite, useUsers } from './api';
import { siteSchema, type SiteForm } from './schema';
import type { AdminSite, SiteStatus } from './types';

interface Props {
  open: boolean;
  /** Null adds a site; a site edits that one. */
  site: AdminSite | null;
  onClose: () => void;
}

const STATUSES: { value: SiteStatus; label: string }[] = [
  { value: 'PLANNED', label: 'Planned' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'SUSPENDED', label: 'Suspended' },
  { value: 'CLOSED', label: 'Closed' },
];

const DEFAULT_SHIFT_HOURS = 8;
const DEFAULT_WAGE_DAYS = 26;

/**
 * Adding a site and posting its engineer and supervisor to it.
 *
 * <p>Naming the two here is what grants them the site: the server opens a site assignment
 * for whoever is named and closes it for whoever is dropped, which is the row the access
 * guard reads on every request. That is why the pickers list only members who hold the
 * matching role — posting an accountant as supervisor would be refused server-side, and a
 * picker that offered it would only teach the admin to expect it to work.</p>
 */
export function SiteFormDialog({ open, site, onClose }: Props) {
  const editing = site !== null;
  const projects = useProjects();
  const engineers = useUsers('', 'ENGINEER');
  const supervisors = useUsers('', 'SUPERVISOR');
  const createSite = useCreateSite();
  const updateSite = useUpdateSite();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<SiteForm>({
    resolver: zodResolver(siteSchema),
    defaultValues: {
      projectId: '',
      code: '',
      name: '',
      address: '',
      siteEngineerId: '',
      supervisorId: '',
      startDate: '',
      status: 'ACTIVE',
      standardShiftHours: DEFAULT_SHIFT_HOURS,
      monthlyWageDays: DEFAULT_WAGE_DAYS,
      usesOutsourcedLabour: false,
    },
  });

  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    reset(
      site
        ? {
            projectId: site.projectId,
            code: site.code,
            name: site.name,
            address: site.address ?? '',
            siteEngineerId: site.siteEngineerId ?? '',
            supervisorId: site.supervisorId ?? '',
            startDate: site.startDate ?? '',
            status: site.status,
            standardShiftHours: site.standardShiftHours,
            monthlyWageDays: site.monthlyWageDays,
            usesOutsourcedLabour: site.usesOutsourcedLabour,
          }
        : {
            projectId: '',
            code: '',
            name: '',
            address: '',
            siteEngineerId: '',
            supervisorId: '',
            startDate: '',
            status: 'ACTIVE',
            standardShiftHours: DEFAULT_SHIFT_HOURS,
            monthlyWageDays: DEFAULT_WAGE_DAYS,
            usesOutsourcedLabour: false,
          },
    );
  }, [open, site, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    try {
      if (site) {
        await updateSite.mutateAsync({ ...values, id: site.id, version: site.version });
      } else {
        await createSite.mutateAsync(values);
      }
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  const staffOptions = (
    data: { id: string; fullName: string; username: string; active: boolean }[] | undefined,
  ) => [
    <MenuItem key="none" value="">
      Nobody yet
    </MenuItem>,
    ...(data ?? [])
      .filter((member) => member.active)
      .map((member) => (
        <MenuItem key={member.id} value={member.id}>
          {member.fullName} ({member.username})
        </MenuItem>
      )),
  ];

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{editing ? `Edit ${site.code}` : 'Add a site'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <Controller
            control={control}
            name="projectId"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Project"
                // A site cannot move between projects: its whole transaction history hangs
                // off the pair, so a move would rewrite months of records.
                disabled={editing}
                error={!!errors.projectId}
                helperText={errors.projectId?.message}
              >
                {(projects.data ?? []).map((project) => (
                  <MenuItem key={project.id} value={project.id}>
                    {project.code} — {project.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />

          <TextField
            label="Site code"
            disabled={editing}
            error={!!errors.code}
            helperText={errors.code?.message ?? 'Short and unique, e.g. KSN-A.'}
            {...register('code')}
          />
          <TextField
            label="Site name"
            error={!!errors.name}
            helperText={errors.name?.message}
            {...register('name')}
          />
          <TextField
            label="Address (optional)"
            multiline
            minRows={2}
            error={!!errors.address}
            helperText={errors.address?.message}
            {...register('address')}
          />

          <Controller
            control={control}
            name="siteEngineerId"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Site engineer"
                error={!!errors.siteEngineerId}
                helperText={
                  errors.siteEngineerId?.message ?? 'Gets access to this site as soon as you save.'
                }
              >
                {staffOptions(engineers.data?.content)}
              </TextField>
            )}
          />
          <Controller
            control={control}
            name="supervisorId"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Site supervisor"
                error={!!errors.supervisorId}
                helperText={
                  errors.supervisorId?.message ??
                  'Removing someone here takes their access to this site away.'
                }
              >
                {staffOptions(supervisors.data?.content)}
              </TextField>
            )}
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Standard shift"
              type="number"
              inputProps={{ step: 0.5, min: 0.5, max: 24 }}
              error={!!errors.standardShiftHours}
              helperText={errors.standardShiftHours?.message ?? 'Hours. Overtime is counted past this.'}
              {...register('standardShiftHours')}
            />
            <TextField
              label="Wage days a month"
              type="number"
              inputProps={{ step: 1, min: 1, max: 31 }}
              error={!!errors.monthlyWageDays}
              helperText={errors.monthlyWageDays?.message ?? 'Used to derive a daily rate.'}
              {...register('monthlyWageDays')}
            />
          </Stack>

          {/*
            What this switch decides is which question the site's supervisor is asked every
            evening: "who came in" against a named roster, or "how many came in" per trade.
            Turning it on does not turn the muster roll off — a site can run both, our own
            men and a contractor's gang — it adds the counts box to his day.
          */}
          <Controller
            control={control}
            name="usesOutsourcedLabour"
            render={({ field }) => (
              <FormControlLabel
                control={
                  <Switch checked={field.value} onChange={(_, next) => field.onChange(next)} />
                }
                label="Work here is let to labour contractors"
              />
            )}
          />
          <FormHelperText sx={{ mt: -1.5 }}>
            The supervisor records head counts per trade for the day as well as any muster
            roll. No wages are computed from a count — the contractor bills for the work.
          </FormHelperText>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Start date (optional)"
              type="date"
              InputLabelProps={{ shrink: true }}
              error={!!errors.startDate}
              helperText={errors.startDate?.message}
              {...register('startDate')}
            />
            <Controller
              control={control}
              name="status"
              render={({ field }) => (
                <TextField {...field} select label="Status" disabled={!editing}>
                  {STATUSES.map((option) => (
                    <MenuItem key={option.value} value={option.value}>
                      {option.label}
                    </MenuItem>
                  ))}
                </TextField>
              )}
            />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          {editing ? 'Save changes' : 'Add site'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
