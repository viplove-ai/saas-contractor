import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Step,
  StepLabel,
  Stepper,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatHours, formatQuantity } from '../../shared/formatters';
import { DeleteRecordDialog } from '../../shared/DeleteRecordDialog';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useSelectedSite } from '../../shared/siteSelection';
import { StatusChip } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import {
  useAttachDprPhoto,
  useBoqItems,
  useCreateDpr,
  useDeleteDpr,
  useDpr,
  usePrefill,
  useSites,
  useSubmitDpr,
  useUpdateDpr,
} from './api';
import {
  ExpenseCard,
  MaterialCard,
  OutsourcedLabourCard,
  PhotoCard,
} from './DayEntry';
import { isEditable } from './types';
import type {
  Dpr,
  DprPrefill,
  LabourLine,
  MachineryInput,
  MaterialLine,
  Weather,
  WorkItemInput,
} from './types';

const STEPS = ['The day so far', 'Work done', 'Observations'];

/** The day's men, grouped the way the muster groups them. */
const LABOUR_COLUMNS: RecordColumn<LabourLine>[] = [
  {
    key: 'trade',
    header: 'Trade',
    card: 'title',
    cell: (line) => line.skillCategoryName ?? 'Unspecified',
  },
  {
    // Where the men came from, which is the one thing that must not be lost when the two
    // kinds of row print as one list. "Direct" is our own muster; "External" is a gang
    // counted at the gate, which has a head count and unpriced hours and no wage at all.
    key: 'source',
    header: 'Source',
    cell: (line) =>
      line.labourSupplierName ?? (line.outsourced ? 'External' : 'Direct'),
  },
  { key: 'men', header: 'Men', align: 'right', cell: (line) => line.headCount },
  {
    key: 'regular',
    header: 'Regular',
    align: 'right',
    cell: (line) => formatHours(line.regularHours),
  },
  {
    key: 'overtime',
    header: 'Overtime',
    align: 'right',
    cell: (line) => formatHours(line.overtimeHours),
  },
];

/** Received and consumed side by side, per material. Never added — see the note below. */
const MATERIAL_COLUMNS: RecordColumn<MaterialLine>[] = [
  { key: 'material', header: 'Material', card: 'title', cell: (line) => line.materialName },
  {
    key: 'received',
    header: 'Received',
    align: 'right',
    cell: (line) => formatQuantity(line.receivedQty, line.baseUnitCode),
  },
  {
    key: 'consumed',
    header: 'Consumed',
    align: 'right',
    cell: (line) => formatQuantity(line.consumedQty, line.baseUnitCode),
  },
];

// Exactly the values the server's enum holds. Offering a Fog the server has never heard of
// buys the supervisor a refused save at the end of a day's typing.
const WEATHER: { value: Weather; label: string }[] = [
  { value: 'CLEAR', label: 'Clear' },
  { value: 'CLOUDY', label: 'Cloudy' },
  { value: 'RAIN', label: 'Rain' },
  { value: 'HEAVY_RAIN', label: 'Heavy rain' },
  { value: 'EXTREME_HEAT', label: 'Extreme heat' },
];

/**
 * What the screen is doing about a report that already covers the day.
 *
 * <p>UNDECIDED is not a neutral starting state — it is the question on screen, and the
 * wizard refuses to move off step one until it is answered. Both answers write to the same
 * report: one report per site per day is an invariant, so "start fresh" cannot mean a second
 * row, and means clearing this one out and writing over it.</p>
 */
type DraftChoice = 'UNDECIDED' | 'RESUMED' | 'FRESH';

interface WorkLine extends WorkItemInput {
  key: string;
}

