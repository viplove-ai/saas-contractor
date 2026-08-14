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
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { NitImportPanel } from './NitImportPanel';
import {
  useCreateProject,
  useCreateProjectFromNit,
  useCreateSite,
  useDiscardNitUpload,
  useUpdateProject,
  useUsers,
} from './api';
import { projectSchema, type ProjectForm } from './schema';
import type { AdminProject, NitPreview, ProjectStatus } from './types';

interface Props {
  open: boolean;
  /** Null adds a project; a project edits that one. */
  project: AdminProject | null;
  onClose: () => void;
}

/** How the user chose to start. Editing is always 'manual'. */
type Mode = 'manual' | 'nit';

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
  quotedPercent: '',
  budgetAmount: '',
  startDate: '',
  expectedCompletionDate: '',
  actualCompletionDate: '',
  projectManagerId: '',
  status: 'ACTIVE',
  description: '',
};

/**
 * Carries a site failure that happened *after* the project was written, so the dialog can
 * say which of the two succeeded instead of reporting a bare error over a saved project.
 */
class SiteFailedError extends Error {}

/** Same defaults SiteFormDialog opens on, so the two routes to a site agree. */
const DEFAULT_SHIFT_HOURS = 8;
const DEFAULT_WAGE_DAYS = 26;

/** Empty stays empty: an amount left blank is unknown, not zero. */
function toAmount(value: string | undefined): number | undefined {
  return value ? Number(value) : undefined;
}

/**
 * Keeps every label floating above its box instead of sitting in it.
 *
 * <p>The fields here are uncontrolled — {@code register} hands the input straight to
 * react-hook-form — and MUI works out whether a label should float by reading the input's
 * value <em>once, at mount</em>. An NIT import fills the form afterwards, through
 * {@code reset}, so MUI never finds out the boxes are no longer empty and every label stays
 * lying across the value read out of the PDF.</p>
 *
 * <p>Pinning it open is the fix rather than deriving it from the value: a label that floats
 * only when filled has to float on focus too, and driving that by hand from an uncontrolled
 * field puts the label back over the caret the moment somebody clicks an empty box. The two
 * date fields have always been pinned for the same underlying reason, so this makes the
 * dialog consistent rather than half one way.</p>
 */
