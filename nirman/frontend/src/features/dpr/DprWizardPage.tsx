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
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatHours, formatQuantity } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { StatusChip } from '../../shared/StatusChip';
import {
  useBoqItems,
  useCreateDpr,
  usePrefill,
  useSites,
  useSubmitDpr,
  useUpdateDpr,
} from './api';
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
    key: 'contractor',
    header: 'Contractor',
    cell: (line) => line.labourContractorName ?? 'Direct',
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

const WEATHER: { value: Weather; label: string }[] = [
  { value: 'CLEAR', label: 'Clear' },
  { value: 'CLOUDY', label: 'Cloudy' },
  { value: 'RAIN', label: 'Rain' },
  { value: 'HEAVY_RAIN', label: 'Heavy rain' },
  { value: 'FOG', label: 'Fog' },
  { value: 'SNOW', label: 'Snow' },
  { value: 'STORM', label: 'Storm' },
];

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
  const [siteId, setSiteId] = useState('');
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

  const sites = useSites();
  const prefill = usePrefill(siteId || undefined, reportDate);
  const boqItems = useBoqItems(siteId || undefined);
  const create = useCreateDpr();
  const update = useUpdateDpr();
  const submit = useSubmitDpr();

  useEffect(() => {
    if (!siteId && sites.data?.length) {
      setSiteId(sites.data[0]!.id);
    }
  }, [sites.data, siteId]);

  // A new day is a new report. Anything typed against the old one would otherwise be filed
  // under a date it did not happen on.
  useEffect(() => {
    setSaved(null);
    setStep(0);
  }, [siteId, reportDate]);

  const alreadyExists = Boolean(prefill.data?.reportExists) && !saved;

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
    return created;
  }

  async function saveAndSubmit() {
    const draft = await saveDraft();
    const sent = await submit.mutateAsync(draft.id);
    setSaved(sent);
  }

  const busy = create.isPending || update.isPending || submit.isPending;
  const error = create.error ?? update.error ?? submit.error;

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

      {alreadyExists && (
        <Alert severity="info">
          A report already covers {reportDate} at this site. Open it from the reports list
          rather than starting a second one.
        </Alert>
      )}

      {saved && (
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
        <PrefillStep data={prefill.data} onUseSuggestion={addSuggestion} />
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
              disabled={alreadyExists}
            >
              Next
            </Button>
          )}
          {step === STEPS.length - 1 && (
            <>
              <Button
                variant="outlined"
                onClick={() => void saveDraft()}
                disabled={busy || alreadyExists || !siteId}
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
                disabled={busy || alreadyExists || !siteId}
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
        {data.labour.lines.length > 0 && (
          <RecordTable
            nested
            columns={LABOUR_COLUMNS}
            rows={data.labour.lines}
            rowKey={(line) =>
              `${line.skillCategoryId ?? 'none'}-${line.labourContractorId ?? 'direct'}`
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
