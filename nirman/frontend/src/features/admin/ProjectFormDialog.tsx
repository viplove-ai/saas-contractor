import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { useCreateProject, useUpdateProject, useUsers } from './api';
import { projectSchema, type ProjectForm } from './schema';
import type { AdminProject, ProjectStatus } from './types';

interface Props {
  open: boolean;
  /** Null adds a project; a project edits that one. */
  project: AdminProject | null;
  onClose: () => void;
}

const STATUSES: { value: ProjectStatus; label: string }[] = [
  { value: 'PLANNED', label: 'Planned' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'ON_HOLD', label: 'On hold' },
  { value: 'COMPLETED', label: 'Completed' },
  { value: 'CLOSED', label: 'Closed' },
];

const EMPTY: ProjectForm = {
  code: '',
  name: '',
  clientDepartment: '',
  agreementNo: '',
  nitNumber: '',
  tenderReference: '',
  contractValue: '',
  budgetAmount: '',
  startDate: '',
  expectedCompletionDate: '',
  actualCompletionDate: '',
  projectManagerId: '',
  status: 'ACTIVE',
  description: '',
};

/** Empty stays empty: an amount left blank is unknown, not zero. */
function toAmount(value: string | undefined): number | undefined {
  return value ? Number(value) : undefined;
}

/**
 * The contract a project runs under: its tender and agreement references, its value, its
 * dates and who manages it.
 *
 * <p>Only the code and the name are required. The rest of a government contract's paperwork
 * arrives over weeks — an NIT number before an agreement number, a final value after a
 * revision — and a form that refused to save until all of it was to hand would be filled in
 * with placeholders instead.</p>
 */
export function ProjectFormDialog({ open, project, onClose }: Props) {
  const editing = project !== null;
  const managers = useUsers('', '');
  const createProject = useCreateProject();
  const updateProject = useUpdateProject();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<ProjectForm>({ resolver: zodResolver(projectSchema), defaultValues: EMPTY });

  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    reset(
      project
        ? {
            code: project.code,
            name: project.name,
            clientDepartment: project.clientDepartment ?? '',
            agreementNo: project.agreementNo ?? '',
            nitNumber: project.nitNumber ?? '',
            tenderReference: project.tenderReference ?? '',
            contractValue: project.contractValue?.toString() ?? '',
            budgetAmount: project.budgetAmount?.toString() ?? '',
            startDate: project.startDate ?? '',
            expectedCompletionDate: project.expectedCompletionDate ?? '',
            actualCompletionDate: project.actualCompletionDate ?? '',
            projectManagerId: project.projectManagerId ?? '',
            status: project.status,
            description: project.description ?? '',
          }
        : EMPTY,
    );
  }, [open, project, reset]);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    const payload = {
      code: values.code,
      name: values.name,
      clientDepartment: values.clientDepartment || undefined,
      agreementNo: values.agreementNo || undefined,
      nitNumber: values.nitNumber || undefined,
      tenderReference: values.tenderReference || undefined,
      contractValue: toAmount(values.contractValue),
      budgetAmount: toAmount(values.budgetAmount),
      startDate: values.startDate || undefined,
      expectedCompletionDate: values.expectedCompletionDate || undefined,
      projectManagerId: values.projectManagerId || undefined,
      description: values.description || undefined,
    };
    try {
      if (project) {
        await updateProject.mutateAsync({
          ...payload,
          actualCompletionDate: values.actualCompletionDate || undefined,
          status: values.status,
          id: project.id,
          version: project.version,
        });
      } else {
        await createProject.mutateAsync(payload);
      }
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{editing ? `Edit ${project.code}` : 'Add a project'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <TextField
            label="Project code"
            // Fixed at creation: every site, document number and report already carries it.
            disabled={editing}
            error={!!errors.code}
            helperText={errors.code?.message ?? 'Short and unique, e.g. KSN01.'}
            {...register('code')}
          />
          <TextField
            label="Project name"
            error={!!errors.name}
            helperText={errors.name?.message}
            {...register('name')}
          />
          <TextField
            label="Client department (optional)"
            error={!!errors.clientDepartment}
            helperText={errors.clientDepartment?.message ?? 'Who awarded the work, e.g. CPWD.'}
            {...register('clientDepartment')}
          />

          <Typography variant="subtitle2" color="text.secondary">
            Tender paperwork
          </Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="NIT number (optional)"
              error={!!errors.nitNumber}
              helperText={errors.nitNumber?.message}
              {...register('nitNumber')}
            />
            <TextField
              label="Agreement no. (optional)"
              error={!!errors.agreementNo}
              helperText={errors.agreementNo?.message}
              {...register('agreementNo')}
            />
          </Stack>
          <TextField
            label="Tender reference (optional)"
            error={!!errors.tenderReference}
            helperText={errors.tenderReference?.message}
            {...register('tenderReference')}
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Contract value (optional)"
              error={!!errors.contractValue}
              helperText={errors.contractValue?.message ?? 'What the client pays, in rupees.'}
              {...register('contractValue')}
            />
            <TextField
              label="Budget (optional)"
              error={!!errors.budgetAmount}
              helperText={errors.budgetAmount?.message ?? 'What you plan to spend.'}
              {...register('budgetAmount')}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Start date (optional)"
              type="date"
              InputLabelProps={{ shrink: true }}
              error={!!errors.startDate}
              helperText={errors.startDate?.message}
              {...register('startDate')}
            />
            <TextField
              label="Expected completion (optional)"
              type="date"
              InputLabelProps={{ shrink: true }}
              error={!!errors.expectedCompletionDate}
              helperText={errors.expectedCompletionDate?.message}
              {...register('expectedCompletionDate')}
            />
          </Stack>

          {editing && (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Actual completion (optional)"
                type="date"
                InputLabelProps={{ shrink: true }}
                error={!!errors.actualCompletionDate}
                helperText={errors.actualCompletionDate?.message}
                {...register('actualCompletionDate')}
              />
              <Controller
                control={control}
                name="status"
                render={({ field }) => (
                  <TextField {...field} select label="Status">
                    {STATUSES.map((option) => (
                      <MenuItem key={option.value} value={option.value}>
                        {option.label}
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
            </Stack>
          )}

          <Controller
            control={control}
            name="projectManagerId"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Project manager (optional)"
                helperText="For the record. Site access still comes from the site postings."
              >
                <MenuItem value="">Nobody yet</MenuItem>
                {(managers.data?.content ?? [])
                  .filter((member) => member.active)
                  .map((member) => (
                    <MenuItem key={member.id} value={member.id}>
                      {member.fullName} ({member.username})
                    </MenuItem>
                  ))}
              </TextField>
            )}
          />

          <TextField
            label="Description (optional)"
            multiline
            minRows={2}
            error={!!errors.description}
            helperText={errors.description?.message ?? 'What the work covers, in a line or two.'}
            {...register('description')}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          {editing ? 'Save changes' : 'Add project'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