interface MachineryLine extends MachineryInput {
  key: string;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function emptyWorkLine(): WorkLine {
  return { key: crypto.randomUUID(), activity: '', workLocation: '', quantity: undefined };
}

/**
 * The daily progress report, written the way the day actually happened.
 *
 * <p>Step one is the whole argument for this screen. A supervisor at six in the evening has
 * already recorded the muster, the material and the bills; asking him to copy those figures
 * into a diary is asking him to make a transcription error on data the system already holds.
 * So the first step is not a form — it is what the records say, offered for confirmation, and
 * the only thing he adds is what the numbers cannot know: what got built, what went wrong,
 * and what happens tomorrow.</p>
 *
 * <p>Two honesties are carried on the face of it rather than buried. A labour cost standing
 * on unverified attendance is <b>provisional</b>, because the wage is frozen at verification
 * and until then the figure moves; and material received is kept apart from material consumed,
 * because one is inventory and only the other is cost.</p>
 */
export function DprWizardPage() {
  // Opened as /dpr/:id when the supervisor picked a particular draft to come back to.
  const { id: routeDprId } = useParams<{ id: string }>();
  const [reportDate, setReportDate] = useState(today());
  const [step, setStep] = useState(0);

  const [weather, setWeather] = useState<Weather | ''>('');
  const [temperatureC, setTemperatureC] = useState('');
  const [workingHoursLost, setWorkingHoursLost] = useState('');
  const [workSummary, setWorkSummary] = useState('');
  const [delays, setDelays] = useState('');
  const [safetyObservations, setSafety] = useState('');
  const [qualityObservations, setQuality] = useState('');
  const [instructionsReceived, setInstructions] = useState('');
  const [managementAttention, setAttention] = useState('');
  const [nextDayPlan, setNextDayPlan] = useState('');
  const [workLines, setWorkLines] = useState<WorkLine[]>([emptyWorkLine()]);
  const [machinery, setMachinery] = useState<MachineryLine[]>([]);
  const [saved, setSaved] = useState<Dpr | null>(null);
  const [draftChoice, setDraftChoice] = useState<DraftChoice>('UNDECIDED');
  /** Whether this screen has saved anything yet — a resumed draft was saved by an earlier one. */
  const [savedHere, setSavedHere] = useState(false);
  /** The /dpr/:id report has been stood on; the date is the supervisor's again from here. */
  const [routeApplied, setRouteApplied] = useState(false);
  // Held here until there is a report to hang them off. Picked while he is looking at the
  // work; sent with the save.
  const [photos, setPhotos] = useState<File[]>([]);
  const [photoError, setPhotoError] = useState<string | null>(null);

  const { hasPermission } = useAuth();
  const canDelete = hasPermission('dpr:delete');
  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data);
  const prefill = usePrefill(siteId || undefined, reportDate);
  const boqItems = useBoqItems(siteId || undefined);
  const attachPhoto = useAttachDprPhoto();
  const create = useCreateDpr();
  const update = useUpdateDpr();
  const submit = useSubmitDpr();

  /**
   * Whether a report already covers the day on screen.
   *
   * <p>The prefill is the authority — it is keyed on the site and date actually selected. The
   * route id only stands in for the moment before the prefill has answered, so that opening a
   * draft does not flash a blank wizard that invites somebody to start typing a second one.</p>
   */
  const covered = Boolean(prefill.data?.reportExists) || (Boolean(routeDprId) && !routeApplied);
  const existingId =
    saved || !covered ? undefined : (prefill.data?.existingDprId ?? routeDprId);
  const existing = useDpr(existingId);

  /** Everything the supervisor types, back to blank. The saved report is untouched. */
  function clearForm() {
    setWeather('');
    setTemperatureC('');
    setWorkingHoursLost('');
    setWorkSummary('');
    setDelays('');
    setSafety('');
    setQuality('');
    setInstructions('');
    setAttention('');
    setNextDayPlan('');
    setWorkLines([emptyWorkLine()]);
    setMachinery([]);
    setPhotos([]);
    setPhotoError(null);
  }

  /** Reads a saved report back into the form, so continuing it starts where it was left. */
  function loadForm(report: Dpr) {
    setWeather(report.weather ?? '');
    setTemperatureC(report.temperatureC == null ? '' : String(report.temperatureC));
    setWorkingHoursLost(
      report.workingHoursLost == null ? '' : String(report.workingHoursLost),
    );
    setWorkSummary(report.workSummary ?? '');
    setDelays(report.delays ?? '');
    setSafety(report.safetyObservations ?? '');
    setQuality(report.qualityObservations ?? '');
    setInstructions(report.instructionsReceived ?? '');
    setAttention(report.managementAttention ?? '');
    setNextDayPlan(report.nextDayPlan ?? '');
    setWorkLines(
      report.workItems.length === 0
        ? [emptyWorkLine()]
        : report.workItems.map((item) => ({
            key: crypto.randomUUID(),
            boqItemId: item.boqItemId,
            activity: item.activity,
            workLocation: item.workLocation ?? '',
            quantity: item.quantity,
            remarks: item.remarks,
          })),
    );
    setMachinery(
      report.machinery.map((line) => ({
        key: crypto.randomUUID(),
        machineryName: line.machineryName,
        count: line.count,
        hoursUsed: line.hoursUsed,
        idleHours: line.idleHours,
        remarks: line.remarks,
      })),
    );
    setPhotos([]);
    setPhotoError(null);
  }

