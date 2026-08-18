import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  Step,
  StepLabel,
  Stepper,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
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
  useDecideDpr,
  useDeleteDpr,
  useDpr,
  usePrefill,
  useSetPlantRates,
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
import { causeLabel, causeNeedsNote, isWritable, NON_OPERATIONAL_CAUSES } from './types';
import type {
  Dpr,
  DprConditions,
  DprObservations,
  DprPrefill,
  LabourLine,
  MachineryInput,
  MaterialLine,
  NonOperationalCause,
  RateBasis,
  Weather,
  WorkItemInput,
} from './types';

const DAY_STEP = 'The day so far';
const WORK_STEP = 'Work done';
const OBSERVATION_STEP = 'Observations';

/**
 * The steps this person has on this report.
 *
 * <p>Two things narrow it. The report has two authors — the supervisor records what the day
 * <b>was</b> and hands it over, the engineer records what was <b>built</b> and signs — so a
 * supervisor never sees the last two steps at all. And a day the site did not work has only
 * the first step for either of them: there is nothing built to describe and nothing observed
 * to write down, and the cause is the whole of what the report has to say.</p>
 */
function stepsFor(canVerify: boolean, operational: boolean): string[] {
  if (!operational || !canVerify) {
    return [DAY_STEP];
  }
  return [DAY_STEP, WORK_STEP, OBSERVATION_STEP];
}

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
  /**
   * The server's row id, absent on a machine typed here and not yet saved.
   *
   * <p>The rate is written by a different call against this id, so a row that has never been
   * saved cannot carry one — there is nothing yet to price.</p>
   */
  id?: string;
  hireRate?: number | undefined;
  rateBasis?: RateBasis | undefined;
  hireAmount?: number | undefined;
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function emptyWorkLine(): WorkLine {
  return { key: crypto.randomUUID(), activity: '', workLocation: '', quantity: undefined };
}

/**
 * What the records already hold for the day, in the words a supervisor would use.
 *
 * <p>Only ever read when he is about to say the site did not work. A muster with twelve men
 * marked present behind a day recorded as lost is not necessarily an error — a watchman was
 * there, a gang was paid to stand — but it is a contradiction, and a screen that let it
 * through in silence would be the reason a month's delay statement does not survive being
 * read.</p>
 */
function recordsBehind(data: DprPrefill | undefined): string[] {
  if (!data) {
    return [];
  }
  const found: string[] = [];
  if (data.labour.presentCount > 0) {
    found.push(`${data.labour.presentCount} man/men marked present on the muster`);
  }
  if (data.outsourcedLabour.headCount > 0) {
    found.push(`${data.outsourcedLabour.headCount} external man/men counted at the gate`);
  }
  if (data.material.receiptCount > 0) {
    found.push(`${data.material.receiptCount} material receipt(s)`);
  }
  if (data.material.issueCount > 0) {
    found.push(`${data.material.issueCount} material issue(s) to the work face`);
  }
  if (data.expense.expenseCount > 0) {
    found.push(`${data.expense.expenseCount} bill(s) booked`);
  }
  return found;
}

