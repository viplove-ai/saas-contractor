import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
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
 * Adding a site and posting its engineers and supervisors to it.
 *
 * <p>Naming them here is what grants them the site: the server opens a site assignment for
 * whoever is named and closes it for whoever is dropped, which is the row the access guard
 * reads on every request. That is why the pickers list only members who hold the matching
 * role — posting an accountant as supervisor would be refused server-side, and a picker that
 * offered it would only teach the admin to expect it to work.</p>
 *
 * <p>Both pickers take as many names as the site needs. A block worked in two shifts has two
 * supervisors and a job with separate civil and electrical work has two engineers; while
 * each picker held one name, the second man was not merely unlisted on the register, he was
 * shut out of the site.</p>
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
      siteEngineerIds: [],
      supervisorIds: [],
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
            siteEngineerIds: site.siteEngineerIds,
            supervisorIds: site.supervisorIds,
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
            siteEngineerIds: [],
            supervisorIds: [],
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

  const engineerOptions = staffOptions(engineers.data?.content);
  const supervisorOptions = staffOptions(supervisors.data?.content);

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
            name="siteEngineerIds"
            render={({ field }) => (
              <StaffPicker
                label="Site engineers"
                value={field.value}
                onChange={field.onChange}
                options={engineerOptions}
                emptyHint="No engineer named yet. The site works without one; nobody holding
                  only the engineer role will see it until you name them."
                helperText="Whoever is named gets access to this site as soon as you save."
              />
            )}
          />
          <Controller
            control={control}
            name="supervisorIds"
            render={({ field }) => (
              <StaffPicker
                label="Site supervisors"
                value={field.value}
                onChange={field.onChange}
                options={supervisorOptions}
                emptyHint="No supervisor named yet. Nobody can mark attendance here until
                  there is one."
                helperText="Name as many as the site has — a block worked in two shifts has
                  two. Taking someone off here takes their access to this site away."
              />
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

interface StaffOption {
  id: string;
  label: string;
}

/** The active members holding a role, as options. A deactivated one would be refused. */
function staffOptions(
  data: { id: string; fullName: string; username: string; active: boolean }[] | undefined,
): StaffOption[] {
  return (data ?? [])
    .filter((member) => member.active)
    .map((member) => ({ id: member.id, label: `${member.fullName} (${member.username})` }));
}

/**
 * However many names the post has, as a tick per member rather than a multi-select.
 *
 * <p>A dropdown with checkboxes hides the answer behind a tap, and the question this screen
 * is really asking — "is everybody who runs this site on the list" — is one you answer by
 * looking. A contractor's staff is a dozen people, so the list is short enough to show.</p>
 */
function StaffPicker({
  label,
  value,
  onChange,
  options,
  helperText,
  emptyHint,
}: {
  label: string;
  value: string[];
  onChange: (next: string[]) => void;
  options: StaffOption[];
  helperText: string;
  emptyHint: string;
}) {
  const toggle = (id: string) =>
    onChange(value.includes(id) ? value.filter((held) => held !== id) : [...value, id]);

  return (
    <Stack spacing={0.5}>
      <Typography variant="subtitle2">{label}</Typography>
      {options.length === 0 ? (
        <FormHelperText>
          Nobody holds this role yet. Onboard them on the Members screen first.
        </FormHelperText>
      ) : (
        // Named as a group: two of these sit on the dialog, and a tick carrying nothing but
        // a person's name does not say which of the two posts it is granting.
        <FormGroup role="group" aria-label={label}>
          {options.map((option) => (
            <FormControlLabel
              key={option.id}
              control={
                <Checkbox checked={value.includes(option.id)} onChange={() => toggle(option.id)} />
              }
              label={option.label}
            />
          ))}
        </FormGroup>
      )}
      <FormHelperText>{value.length === 0 ? emptyHint : helperText}</FormHelperText>
    </Stack>
  );
}