const FLOATING_LABEL = { shrink: true } as const;

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
  const createFromNit = useCreateProjectFromNit();
  const discardUpload = useDiscardNitUpload();
  const [serverError, setServerError] = useState<string | null>(null);
  /**
   * Null until the user chooses how to start. Editing never asks: the project already
   * exists, and its tender was either imported once or never.
   */
  const [mode, setMode] = useState<Mode | null>(null);
  const [preview, setPreview] = useState<NitPreview | null>(null);
  /**
   * Off by default, and off means nothing happens: a project with no site yet is a normal
   * state, and the Projects list already calls it out as the next thing to do. This only
   * saves the trip to Sites for the common case where the first site is known at the time
   * the contract is entered.
   */
  const [alsoSite, setAlsoSite] = useState(false);
  const [siteCode, setSiteCode] = useState('');
  const [siteName, setSiteName] = useState('');
  /**
   * Set once the project write has gone through. Two writes cannot be one transaction from
   * here, so if the site fails the project still exists — retrying must add the site to the
   * project that was made, not make a second one.
   */
  const [createdProjectId, setCreatedProjectId] = useState<string | null>(null);
  const createSite = useCreateSite();

  const {
    control,
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<ProjectForm>({ resolver: zodResolver(projectSchema), defaultValues: EMPTY });

  // Only to seed the site fields when the box is ticked; the project code is the natural stem.
  const watchedCode = watch('code');
  const watchedName = watch('name');
  /** Ticking the box makes these two required — the server would refuse them empty anyway. */
  const siteIncomplete = alsoSite && (!siteCode.trim() || !siteName.trim());

  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    setMode(editing ? 'manual' : null);
    setPreview(null);
    setAlsoSite(false);
    setSiteCode('');
    setSiteName('');
    setCreatedProjectId(null);
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
            quotedPercent: project.quotedPercent?.toString() ?? '',
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
  }, [open, project, editing, reset]);

  /** Fills the form from a parsed notice, leaving every field editable. */
  function applyPreview(parsed: NitPreview) {
    setPreview(parsed);
    setServerError(null);
    reset({
      ...EMPTY,
      code: parsed.suggestedCode ?? '',
      name: parsed.suggestedName ?? '',
      clientDepartment: parsed.fields.division ? `CPWD ${parsed.fields.division}` : '',
      nitNumber: parsed.nitNumber ?? '',
      tenderReference: parsed.tenderReference ?? '',
      contractValue: parsed.contractValue?.toString() ?? '',
      description: parsed.fields.location ? `Location: ${parsed.fields.location}` : '',
    });
  }

  /** Backing out of an import throws away the upload it left behind. */
  function close() {
    if (preview) {
      discardUpload.mutate(preview.attachmentId);
    }
    onClose();
  }

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
      quotedPercent: toAmount(values.quotedPercent),
      budgetAmount: toAmount(values.budgetAmount),
      startDate: values.startDate || undefined,
      expectedCompletionDate: values.expectedCompletionDate || undefined,
      projectManagerId: values.projectManagerId || undefined,
      description: values.description || undefined,
    };
    try {
      // The project is already saved and only the site failed last time; do not send it again.
      if (createdProjectId) {
        await createSite.mutateAsync({
          projectId: createdProjectId,
          code: siteCode.trim(),
          name: siteName.trim(),
          // Nobody named yet: this is the first site of a brand-new project, and who runs
          // it is a decision for the Sites screen once there is somebody to name.
          siteEngineerIds: [],
          supervisorIds: [],
          status: 'ACTIVE',
          standardShiftHours: DEFAULT_SHIFT_HOURS,
          monthlyWageDays: DEFAULT_WAGE_DAYS,
          usesOutsourcedLabour: false,
        });
        onClose();
        return;
      }
      if (project) {
        await updateProject.mutateAsync({
          ...payload,
          actualCompletionDate: values.actualCompletionDate || undefined,
          status: values.status,
          id: project.id,
          version: project.version,
        });
      } else if (preview) {
        // The lines sent are the ones on screen, edits included. The server derives each
        // amount from the quantity and rate, so no total travels with them.
        const imported = await createFromNit.mutateAsync({
          attachmentId: preview.attachmentId,
          pageCount: preview.pageCount,
          project: payload,
          fields: preview.fields,
          // Echoed back untouched. Nothing on screen edits the milestone table, but without
          // this the whole of Schedule F is dropped on the way to the server.
          scheduleTerms: preview.scheduleTerms,
          warnings: preview.warnings,
          boqLines: preview.boqLines.map((line) => ({
            itemNumber: line.itemNumber,
            description: line.description,
            unitCode: line.unitCode,
            quantity: line.quantity ?? 0,
            rate: line.rate ?? 0,
            workPart: line.workPart,
            category: line.category,
            synthetic: line.synthetic,
          })),
        });
        // Saved, so the upload now belongs to the tender record and must not be discarded.
        setPreview(null);
        await addFirstSite(imported.project.id);
      } else {
        const created = await createProject.mutateAsync(payload);
        await addFirstSite(created.id);
      }
      onClose();
    } catch (error) {
      setServerError(
        error instanceof SiteFailedError
          ? `The project was saved. Its site was not: ${error.message} Correct the site and save again, or close — the site can be added from Sites later.`
          : apiErrorDetail(error),
      );
    }
  });

  /**
   * The second write, when the box is ticked. Its failure is reported against the project
   * that already exists rather than rolled back — there is no transaction spanning the two,
   * and silently discarding a saved contract to undo a site would be the worse trade.
   */
  async function addFirstSite(projectId: string) {
    if (!alsoSite) {
      return;
    }
    setCreatedProjectId(projectId);
    try {
      await createSite.mutateAsync({
        projectId,
        code: siteCode.trim(),
        name: siteName.trim(),
        siteEngineerIds: [],
        supervisorIds: [],
        status: 'ACTIVE',
        standardShiftHours: DEFAULT_SHIFT_HOURS,
        monthlyWageDays: DEFAULT_WAGE_DAYS,
        usesOutsourcedLabour: false,
      });
    } catch (error) {
      throw new SiteFailedError(apiErrorDetail(error));
    }
  }

  // Choosing how to start. Two buttons rather than a wizard step: the decision is one
  // question, and a project typed by hand should not cost an extra screen.
  if (mode === null) {
    return (
      <Dialog open={open} onClose={close} fullWidth maxWidth="sm">
        <DialogTitle>Add a project</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              If you have the tender notice, start from it — the project details and the whole
              schedule of quantities are read from the PDF for you to check.
            </Typography>
            <Button variant="contained" color="secondary" onClick={() => setMode('nit')}>
              Import from NIT PDF
            </Button>
            <Button variant="outlined" onClick={() => setMode('manual')}>
              Enter manually
            </Button>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={close}>Cancel</Button>
        </DialogActions>
      </Dialog>
    );
  }

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth={mode === 'nit' ? 'lg' : 'sm'}>
      <DialogTitle>
        {editing ? `Edit ${project.code}` : mode === 'nit' ? 'Import a project from its NIT' : 'Add a project'}
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          {mode === 'nit' && (
            <NitImportPanel
              preview={preview}
              onParsed={applyPreview}
              onLinesChange={(lines) =>
                setPreview((current) => (current ? { ...current, boqLines: lines } : current))
              }
              disabled={isSubmitting}
            />
          )}

          <TextField
            label="Project code"
            // Fixed at creation: every site, document number and report already carries it.
            disabled={editing}
            InputLabelProps={FLOATING_LABEL}
            error={!!errors.code}
            helperText={errors.code?.message ?? 'Short and unique, e.g. KSN01.'}
            {...register('code')}
          />
          <TextField
            label="Project name"
            InputLabelProps={FLOATING_LABEL}
            error={!!errors.name}
            helperText={errors.name?.message}
            {...register('name')}
          />
          <TextField
            label="Client department (optional)"
            InputLabelProps={FLOATING_LABEL}
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
              InputLabelProps={FLOATING_LABEL}
              error={!!errors.nitNumber}
              helperText={errors.nitNumber?.message}
              {...register('nitNumber')}
            />
            <TextField
              label="Agreement no. (optional)"
              InputLabelProps={FLOATING_LABEL}
              error={!!errors.agreementNo}
              helperText={errors.agreementNo?.message}
              {...register('agreementNo')}
            />
          </Stack>
          <TextField
            label="Tender reference (optional)"
            InputLabelProps={FLOATING_LABEL}
            error={!!errors.tenderReference}
            helperText={errors.tenderReference?.message}
            {...register('tenderReference')}
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Contract value (optional)"
              InputLabelProps={FLOATING_LABEL}
              error={!!errors.contractValue}
              helperText={errors.contractValue?.message ?? 'What the client pays, in rupees.'}
              {...register('contractValue')}
            />
            <TextField
              label="Quoted % (optional)"
              InputLabelProps={FLOATING_LABEL}
              error={!!errors.quotedPercent}
              helperText={
                errors.quotedPercent?.message ??
                'Above (+) or below (−) the estimate. The planner reads it.'
              }
              {...register('quotedPercent')}
            />
            <TextField
              label="Budget (optional)"
              InputLabelProps={FLOATING_LABEL}
              error={!!errors.budgetAmount}
              helperText={errors.budgetAmount?.message ?? 'What you plan to spend.'}
              {...register('budgetAmount')}
            />
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Start date (optional)"
              type="date"
              InputLabelProps={FLOATING_LABEL}
              error={!!errors.startDate}
              helperText={errors.startDate?.message}
              {...register('startDate')}
            />
            <TextField
              label="Expected completion (optional)"
              type="date"
              InputLabelProps={FLOATING_LABEL}
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
                InputLabelProps={FLOATING_LABEL}
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
            InputLabelProps={FLOATING_LABEL}
            error={!!errors.description}
            helperText={errors.description?.message ?? 'What the work covers, in a line or two.'}
            {...register('description')}
          />

          {/*
            Offered on the way past, not imposed. A project with no site records nothing, so
            the first site usually follows within the minute — but the site details are not
            always known when the contract is entered, and a required site here would be
            answered with a placeholder that outlives the guess.
          */}
          {!editing && (
            <>
              <FormControlLabel
                control={
                  <Checkbox
                    checked={alsoSite}
                    onChange={(event) => {
                      const on = event.target.checked;
                      setAlsoSite(on);
                      // Prefill on the way in, so the common case is one tick and no typing.
                      if (on) {
                        setSiteCode((current) => current || `${watchedCode || 'SITE'}-01`);
                        setSiteName((current) => current || watchedName || '');
                      }
                    }}
                  />
                }
                label="Also set up its first site"
              />
              {alsoSite && (
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <TextField
                    label="Site code"
                    value={siteCode}
                    onChange={(event) => setSiteCode(event.target.value)}
                    InputLabelProps={FLOATING_LABEL}
                    helperText="Unique, e.g. KSN-A."
                  />
                  <TextField
                    label="Site name"
                    value={siteName}
                    onChange={(event) => setSiteName(event.target.value)}
                    InputLabelProps={FLOATING_LABEL}
                    helperText="Where the work actually happens."
                  />
                </Stack>
              )}
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={close}>Cancel</Button>
        <Button
          variant="contained"
          color="secondary"
          onClick={submit}
          // Nothing to save from an import until a notice has actually been read.
          disabled={isSubmitting || (mode === 'nit' && !preview) || siteIncomplete}
        >
          {createdProjectId
            ? 'Add the site'
            : editing
              ? 'Save changes'
              : mode === 'nit'
                ? `Create project with ${preview?.boqLines.length ?? 0} BOQ lines`
                : alsoSite
                  ? 'Add project and site'
                  : 'Add project'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