/**
 * The daily progress report, written the way the day actually happened.
 *
 * <p>It asks two questions before anything else, and the answers pick the shape of the rest.</p>
 *
 * <p><b>Did the site work today?</b> A day it did not is a different document, not an empty
 * one: it carries a cause off a closed list and a note and photographs, and it has no work
 * done and no observations because there were none. The alternative is what the form used to
 * force — a page of dashes, indistinguishable from a report somebody started and abandoned —
 * and a missing report says nothing at all, while "no work, rain" is what an extension of time
 * is claimed on.</p>
 *
 * <p><b>Whose half is this?</b> The supervisor records what the day was: whether the site
 * worked, in what conditions, and — through the entry cards on step one — the muster, the
 * material and the bills that are still missing. Then he hands it over. The engineer records
 * what was built and signs, because a quantity on this report becomes a claim against the
 * contract the moment he does, and the man who measures it should be the man who stands behind
 * it. What the supervisor said freezes at the handover along with the figures.</p>
 *
 * <p>Step one is still the argument for the whole screen. A supervisor at six in the evening
 * has already recorded the muster, the material and the bills; asking him to copy those figures
 * into a diary is asking him to make a transcription error on data the system already holds. So
 * it is not a form — it is what the records say, offered for confirmation.</p>
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

  const [siteOperational, setSiteOperational] = useState(true);
  const [cause, setCause] = useState<NonOperationalCause | ''>('');
  const [causeNote, setCauseNote] = useState('');
  const [weather, setWeather] = useState<Weather | ''>('');
  const [temperatureC, setTemperatureC] = useState('');
  const [workingHoursLost, setWorkingHoursLost] = useState('');
  const [instructionsReceived, setInstructions] = useState('');
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
  const [sendingBack, setSendingBack] = useState(false);
  const [sendBackReason, setSendBackReason] = useState('');

  const { hasPermission } = useAuth();
  const canDelete = hasPermission('dpr:delete');
  /** The engineer — the half of the report that claims work is his, and so is the signature. */
  const canVerify = hasPermission('dpr:verify');
  const canApprove = hasPermission('dpr:approve');
  const sites = useSites();
  const [siteId, setSiteId] = useSelectedSite(sites.data);
  const prefill = usePrefill(siteId || undefined, reportDate);
  const boqItems = useBoqItems(siteId || undefined);
  const attachPhoto = useAttachDprPhoto();
  const create = useCreateDpr();
  const update = useUpdateDpr();
  const submit = useSubmitDpr();
  const decide = useDecideDpr();
  const setPlantRates = useSetPlantRates();

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
    setSiteOperational(true);
    setCause('');
    setCauseNote('');
    setWeather('');
    setTemperatureC('');
    setWorkingHoursLost('');
    setInstructions('');
    setNextDayPlan('');
    setWorkLines([emptyWorkLine()]);
    setMachinery([]);
    setPhotos([]);
    setPhotoError(null);
  }

  /** Reads a saved report back into the form, so continuing it starts where it was left. */
  function loadForm(report: Dpr) {
    setSiteOperational(report.siteOperational);
    setCause(report.nonOperationalCause ?? '');
    setCauseNote(report.nonOperationalNote ?? '');
    setWeather(report.weather ?? '');
    setTemperatureC(report.temperatureC == null ? '' : String(report.temperatureC));
    setWorkingHoursLost(
      report.workingHoursLost == null ? '' : String(report.workingHoursLost),
    );
    setInstructions(report.instructionsReceived ?? '');
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
        id: line.id,
        machineryName: line.machineryName,
        count: line.count,
        hoursUsed: line.hoursUsed,
        idleHours: line.idleHours,
        remarks: line.remarks,
        hireRate: line.hireRate,
        rateBasis: line.rateBasis,
        hireAmount: line.hireAmount,
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
  /**
   * A report that has been handed over, opened by anybody who may still write to it.
   *
   * <p>Nobody is asked what to do about it. "Carry on / start fresh / delete" is the question
   * put to somebody who finds his own unfinished draft; a submitted report is not unfinished by
   * accident. For the engineer it is waiting on him, and for the supervisor it is the day he
   * has come back to correct — and neither answer is ever "throw it away and start again",
   * which on a report that has gone would be destroying a document somebody is working from.
   * </p>
   */
  const handedToMe = Boolean(
    existingReport && existingReport.workflowStatus === 'SUBMITTED',
  );

  useEffect(() => {
    if (handedToMe && existingReport && draftChoice === 'UNDECIDED' && !saved) {
      resumeDraft(existingReport);
    }
    // resumeDraft only calls setState; the report arriving is the trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [handedToMe, existingReport, draftChoice, saved]);

  /** The report the screen is standing on, whichever door it came through. */
  const report = saved ?? existingReport;
  /** Handed over: the work and the observations are the engineer's from here. */
  const handedOver = report?.workflowStatus === 'SUBMITTED';
  /** A report covers the day and it is past this user's editing — a dead end, and it says so. */
  const closed = Boolean(report && !isWritable(report.workflowStatus));
  /** The question is on screen and nothing moves until it is answered. */
  const mustChoose = !saved && covered && draftChoice === 'UNDECIDED' && !handedToMe;
  /** Nothing moves while the day belongs to a report the supervisor has not answered for. */
  const blocked = closed || mustChoose;
  /*
    What the day was stays with the site until the report is signed — the weather typed wrong
    at six, the second mixer that arrived at four. It used to freeze at the handover, which
    left the man who could see those things telephoning them to somebody.

    The engineer is the exception, and the server draws the same line: he signs somebody
    else's account of a day he may not have been standing on, so on a handed-over report his
    screen shows it and does not offer to change it.
  */
  const conditionsWritable = !blocked && !(handedOver && canVerify);
  /*
    Who may put a price on the plant, and when. Whoever the report went to after the
    handover — the engineer signing it or the office accepting it — and never the supervisor,
    who records what stood on the site and not what it costs. A draft has not been given to
    anybody yet; an approved report is finished. The server enforces all of this; this is
    only the screen not offering what would be refused.
  */
  const canPricePlant =
    (canVerify || canApprove)
    && (report?.workflowStatus === 'SUBMITTED' || report?.workflowStatus === 'VERIFIED');

  const steps = stepsFor(canVerify, siteOperational);
  // Flipping the day to "no work" while standing on the observations step would otherwise
  // leave the wizard on a step that no longer exists.
  const activeStep = Math.min(step, steps.length - 1);

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

  /**
   * The supervisor's half: what the day was, and what stood on the site while it happened.
   *
   * <p>No plant on a day nobody worked — the machines were where they were, and a report that
   * says the site was shut has nothing to say about their hours.</p>
   */
  function conditions(): DprConditions {
    return {
      siteOperational,
      nonOperationalCause: siteOperational ? undefined : (cause || undefined),
      nonOperationalNote: siteOperational ? undefined : (causeNote || undefined),
      weather: weather || undefined,
      temperatureC: temperatureC ? Number(temperatureC) : undefined,
      workingHoursLost: workingHoursLost ? Number(workingHoursLost) : undefined,
      machinery: siteOperational ? filledMachinery() : [],
    };
  }

  /**
   * The engineer's half: what was built, and the two things the office cannot learn elsewhere.
   *
   * <p>Empty on a day the site did not work, and that is the point rather than an oversight —
   * a report cannot both say nobody worked and claim a quantity against the contract, and the
   * server refuses the combination.</p>
   */
  function observations(): DprObservations {
    if (!siteOperational) {
      return { workItems: [] };
    }
    return {
      instructionsReceived: instructionsReceived || undefined,
      nextDayPlan: nextDayPlan || undefined,
      workItems: filledWorkItems(),
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

  /**
   * Only rows somebody typed a machine into — and only the fields that are the site's.
   *
   * <p>The rate, its basis and its amount are stripped rather than passed through. They are
   * the office's answer, written by their own call against the row id; sending them back
   * inside the supervisor's half would be this screen offering a field the server refuses
   * from that direction.</p>
   */
  function filledMachinery(): MachineryInput[] {
    return machinery
      .filter((line) => line.machineryName.trim().length > 0)
      .map(({
        key: _key, id: _id, hireRate: _rate, rateBasis: _basis, hireAmount: _amount, ...rest
      }) => rest);
  }

  /**
   * Saves the half of the report this user owns, and only that half.
   *
   * <p>A supervisor's save carries no work items at all rather than an empty list. The
   * difference matters on a report the engineer filled in and sent back: an empty list is an
   * instruction to delete his lines, and the supervisor pressing Save on a screen that never
   * showed them to him is not an instruction to do anything of the sort.</p>
   */
  async function saveDraft() {
    if (saved) {
      const next = await update.mutateAsync({
        id: saved.id,
        input: {
          ...(conditionsWritable ? conditions() : {}),
          ...(canVerify ? observations() : {}),
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
      ...conditions(),
      ...(canVerify ? observations() : {}),
    });
    setSaved(created);
    setSavedHere(true);
    return created;
  }

  async function saveDraftWithPhotos() {
    const draft = await saveDraft();
    await uploadPhotos(draft.id);
    await savePlantRates(draft.id);
  }

  /**
   * Sends what the plant is charged at, for whoever may say so.
   *
   * <p>A second call rather than part of the save, because the server holds the two halves
   * apart: the plant rows are the supervisor's and the rate on them is the office's. Rows
   * typed on this screen and not yet saved have no id to price and are left for the next
   * save, which is what the boxes say while they wait.</p>
   *
   * <p>Like the photographs, a failure here does not fail the save. The report is the
   * record.</p>
   */
  async function savePlantRates(dprId: string) {
    if (!canPricePlant) {
      return;
    }
    const rates = machinery
      .filter((line) => line.id !== undefined)
      .map((line) => ({
        machineryId: line.id as string,
        rate: line.hireRate,
        basis: line.hireRate === undefined ? undefined : (line.rateBasis ?? 'HOUR'),
      }));
    if (rates.length === 0) {
      return;
    }
    const priced = await setPlantRates.mutateAsync({ id: dprId, rates });
    // Read the amounts back rather than working them out here: the arithmetic is the
    // server's, and a second version of it on this screen is a second answer.
    setSaved(priced);
    setMachinery((lines) =>
      lines.map((line) => {
        const row = priced.machinery.find((candidate) => candidate.id === line.id);
        return row === undefined
          ? line
          : { ...line, hireRate: row.hireRate, rateBasis: row.rateBasis,
              hireAmount: row.hireAmount };
      }),
    );
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

  /** The engineer's end of it: finish what he wrote, then put his name on it. */
  async function saveAndSign() {
    const draft = await saveDraft();
    await uploadPhotos(draft.id);
    // Before the signature rather than after, so the document he signs carries the figure
    // instead of acquiring it a moment later.
    await savePlantRates(draft.id);
    const signed = await decide.mutateAsync({ id: draft.id, action: 'VERIFY' });
    setSaved(signed);
  }

  async function sendBack(reason: string) {
    if (!report) {
      return;
    }
    const returned = await decide.mutateAsync({
      id: report.id,
      action: 'REJECT',
      remarks: reason,
    });
    setSaved(returned);
    setSendingBack(false);
    setSendBackReason('');
  }

  const busy =
    create.isPending || update.isPending || submit.isPending || decide.isPending;
  const error = create.error ?? update.error ?? submit.error ?? decide.error;
  const contradictions = siteOperational ? [] : recordsBehind(prefill.data);
  const measuredLines = filledWorkItems().filter(
    (line) => line.boqItemId && line.quantity != null && line.quantity > 0,
  ).length;
  /** A cause is required, and "other" on its own is not one. */
  const causeMissing =
    !siteOperational && (!cause || (causeNeedsNote(cause) && !causeNote.trim()));
  const onLastStep = activeStep === steps.length - 1;

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

      {/*
        Not said to the man who just handed it over. "It is no longer yours to edit" is true
        the instant he presses the button, and printed beside his own success it reads as a
        refusal of what he just did — the line below already tells him where the report went.
      */}
      {closed && report && !savedHere && (
        <Alert severity="info">
          {report.dprNumber} already covers {report.reportDate} at this site and has been{' '}
          {report.workflowStatus.toLowerCase()}, so it is no longer yours to edit.{' '}
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

      {/*
        The engineer's half of the handover, said plainly. He did not write what is above the
        work step and he does not change it — that is the site's account of a day he may not
        have been standing on, and he is about to sign under it.
      */}
      {handedOver && canVerify && (
        <Alert severity="info">
          <AlertTitle>{report?.dprNumber} is waiting on you</AlertTitle>
          {report?.preparedByName ?? 'The supervisor'} recorded what the day was and handed it
          over. What was built and the day&rsquo;s observations are yours to write. The
          conditions are the site&rsquo;s and stay theirs until you sign; the figures were
          frozen when it was sent.
        </Alert>
      )}

      {/*
        And the supervisor's side of the same moment. The report has gone and he can still say
        what the day was — the weather he typed wrong at six, the second mixer that arrived at
        four — because he is the one who can see it. It closes when the engineer signs.
      */}
      {handedOver && !canVerify && (
        <Alert severity="info">
          <AlertTitle>{report?.dprNumber} has gone to the engineer</AlertTitle>
          What was built is his to write now. You can still correct what the day was until he
          signs it; the figures froze when it was sent.
        </Alert>
      )}

      {saved && !savedHere && draftChoice === 'RESUMED' && !handedOver && (
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
        <Alert
          severity={
            saved.workflowStatus === 'DRAFT'
              ? 'info'
              : saved.workflowStatus === 'REJECTED'
                ? 'warning'
                : 'success'
          }
        >
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
            <span>{saved.dprNumber} saved.</span>
            {/* The four workflow states are four of the chip's own, so it says the real one. */}
            <StatusChip status={saved.workflowStatus} />
            {saved.workflowStatus === 'SUBMITTED' && (
              <span>Waiting on the engineer&rsquo;s signature.</span>
            )}
            {saved.workflowStatus === 'VERIFIED' && (
              <span>
                Signed
                {typeof saved.progressPosted === 'number' && saved.progressPosted > 0
                  ? ` · ${saved.progressPosted} claim(s) posted to the measurement book`
                  : ''}
                .
              </span>
            )}
            {saved.workflowStatus === 'REJECTED' && <span>Sent back to the supervisor.</span>}
          </Stack>
        </Alert>
      )}

      {steps.length > 1 && (
        <Stepper activeStep={activeStep} alternativeLabel sx={{ display: { xs: 'none', sm: 'flex' } }}>
          {steps.map((label) => (
            <Step key={label}>
              <StepLabel>{label}</StepLabel>
            </Step>
          ))}
        </Stepper>
      )}

      {prefill.isLoading && <CircularProgress />}
      {prefill.isError && <Alert severity="error">{apiErrorDetail(prefill.error)}</Alert>}
      {error && <Alert severity="error">{apiErrorDetail(error)}</Alert>}

      {steps[activeStep] === DAY_STEP && (
        <>
          <DayStatusCard
            operational={siteOperational}
            cause={cause}
            note={causeNote}
            readOnly={!conditionsWritable}
            contradictions={contradictions}
            onOperationalChange={(next) => {
              setSiteOperational(next);
              if (next) {
                setCause('');
                setCauseNote('');
              }
              setStep(0);
            }}
            onCauseChange={setCause}
            onNoteChange={setCauseNote}
          />

          {/*
            The conditions the day was worked in, and they are the supervisor's — he was
            standing in them. Beside the operational question rather than three steps away,
            because "rain" and "the site did not work, because of the weather" are one answer
            given twice and they belong on one screen where they can be seen to agree.
          */}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              select
              label="Weather"
              value={weather}
              onChange={(e) => setWeather(e.target.value as Weather)}
              disabled={!conditionsWritable}
              sx={{ minWidth: 160 }}
            >
              {WEATHER.map((option) => (
                <MenuItem key={option.value} value={option.value}>
                  {option.label}
                </MenuItem>
              ))}
            </TextField>
            {/*
              The two numbers share a row on the handset instead of taking a line each. Both
              hold three characters, so a column of them is mostly empty screen between the
              weather and the entry cards — and they are read together anyway: hot, and two
              hours off the brickwork.

              Hours lost is the operationally useful half of a weather note: "rain" is
              weather, "an hour and a half off the brickwork" is a delay somebody can price.
            */}
            <Stack direction="row" spacing={2}>
              <TextField
                label="Temperature (°C)"
                type="number"
                value={temperatureC}
                onChange={(e) => setTemperatureC(e.target.value)}
                disabled={!conditionsWritable}
                sx={{ flex: 1, maxWidth: { sm: 160 } }}
              />
              <TextField
                label="Working hours lost"
                type="number"
                value={workingHoursLost}
                onChange={(e) => setWorkingHoursLost(e.target.value)}
                disabled={!conditionsWritable}
                sx={{ flex: 1, maxWidth: { sm: 180 } }}
              />
            </Stack>
          </Stack>

          {siteOperational && prefill.data && (
            <PrefillStep
              data={prefill.data}
              onUseSuggestion={addSuggestion}
              suggestable={canVerify}
            />
          )}

          {/*
            Below the figures rather than above them, and in the order the day happens. What
            the records already know comes first — most evenings that is the whole answer and
            he scrolls past these — and what is still missing can be typed here rather than
            on four other screens. Not offered once the report has gone: its figures are
            frozen, so a receipt entered here would move the ledger and not the document.
          */}
          {siteOperational && !handedOver && (
            <>
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
            </>
          )}

          {/*
            Plant, on the day step rather than beside the work claimed. A mixer that ran six
            hours and stood for two is something the supervisor watched happen: it measures
            against no contract line, nothing is billed off it, and he is the man who was
            there to see it. The idle hours are the half worth having — a machine that stood
            for four hours is a story about the rain, not about the machine.
          */}
          {siteOperational && (
            <MachineryEditor
              lines={machinery}
              readOnly={!conditionsWritable}
              rateWritable={canPricePlant}
              onChange={setMachinery}
            />
          )}

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

      {steps[activeStep] === WORK_STEP && (
        <WorkStep
          lines={workLines}
          boqItems={boqItems.data ?? []}
          onLinesChange={setWorkLines}
        />
      )}

      {/*
        Two boxes on a step that had seven. Four of the five that went were prose the site was
        asked for every evening and answered with "nil", and the fifth restated the work rows
        on the step before it — a form that asks more than it needs teaches the man filling it
        that nothing on it is worth reading. These two are what the office cannot learn from
        any other record: an instruction the department gave on site, and what the site
        intends to do next.
      */}
      {steps[activeStep] === OBSERVATION_STEP && (
        <Stack spacing={2}>
          <TextField
            label="Instructions received from the department"
            value={instructionsReceived}
            onChange={(e) => setInstructions(e.target.value)}
            multiline
            minRows={3}
            helperText="What was said on site, and by whom. Blank if nothing was."
          />
          <TextField
            label="Plan for tomorrow"
            value={nextDayPlan}
            onChange={(e) => setNextDayPlan(e.target.value)}
            multiline
            minRows={3}
          />
        </Stack>
      )}

      <Divider />

      <Stack direction="row" spacing={2} justifyContent="space-between">
        <Button disabled={activeStep === 0} onClick={() => setStep((s) => s - 1)}>
          Back
        </Button>
        <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
          {!onLastStep && (
            <Button
              variant="contained"
              onClick={() => setStep((s) => s + 1)}
              disabled={blocked}
            >
              Next
            </Button>
          )}
          {onLastStep && (
            <>
              <Button
                variant="outlined"
                onClick={() => void saveDraftWithPhotos()}
                disabled={busy || blocked || !siteId || causeMissing}
              >
                {handedOver && !canVerify ? 'Save the correction' : 'Save draft'}
              </Button>
              {/*
                The two ends of the report, and only one of them is on screen at a time. The
                supervisor hands over what the day was; the engineer, holding a report that
                was handed to him, signs it — and signing is what claims the measured lines
                against the contract, so the button says how many.
              */}
              {!handedOver && (
                <Button
                  variant="contained"
                  onClick={() => void saveAndSubmit()}
                  disabled={busy || blocked || !siteId || causeMissing}
                >
                  {canVerify ? 'Send for signature' : 'Submit'}
                </Button>
              )}
              {handedOver && canVerify && (
                <>
                  <Button
                    variant="outlined"
                    color="warning"
                    onClick={() => setSendingBack(true)}
                    disabled={busy}
                  >
                    Send back
                  </Button>
                  <Button
                    variant="contained"
                    onClick={() => void saveAndSign()}
                    disabled={busy || !siteId}
                  >
                    {measuredLines > 0
                      ? `Sign and claim ${measuredLines} line(s)`
                      : 'Sign the report'}
                  </Button>
                </>
              )}
            </>
          )}
        </Stack>
      </Stack>

      <Dialog open={sendingBack} onClose={() => setSendingBack(false)} fullWidth maxWidth="sm">
        <DialogTitle>Send this report back</DialogTitle>
        <DialogContent>
          {/*
            The reason is required by the server and asked for here, because a report sent
            back without one leaves the supervisor guessing at what to change.
          */}
          <TextField
            autoFocus
            fullWidth
            multiline
            minRows={3}
            label="What needs fixing"
            value={sendBackReason}
            onChange={(e) => setSendBackReason(e.target.value)}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSendingBack(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!sendBackReason.trim() || busy}
            onClick={() => void sendBack(sendBackReason)}
          >
            Send back
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

/**
 * The first question the report asks, and the one that picks its shape.
 *
 * <p>Two buttons rather than a tick box, because a tick box has a default and this question
 * does not have one. A day the site did not work is not a lesser version of a working day — it
 * is a different document, and the answer decides which one is being written.</p>
 *
 * <p>What the records already hold is shown against a "no work" answer rather than used to
 * refuse it. A muster with men on it behind a day recorded as lost is a contradiction and not
 * necessarily an error — a watchman was there, a gang was paid to stand — and the supervisor
 * standing on the site is the one who knows which. The screen's job is to make sure he is not
 * saying it by accident.</p>
 */
function DayStatusCard({
  operational,
  cause,
  note,
  readOnly,
  contradictions,
  onOperationalChange,
  onCauseChange,
  onNoteChange,
}: {
  operational: boolean;
  cause: NonOperationalCause | '';
  note: string;
  readOnly: boolean;
  contradictions: string[];
  onOperationalChange: (operational: boolean) => void;
  onCauseChange: (cause: NonOperationalCause) => void;
  onNoteChange: (note: string) => void;
}) {
  /*
    A cause the picker no longer offers is still shown when the report already carries one.
    Reports were written with the retired causes, and a draft reopened against a picker that
    has no row for what it says would show an empty required field and read as though the
    supervisor had never answered.
  */
  const causeOptions =
    cause && !NON_OPERATIONAL_CAUSES.some((option) => option.value === cause)
      ? [...NON_OPERATIONAL_CAUSES, { value: cause, label: causeLabel(cause) }]
      : NON_OPERATIONAL_CAUSES;

  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Typography fontWeight={600} gutterBottom>
        Did the site work today?
      </Typography>
      <ToggleButtonGroup
        exclusive
        value={operational ? 'YES' : 'NO'}
        onChange={(_event, value) => {
          if (value !== null && !readOnly) {
            onOperationalChange(value === 'YES');
          }
        }}
        disabled={readOnly}
        sx={{ flexWrap: 'wrap' }}
      >
        <ToggleButton value="YES" sx={{ minHeight: 48, px: 3 }}>
          Yes, work went on
        </ToggleButton>
        <ToggleButton value="NO" sx={{ minHeight: 48, px: 3 }}>
          No work today
        </ToggleButton>
      </ToggleButtonGroup>

      {!operational && (
        <Stack spacing={2} sx={{ mt: 2 }}>
          <TextField
            select
            required
            label="Why the site did not work"
            value={cause}
            onChange={(e) => onCauseChange(e.target.value as NonOperationalCause)}
            disabled={readOnly}
            helperText="Counted across the month, so a claim for time can be made on it."
            sx={{ maxWidth: 320 }}
          >
            {causeOptions.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="What happened"
            required={causeNeedsNote(cause)}
            value={note}
            onChange={(e) => onNoteChange(e.target.value)}
            disabled={readOnly}
            multiline
            minRows={2}
            helperText={
              causeNeedsNote(cause)
                ? '"Other" says nothing on its own — write what stopped the work.'
                : 'Which road flooded, which letter stopped the work. Optional.'
            }
          />
          {contradictions.length > 0 && (
            <Alert severity="warning">
              <AlertTitle>The records say something happened here today</AlertTitle>
              {contradictions.join(', ')}. That may be right — a watchman is still paid, a
              lorry still arrives at a shut site — and the report will carry both. Check the
              muster and the bills if it is not.
            </Alert>
          )}
          <Typography variant="body2" color="text.secondary">
            Photographs below, if there are any worth keeping. There is no work to record and
            no observation to write: the cause is the report.
          </Typography>
        </Stack>
      )}
    </Paper>
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
        {report.siteOperational
          ? lines === 0
            ? 'No work lines in it yet.'
            : `${lines} work line(s) in it${report.workSummary ? ', and the day written up' : ''}.`
          : 'It records a day the site did not work.'}
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
  suggestable,
}: {
  data: DprPrefill;
  onUseSuggestion: (boqItemId: string) => void;
  /** Work lines are the engineer's, so only he is offered a shortcut into writing one. */
  suggestable: boolean;
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
          External labour on its own row of figures rather than folded into the ones above:
          those men have a wage behind their hours and these have none, so an "Present: 45"
          that included them would read as a wage bill spread over people who are not on our
          payroll.

          The men, though, do add. "How many men were on the site" is one question with one
          answer, and it is the head count alone that answers it — no hours and no money
          travel with the sum, which is why they are still shown apart above and beside it.
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
            <Figure
              label="Men on site"
              value={String(data.labour.presentCount + data.outsourcedLabour.headCount)}
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

      {suggestable && data.suggestedWorkItems.length > 0 && (
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
  boqItems,
  onLinesChange,
}: {
  lines: WorkLine[];
  boqItems: { id: string; itemNumber: string; description: string }[];
  onLinesChange: (lines: WorkLine[]) => void;
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
        A quantity claims work against the contract when this report is signed. Leave it blank
        to record work without claiming it.
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
    </Stack>
  );
}

/**
 * The plant that stood on the site today, on the supervisor's step.
 *
 * <p>It used to sit under the work claimed, which put it on the wrong side of the only line
 * this report really draws. A quantity is a claim against the contract and has to come from
 * the man who signs for it; a machine's running hours are a fact about the day, billed to
 * nobody, and the man who was standing next to it is the one who knows them.</p>
 */
/**
 * Plant on site, and — for whoever the report went to — what it is charged at.
 *
 * <p>Two people write this one table, and the two halves are disabled independently. The
 * machine and its hours are the supervisor's: he watched them happen. The rate is not his,
 * and a rate box on his screen is a number guessed at seven in the evening, so it is
 * read-only to him and to anybody looking at a report that has not been handed over.</p>
 */
function MachineryEditor({
  lines,
  readOnly,
  rateWritable,
  onChange,
}: {
  lines: MachineryLine[];
  readOnly: boolean;
  rateWritable: boolean;
  onChange: (lines: MachineryLine[]) => void;
}) {
  function patch(key: string, changes: Partial<MachineryLine>) {
    onChange(lines.map((line) => (line.key === key ? { ...line, ...changes } : line)));
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h2" sx={{ fontSize: '1.15rem', mt: 1 }}>
        Plant on site
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: -1 }}>
        {rateWritable
          ? 'Machines the site recorded today. The hours are theirs; what each is charged at is yours.'
          : 'Machines that were here today, and what they did. Nothing is claimed off these.'}
      </Typography>

      {lines.map((line) => (
        <Paper key={line.key} elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Machine"
              value={line.machineryName}
              onChange={(e) => patch(line.key, { machineryName: e.target.value })}
              disabled={readOnly}
              sx={{ minWidth: 200 }}
            />
            <TextField
              label="Hours run"
              type="number"
              value={line.hoursUsed}
              onChange={(e) => patch(line.key, { hoursUsed: Number(e.target.value) })}
              disabled={readOnly}
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
              onChange={(e) => patch(line.key, { idleHours: Number(e.target.value) })}
              disabled={readOnly}
              sx={{ maxWidth: 140 }}
            />
            <IconButton
              aria-label="Remove this machine"
              onClick={() => onChange(lines.filter((other) => other.key !== line.key))}
              disabled={readOnly}
              sx={{ width: 48, height: 48, alignSelf: 'center' }}
            >
              <DeleteOutlineIcon />
            </IconButton>
          </Stack>

          {/*
            The office's row underneath the site's. Shown to whoever may price it, and to
            anybody else only once there is a price to read — an empty rate box on a
            supervisor's screen is an invitation to guess.

            A machine typed on this screen and not yet saved has no row id to price, so the
            boxes wait for the save. The amount beside them is the server's own arithmetic
            read back, never this screen's: an hourly rate charges the hours run and not the
            hours stood, and two places computing that is two answers to one question.
          */}
          {(rateWritable || line.hireRate !== undefined) && (
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={2}
              sx={{ mt: 2, pt: 2, borderTop: 1, borderColor: 'divider' }}
              alignItems={{ sm: 'center' }}
            >
              <TextField
                label="Rate"
                type="number"
                value={line.hireRate ?? ''}
                onChange={(e) =>
                  patch(line.key, {
                    hireRate: e.target.value === '' ? undefined : Number(e.target.value),
                    // A rate needs a unit, and the hour is the one a day's report is read in.
                    // The server refuses a figure without one rather than assuming.
                    rateBasis: line.rateBasis ?? 'HOUR',
                  })
                }
                disabled={!rateWritable || !line.id}
                helperText={line.id ? undefined : 'Save the report first'}
                sx={{ maxWidth: 160 }}
              />
              <TextField
                select
                label="Per"
                value={line.rateBasis ?? 'HOUR'}
                onChange={(e) => patch(line.key, { rateBasis: e.target.value as RateBasis })}
                disabled={!rateWritable || !line.id}
                sx={{ minWidth: 120 }}
              >
                <MenuItem value="HOUR">Hour run</MenuItem>
                <MenuItem value="DAY">Day</MenuItem>
              </TextField>
              <Figure
                label="Amount"
                value={line.hireAmount === undefined ? '—' : formatAmount(line.hireAmount)}
              />
            </Stack>
          )}
        </Paper>
      ))}

      <Button
        startIcon={<AddIcon />}
        disabled={readOnly}
        onClick={() =>
          onChange([
            ...lines,
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