  /** Pick up where the draft was left. */
  function resumeDraft(report: Dpr) {
    loadForm(report);
    setSaved(report);
    setSavedHere(false);
    setDraftChoice('RESUMED');
  }

  /**
   * Throw away what is in the draft and write the day again.
   *
   * <p>It binds to the same report rather than opening a second one, because one report per
   * site per day is the rule the server enforces and a second row is not a thing that can
   * exist. Nothing is destroyed until he saves — until then the draft still holds what it
   * held, which is the difference between a fresh start and a mistake he cannot undo.</p>
   */
  function startFresh(report: Dpr) {
    clearForm();
    setSaved(report);
    setSavedHere(false);
    setDraftChoice('FRESH');
    setStep(0);
  }

  // A new day is a new report. Anything typed against the old one would otherwise be filed
  // under a date it did not happen on, so the form empties with the date rather than carrying
  // yesterday's narrative into today's.
  useEffect(() => {
    setSaved(null);
    setSavedHere(false);
    setStep(0);
    setDraftChoice('UNDECIDED');
    clearForm();
    // clearForm only ever calls setState, and re-running this on every render is exactly what
    // must not happen: the day is the trigger, nothing else.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [siteId, reportDate]);

  /**
   * Opened on a particular report: stand the screen on that report's day, and let the same
   * chooser everybody else gets do the rest — one code path, whichever door was used.
   *
   * <p>Once only. Otherwise changing the date on a screen opened as /dpr/:id would be undone
   * by this on the next render, and the supervisor would be locked to one day.</p>
   */
  useEffect(() => {
    const report = existing.data;
    if (routeApplied || !report || report.id !== routeDprId) {
      return;
    }
    setSiteId(report.siteId);
    setReportDate(report.reportDate);
    setRouteApplied(true);
    // setSiteId is the shared site chooser and stable, but it is a real dependency now that
    // it is not a plain setState — naming it keeps that visible rather than assumed.
  }, [existing.data, routeDprId, routeApplied, setSiteId]);

  const existingReport = saved || !covered ? null : (existing.data ?? null);
  /** A report covers the day and it is past editing — a dead end, and it says so. */
  const closed = Boolean(existingReport && !isEditable(existingReport.workflowStatus));
  /** The question is on screen and nothing moves until it is answered. */
  const mustChoose = !saved && covered && draftChoice === 'UNDECIDED';

  /** Re-ask the day after an entry card saves, so the figures above it move as he types. */
  function refreshDay() {
    void prefill.refetch();
  }

  const workItemById = useMemo(() => {
    const map = new Map<string, { itemNumber: string; description: string }>();
    (boqItems.data ?? []).forEach((item) => map.set(item.id, item));
    return map;
  }, [boqItems.data]);

  function addSuggestion(boqItemId: string) {
    const item = workItemById.get(boqItemId);
    setWorkLines((lines) => {
      if (lines.some((line) => line.boqItemId === boqItemId)) {
        return lines;
      }
      const line: WorkLine = {
        key: crypto.randomUUID(),
        boqItemId,
        activity: item?.description ?? '',
        workLocation: '',
        quantity: undefined,
      };
      // Replaces the blank starter row rather than sitting under it.
      const blank = lines.findIndex((existing) => !existing.activity && !existing.boqItemId);
      if (blank >= 0) {
        const next = [...lines];
        next[blank] = line;
        return next;
      }
      return [...lines, line];
    });
  }

  function narrative() {
    return {
      weather: weather || undefined,
      temperatureC: temperatureC ? Number(temperatureC) : undefined,
      workingHoursLost: workingHoursLost ? Number(workingHoursLost) : undefined,
      workSummary: workSummary || undefined,
      delays: delays || undefined,
      safetyObservations: safetyObservations || undefined,
      qualityObservations: qualityObservations || undefined,
      instructionsReceived: instructionsReceived || undefined,
      managementAttention: managementAttention || undefined,
      nextDayPlan: nextDayPlan || undefined,
    };
  }

  /** Only rows somebody actually typed something into. A blank starter row claims nothing. */
  function filledWorkItems(): WorkItemInput[] {
    return workLines
      .filter((line) => line.activity.trim().length > 0)
      .map((line) => ({
        boqItemId: line.boqItemId || undefined,
        activity: line.activity.trim(),
        workLocation: line.workLocation || undefined,
        quantity: line.quantity === undefined || Number.isNaN(line.quantity)
          ? undefined
          : line.quantity,
        remarks: line.remarks || undefined,
      }));
  }

  function filledMachinery(): MachineryInput[] {
    return machinery
      .filter((line) => line.machineryName.trim().length > 0)
      .map(({ key: _key, ...rest }) => rest);
  }

  async function saveDraft() {
    const workItems = filledWorkItems();
    const machineryLines = filledMachinery();
    if (saved) {
      const next = await update.mutateAsync({
        id: saved.id,
        input: {
          ...narrative(),
          workItems,
          machinery: machineryLines,
          version: saved.version,
        },
      });
      setSaved(next);
      setSavedHere(true);
      return next;
    }
    const created = await create.mutateAsync({
      id: crypto.randomUUID(),
      siteId,
      reportDate,
      ...narrative(),
      workItems,
      machinery: machineryLines,
    });
    setSaved(created);
    setSavedHere(true);
    return created;
  }

  async function saveDraftWithPhotos() {
    const draft = await saveDraft();
    await uploadPhotos(draft.id);
  }

  /**
   * Sends the photographs the supervisor picked, once there is a report to attach them to.
   *
   * <p>A failure here does not fail the save. The report is the record; a photograph that
   * did not go up is worth saying out loud and worth trying again, and it is not worth
   * throwing away a day's typing over.</p>
   */
  async function uploadPhotos(dprId: string) {
    if (photos.length === 0) {
      return;
    }
    setPhotoError(null);
    const failed: File[] = [];
    for (const file of photos) {
      try {
        await attachPhoto.mutateAsync({ dprId, siteId, file });
      } catch {
        failed.push(file);
      }
    }
    setPhotos(failed);
    if (failed.length > 0) {
      setPhotoError(
        `${failed.length} photograph(s) did not go up. The report is saved — try them again.`,
      );
    }
  }

  async function saveAndSubmit() {
    const draft = await saveDraft();
    await uploadPhotos(draft.id);
    const sent = await submit.mutateAsync(draft.id);
    setSaved(sent);
  }

  const busy = create.isPending || update.isPending || submit.isPending;
  const error = create.error ?? update.error ?? submit.error;
  /** Nothing moves while the day belongs to a report the supervisor has not answered for. */
  const blocked = closed || mustChoose;

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Daily report</Typography>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
          sx={{ minWidth: 220 }}
        >
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="Date"
          type="date"
          value={reportDate}
          onChange={(e) => setReportDate(e.target.value)}
          InputLabelProps={{ shrink: true }}
          inputProps={{ max: today() }}
        />
      </Stack>

      {!saved && covered && existing.isLoading && (
        <Alert severity="info">Looking up the report already filed for this day…</Alert>
      )}

      {closed && existingReport && (
        <Alert severity="info">
          {existingReport.dprNumber} already covers {existingReport.reportDate} at this site and
          has been {existingReport.workflowStatus.toLowerCase()}, so it can no longer be edited.{' '}
          <Box component={Link} to="/dprs" sx={{ color: 'inherit' }}>
            Open it from the reports list
          </Box>
          .
        </Alert>
      )}

      {mustChoose && existingReport && !closed && (
        <ExistingDraftChoice
          report={existingReport}
          canDelete={canDelete}
          onResume={() => resumeDraft(existingReport)}
          onStartFresh={() => startFresh(existingReport)}
          onDeleted={() => {
            // The day is free again and nothing on this screen is standing on that report
            // any more, so the wizard goes back to what it is on an empty day: a blank one.
            clearForm();
            setDraftChoice('UNDECIDED');
            setStep(0);
            void prefill.refetch();
          }}
        />
      )}

      {saved && !savedHere && draftChoice === 'RESUMED' && (
        <Alert severity="info">
          Carrying on with {saved.dprNumber}, as it was left. Saving writes over it.
        </Alert>
      )}

      {saved && !savedHere && draftChoice === 'FRESH' && (
        <Alert severity="warning">
          Writing {saved.dprNumber} again from scratch. What is in the draft now stays there
          until you save — the save is what replaces it.
        </Alert>
      )}

      {saved && savedHere && (
        <Alert severity={saved.workflowStatus === 'SUBMITTED' ? 'success' : 'info'}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
            <span>{saved.dprNumber} saved.</span>
            <StatusChip status={saved.workflowStatus === 'SUBMITTED' ? 'SUBMITTED' : 'DRAFT'} />
            {saved.workflowStatus === 'SUBMITTED' && (
              <span>Waiting on the engineer&rsquo;s signature.</span>
            )}
          </Stack>
        </Alert>
      )}

      <Stepper activeStep={step} alternativeLabel sx={{ display: { xs: 'none', sm: 'flex' } }}>
        {STEPS.map((label) => (
          <Step key={label}>
            <StepLabel>{label}</StepLabel>
          </Step>
        ))}
      </Stepper>

      {prefill.isLoading && <CircularProgress />}
      {prefill.isError && <Alert severity="error">{apiErrorDetail(prefill.error)}</Alert>}
      {error && <Alert severity="error">{apiErrorDetail(error)}</Alert>}

      {step === 0 && prefill.data && (
        <>
          <PrefillStep data={prefill.data} onUseSuggestion={addSuggestion} />
          {/*
            Below the figures rather than above them, and in the order the day happens. What
            the records already know comes first — most evenings that is the whole answer and
            he scrolls past these — and what is still missing can be typed here rather than
            on four other screens.
          */}
          <Typography variant="h2" sx={{ fontSize: '1.15rem', mt: 1 }}>
            Anything not entered yet
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: -1 }}>
            Each box saves on its own, into the same records the other screens write to.
          </Typography>
          <OutsourcedLabourCard siteId={siteId} date={reportDate} onSaved={refreshDay} />
          <MaterialCard siteId={siteId} date={reportDate} mode="RECEIVED" onSaved={refreshDay} />
          <MaterialCard siteId={siteId} date={reportDate} mode="USED" onSaved={refreshDay} />
          <ExpenseCard siteId={siteId} date={reportDate} onSaved={refreshDay} />
          <PhotoCard
            files={photos}
            onChange={setPhotos}
            uploaded={saved?.photos.length ?? 0}
            siteCode={sites.data?.find((site) => site.id === siteId)?.code}
            reportDate={reportDate}
          />
          {photoError && <Alert severity="warning">{photoError}</Alert>}
        </>
      )}

      {step === 1 && (
        <WorkStep
          lines={workLines}
          machinery={machinery}
          boqItems={boqItems.data ?? []}
          onLinesChange={setWorkLines}
          onMachineryChange={setMachinery}
        />
      )}

      {step === 2 && (
        <Stack spacing={2}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="Weather"
              value={weather}
              onChange={(e) => setWeather(e.target.value as Weather)}
              sx={{ minWidth: 160 }}
            >
              {WEATHER.map((option) => (
                <MenuItem key={option.value} value={option.value}>
                  {option.label}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Temperature (°C)"
              type="number"
              value={temperatureC}
              onChange={(e) => setTemperatureC(e.target.value)}
              sx={{ maxWidth: 160 }}
            />
            {/*
              Hours lost is the operationally useful half of a weather note: "rain" is
              weather, "an hour and a half off the brickwork" is a delay somebody can price.
            */}
            <TextField
              label="Working hours lost"
              type="number"
              value={workingHoursLost}
              onChange={(e) => setWorkingHoursLost(e.target.value)}
              sx={{ maxWidth: 180 }}
            />
          </Stack>
          <TextField
            label="What happened today"
            value={workSummary}
            onChange={(e) => setWorkSummary(e.target.value)}
            multiline
            minRows={3}
          />
          <TextField
            label="Delays and stoppages"
            value={delays}
            onChange={(e) => setDelays(e.target.value)}
            multiline
            minRows={2}
          />
          <TextField
            label="Safety"
            value={safetyObservations}
            onChange={(e) => setSafety(e.target.value)}
            multiline
            minRows={2}
          />
          <TextField
            label="Quality"
            value={qualityObservations}
            onChange={(e) => setQuality(e.target.value)}
            multiline
            minRows={2}
          />
          <TextField
            label="Instructions received"
            value={instructionsReceived}
            onChange={(e) => setInstructions(e.target.value)}
            multiline
            minRows={2}
          />
          <TextField
            label="Needs the office to act"
            value={managementAttention}
            onChange={(e) => setAttention(e.target.value)}
            multiline
            minRows={2}
            helperText="Anything the site cannot settle on its own."
          />
          <TextField
            label="Plan for tomorrow"
            value={nextDayPlan}
            onChange={(e) => setNextDayPlan(e.target.value)}
            multiline
            minRows={2}
          />
        </Stack>
      )}

      <Divider />

      <Stack direction="row" spacing={2} justifyContent="space-between">
        <Button disabled={step === 0} onClick={() => setStep((s) => s - 1)}>
          Back
        </Button>
        <Stack direction="row" spacing={2}>
          {step < STEPS.length - 1 && (
            <Button
              variant="contained"
              onClick={() => setStep((s) => s + 1)}
              disabled={blocked}
            >
              Next
            </Button>
          )}
          {step === STEPS.length - 1 && (
            <>
              <Button
                variant="outlined"
                onClick={() => void saveDraftWithPhotos()}
                disabled={busy || blocked || !siteId}
              >
                Save draft
              </Button>
              {/*
                Sending is a separate act from saving, the same way an expense is. A report
                sent for signature is one the supervisor is standing behind.
              */}
              <Button
                variant="contained"
                onClick={() => void saveAndSubmit()}
                disabled={busy || blocked || !siteId}
              >
                Send for signature
              </Button>
            </>
          )}
        </Stack>
      </Stack>
    </Stack>
  );
}

/**
 * The day already has a report, and the supervisor is asked what to do about it.
 *
 * <p>The screen used to answer this for him — "open it from the reports list" — and the
 * reports list had no way to open one for editing, so a draft started and left was a draft
 * nobody could finish. Two answers, then, and both write to this same report: one report per
 * site per day is the invariant, so starting fresh cannot mean a second one.</p>
 *
 * <p>Starting fresh asks twice. It is the only button on the screen that throws away work
 * somebody typed, and the second press is what separates it from a mis-tap.</p>
 *
 * <p>Three answers now, because starting fresh answers only two of the three cases. It is
 * right when the day happened and was written up badly; it is no help at all when the report
 * should not exist — the wrong site picked at six in the morning, a Sunday opened out of
 * habit. An emptied draft still holds a number, still sits in the register as a draft, and
 * still holds the day against the one report per site per day rule. Deleting it hands the
 * day back.</p>
 */
function ExistingDraftChoice({
  report,
  canDelete,
  onResume,
  onStartFresh,
  onDeleted,
}: {
  report: Dpr;
  canDelete: boolean;
  onResume: () => void;
  onStartFresh: () => void;
  onDeleted: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const deleteDpr = useDeleteDpr();
  const lines = report.workItems.length;

  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography fontWeight={600}>{report.dprNumber} already covers this day</Typography>
        <StatusChip status={report.workflowStatus === 'REJECTED' ? 'REJECTED' : 'DRAFT'} />
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        {report.workflowStatus === 'REJECTED'
          ? `Sent back by the engineer${report.rejectionReason ? `: ${report.rejectionReason}` : ''}`
          : 'Started and never sent, so the office has not seen it.'}
        {' '}
        {lines === 0
          ? 'No work lines in it yet.'
          : `${lines} work line(s) in it${report.workSummary ? ', and the day written up' : ''}.`}
      </Typography>

      <Stack direction="row" spacing={1.5} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
        <Button variant="contained" color="secondary" onClick={onResume} sx={{ minHeight: 48 }}>
          Carry on with it
        </Button>
        {!confirming && (
          <Button variant="outlined" onClick={() => setConfirming(true)} sx={{ minHeight: 48 }}>
            Start fresh
          </Button>
        )}
        {confirming && (
          <>
            <Button
              variant="outlined"
              color="error"
              onClick={onStartFresh}
              sx={{ minHeight: 48 }}
            >
              Yes, clear it and start again
            </Button>
            <Button onClick={() => setConfirming(false)} sx={{ minHeight: 48 }}>
              Keep it
            </Button>
          </>
        )}
        {/*
          The third answer, and the quiet one: it asks for a reason in a dialog rather than
          escalating in place, because it is the least common of the three and the only one
          that takes the report off the register altogether.
        */}
        {!confirming && canDelete && (
          <Button
            variant="text"
            color="error"
            onClick={() => setDeleting(true)}
            sx={{ minHeight: 48 }}
          >
            Delete it
          </Button>
        )}
      </Stack>
      {confirming && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {report.dprNumber} keeps its number and everything typed into it is replaced when you
          save. The muster, the material and the bills behind the day are not touched.
        </Typography>
      )}

      <DeleteRecordDialog
        open={deleting}
        kind="report"
        label={`${report.dprNumber} (${report.reportDate})`}
        description="It comes off the reports list and this day is free to be written again from scratch. The muster, the material and the bills behind the day are not touched."
        onConfirm={async (reason) => {
          await deleteDpr.mutateAsync({ id: report.id, reason });
          onDeleted();
        }}
        onClose={() => setDeleting(false)}
      />
    </Paper>
  );
}

/**
 * Step one: what the records already say.
 *
 * <p>Read-only on purpose. These figures are derived from the muster, the ledger and the bill
 * book, and a field that let somebody type over them would create a second version of a number
 * the rest of the system computes — which is exactly the drift the prefill exists to prevent.
 * If a figure here is wrong, the record behind it is wrong and that is where it gets fixed.</p>
 */
function PrefillStep({
  data,
  onUseSuggestion,
}: {
  data: DprPrefill;
  onUseSuggestion: (boqItemId: string) => void;
}) {
  return (
    <Stack spacing={2}>
      <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
        <Typography fontWeight={600} gutterBottom>
          Labour
        </Typography>
        <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
          <Figure label="Present" value={String(data.labour.presentCount)} />
          <Figure label="Absent" value={String(data.labour.absentCount)} />
          <Figure label="Regular" value={formatHours(data.labour.regularHours)} />
          <Figure label="Overtime" value={formatHours(data.labour.overtimeHours)} />
          <Figure label="Wage cost" value={formatAmount(data.labour.cost)} />
        </Stack>
        {data.labourCostProvisional && (
          // The wage is frozen at verification, so this figure can still move without
          // anybody editing anything. Saying so is the difference between a number an
          // engineer trusts and one he stops reading.
          <Alert severity="warning" sx={{ mt: 1.5 }}>
            {data.labour.unverifiedCount} attendance row(s) behind this are not verified yet, so{' '}
            {formatAmount(data.labour.unverifiedCost)} of the wage cost is provisional.
          </Alert>
        )}
        {/*
          External labour, beside the muster and never inside it. Its own row of figures
          rather than a share of the ones above: those men have a wage behind their hours and
          these have none, so a single "45 present" would read as a wage bill spread over
          people who are not on our payroll.
        */}
        {data.outsourcedLabour.enabled && (
          <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap sx={{ mt: 1.5 }}>
            <Figure label="External (counted)" value={String(data.outsourcedLabour.headCount)} />
            <Figure
              label="External man-hours"
              value={
                data.outsourcedLabour.manHours > 0
                  ? formatHours(data.outsourcedLabour.manHours)
                  : 'Not recorded'
              }
            />
          </Stack>
        )}
        {data.labour.lines.length > 0 && (
          <RecordTable
            nested
            columns={LABOUR_COLUMNS}
            rows={data.labour.lines}
            rowKey={(line) =>
              `${line.skillCategoryId ?? 'none'}-${line.labourSupplierId ?? 'direct'}`
            }
            ariaLabel="Labour on site"
          />
        )}
      </Paper>

      <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
        <Typography fontWeight={600} gutterBottom>
          Material
        </Typography>
        {/*
          Received and consumed side by side and never added. What arrived at the store is
          inventory; what left for the work face is cost. One blended figure would count the
          same cement twice — once as a delivery and again as consumption.
        */}
        <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
          <Figure label="Received (inventory)" value={formatAmount(data.material.receivedValue)} />
          <Figure label="Consumed (cost)" value={formatAmount(data.material.consumedValue)} />
          <Figure label="Receipts" value={String(data.material.receiptCount)} />
          <Figure label="Issues" value={String(data.material.issueCount)} />
        </Stack>
        {data.material.lines.length > 0 && (
          <RecordTable
            nested
            columns={MATERIAL_COLUMNS}
            rows={data.material.lines}
            rowKey={(line) => line.materialId}
            ariaLabel="Material moved today"
          />
        )}
      </Paper>

      <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
        <Typography fontWeight={600} gutterBottom>
          Cash
        </Typography>
        <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
          <Figure label="Cost incurred" value={formatAmount(data.expense.costIncurred)} />
          <Figure label="Material purchases" value={formatAmount(data.expense.materialPurchases)} />
          <Figure label="Wage payments" value={formatAmount(data.expense.labourDisbursements)} />
          <Figure label="Total booked" value={formatAmount(data.expense.totalBooked)} />
        </Stack>
        {data.expense.unapprovedCount > 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            {data.expense.unapprovedCount} of {data.expense.expenseCount} bill(s) are not approved
            yet.
          </Typography>
        )}
      </Paper>

      <Alert severity="info">{data.caveat}</Alert>

      {data.suggestedWorkItems.length > 0 && (
        <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <Typography fontWeight={600}>Worked on today, by the look of the records</Typography>
          {/*
            Suggestions rather than entries. Material issued against a line is evidence
            somebody worked on it; it is not a measurement of how much got built, and only a
            measurement may reach the measurement book.
          */}
          <Typography variant="body2" color="text.secondary" gutterBottom>
            Material or labour was charged to these lines. Add the ones you measured.
          </Typography>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
            {data.suggestedWorkItems.map((item) => (
              <Chip
                key={item.boqItemId}
                label={`${item.itemNumber} · ${item.because}`}
                onClick={() => onUseSuggestion(item.boqItemId)}
                icon={<AddIcon />}
                variant="outlined"
              />
            ))}
          </Stack>
        </Paper>
      )}
    </Stack>
  );
}

function WorkStep({
  lines,
  machinery,
  boqItems,
  onLinesChange,
  onMachineryChange,
}: {
  lines: WorkLine[];
  machinery: MachineryLine[];
  boqItems: { id: string; itemNumber: string; description: string }[];
  onLinesChange: (lines: WorkLine[]) => void;
  onMachineryChange: (lines: MachineryLine[]) => void;
}) {
  function patch(key: string, changes: Partial<WorkLine>) {
    onLinesChange(lines.map((line) => (line.key === key ? { ...line, ...changes } : line)));
  }

  return (
    <Stack spacing={2}>
      <Typography fontWeight={600}>Work done</Typography>
      {/*
        A quantity is optional and that is deliberate. Shuttering struck, curing, a site
        cleared — all real work, none of it a claim against a contract line. Only a measured
        row reaches the measurement book, and only when the engineer signs.
      */}
      <Typography variant="body2" color="text.secondary">
        A quantity claims work against the contract when the engineer verifies this report.
        Leave it blank to record work without claiming it.
      </Typography>

      {lines.map((line) => (
        <Paper key={line.key} elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <Stack spacing={2}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                select
                label="Contract item"
                value={line.boqItemId ?? ''}
                onChange={(e) => patch(line.key, { boqItemId: e.target.value || undefined })}
                sx={{ minWidth: 220 }}
                helperText="Blank for work that measures against no line"
              >
                <MenuItem value="">Not against a contract line</MenuItem>
                {boqItems.map((item) => (
                  <MenuItem key={item.id} value={item.id}>
                    {item.itemNumber} — {item.description}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                label="Quantity"
                type="number"
                value={line.quantity ?? ''}
                onChange={(e) =>
                  patch(line.key, {
                    quantity: e.target.value === '' ? undefined : Number(e.target.value),
                  })
                }
                sx={{ maxWidth: 160 }}
              />
              <IconButton
                aria-label="Remove this line"
                onClick={() => onLinesChange(lines.filter((other) => other.key !== line.key))}
                sx={{ width: 48, height: 48, alignSelf: 'center' }}
              >
                <DeleteOutlineIcon />
              </IconButton>
            </Stack>
            <TextField
              label="What was done"
              value={line.activity}
              onChange={(e) => patch(line.key, { activity: e.target.value })}
              multiline
              minRows={2}
            />
            <TextField
              label="Where"
              value={line.workLocation ?? ''}
              onChange={(e) => patch(line.key, { workLocation: e.target.value })}
            />
          </Stack>
        </Paper>
      ))}

      <Button startIcon={<AddIcon />} onClick={() => onLinesChange([...lines, emptyWorkLine()])}>
        Add a line
      </Button>

      <Divider />

      <Typography fontWeight={600}>Machinery</Typography>
      {machinery.map((line) => (
        <Paper key={line.key} elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Machine"
              value={line.machineryName}
              onChange={(e) =>
                onMachineryChange(
                  machinery.map((other) =>
                    other.key === line.key ? { ...other, machineryName: e.target.value } : other,
                  ),
                )
              }
              sx={{ minWidth: 200 }}
            />
            <TextField
              label="Hours run"
              type="number"
              value={line.hoursUsed}
              onChange={(e) =>
                onMachineryChange(
                  machinery.map((other) =>
                    other.key === line.key
                      ? { ...other, hoursUsed: Number(e.target.value) }
                      : other,
                  ),
                )
              }
              sx={{ maxWidth: 140 }}
            />
            {/*
              Idle hours are the half worth having: a mixer that stood for four hours is a
              story about the rain, not about the mixer.
            */}
            <TextField
              label="Idle hours"
              type="number"
              value={line.idleHours}
              onChange={(e) =>
                onMachineryChange(
                  machinery.map((other) =>
                    other.key === line.key
                      ? { ...other, idleHours: Number(e.target.value) }
                      : other,
                  ),
                )
              }
              sx={{ maxWidth: 140 }}
            />
            <IconButton
              aria-label="Remove this machine"
              onClick={() =>
                onMachineryChange(machinery.filter((other) => other.key !== line.key))
              }
              sx={{ width: 48, height: 48, alignSelf: 'center' }}
            >
              <DeleteOutlineIcon />
            </IconButton>
          </Stack>
        </Paper>
      ))}
      <Button
        startIcon={<AddIcon />}
        onClick={() =>
          onMachineryChange([
            ...machinery,
            { key: crypto.randomUUID(), machineryName: '', count: 1, hoursUsed: 0, idleHours: 0 },
          ])
        }
      >
        Add a machine
      </Button>
    </Stack>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography fontWeight={600}>{value}</Typography>
    </Box>
  );
}
