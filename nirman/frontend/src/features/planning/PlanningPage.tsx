import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useProject } from '../admin/api';
import { useBaselinePlan, useGeneratePlan, useSavePlan, useWorkTypeProfiles } from './api';
import type { CashView, LabourView, MaterialView, PhaseView, Plan } from './types';

const INR = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

/** Lakh rather than raw rupees: a nine-digit figure is unreadable and never compared correctly. */
function lakh(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '—';
  }
  return `₹${INR.format(Math.round(value / 100000))} L`;
}

/**
 * Planning and execution strategy for one project.
 *
 * <p>The screen is built around one number. The peak funding requirement — the deepest point of
 * the cash trough — is what a contractor actually needs to know before starting, and it is not
 * the first month's cost: money keeps going out through the whole payment lag. Everything else
 * on the page explains how that number was arrived at.</p>
 *
 * <p>Generating is free and stores nothing, so the controls at the top can be moved as often as
 * the user likes. Saving keeps a draft; baselining freezes it and dates the schedule of
 * quantities, and it is the only irreversible act here.</p>
 */
export function PlanningPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const project = useProject(projectId);
  const profiles = useWorkTypeProfiles();
  const generate = useGeneratePlan(projectId);
  const save = useSavePlan(projectId);
  const baseline = useBaselinePlan(projectId);

  const [profileId, setProfileId] = useState('');
  const [quotedPercent, setQuotedPercent] = useState('0');
  const [paymentLagDays, setPaymentLagDays] = useState('45');
  const [plan, setPlan] = useState<Plan | null>(null);
  const [saved, setSaved] = useState<Plan | null>(null);
  const [error, setError] = useState<string | null>(null);

  const input = useMemo(
    () => ({
      workTypeProfileId: profileId || undefined,
      quotedPercent: Number(quotedPercent) || 0,
      paymentLagDays: Number(paymentLagDays) || 45,
    }),
    [profileId, quotedPercent, paymentLagDays],
  );

  // The first plan is generated on arrival so the page opens with an answer rather than a form.
  useEffect(() => {
    if (!projectId || plan) {
      return;
    }
    generate.mutate(input, { onSuccess: setPlan, onError: (e) => setError(apiErrorDetail(e)) });
  }, [projectId, plan, input, generate]);

  const run = () => {
    setError(null);
    setSaved(null);
    generate.mutate(input, { onSuccess: setPlan, onError: (e) => setError(apiErrorDetail(e)) });
  };

  const keep = () => {
    setError(null);
    save.mutate(
      { ...input, name: `Plan for ${project.data?.name ?? 'project'}` },
      { onSuccess: (result) => { setSaved(result); setPlan(result); },
        onError: (e) => setError(apiErrorDetail(e)) },
    );
  };

  const freeze = () => {
    if (!saved?.id) {
      return;
    }
    setError(null);
    baseline.mutate(saved.id, {
      onSuccess: (result) => { setSaved(result); setPlan(result); },
      onError: (e) => setError(apiErrorDetail(e)),
    });
  };

  const blocking = plan?.notes.filter((n) => n.severity === 'BLOCKING') ?? [];
  const warnings = plan?.notes.filter((n) => n.severity === 'WARNING') ?? [];
  const assumptions = plan?.notes.filter((n) => n.kind === 'ASSUMPTION') ?? [];

  return (
    <Box sx={{ p: { xs: 2, md: 3 } }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
        <IconButton component={Link} to={`/projects/${projectId}`} aria-label="Back to project">
          <ArrowBackIcon />
        </IconButton>
        <Box>
          <Typography variant="h5">Planning and execution strategy</Typography>
          <Typography variant="body2" color="text.secondary">
            {project.data?.name ?? 'Loading…'}
          </Typography>
        </Box>
      </Stack>

      <Paper sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="flex-start">
          <TextField
            select
            label="Kind of work"
            size="small"
            value={profileId}
            onChange={(e) => setProfileId(e.target.value)}
            sx={{ minWidth: 260 }}
            helperText="A road is not a school; this picks the sequencing"
          >
            <MenuItem value="">Use the default</MenuItem>
            {(profiles.data ?? []).map((p) => (
              <MenuItem key={p.id} value={p.id}>
                {p.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Quoted %"
            size="small"
            type="number"
            value={quotedPercent}
            onChange={(e) => setQuotedPercent(e.target.value)}
            sx={{ width: 150 }}
            helperText="Above (+) or below (−) the estimate"
          />
          <TextField
            label="Payment lag (days)"
            size="small"
            type="number"
            value={paymentLagDays}
            onChange={(e) => setPaymentLagDays(e.target.value)}
            sx={{ width: 170 }}
            helperText="What your division actually takes"
          />
          <Stack direction="row" spacing={1} sx={{ pt: 1 }}>
            <Button variant="contained" onClick={run} disabled={generate.isPending}>
              {generate.isPending ? 'Working…' : 'Generate'}
            </Button>
            <Button variant="outlined" onClick={keep} disabled={!plan || save.isPending}>
              Save draft
            </Button>
            <Tooltip title="Freezes the figures this project is measured against, and dates the BOQ">
              <span>
                <Button
                  variant="outlined"
                  color="secondary"
                  onClick={freeze}
                  disabled={!saved?.id || saved.baselined || baseline.isPending}
                >
                  {saved?.baselined ? 'Baselined' : 'Make baseline'}
                </Button>
              </span>
            </Tooltip>
          </Stack>
        </Stack>
      </Paper>

      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {generate.isPending && !plan && (
        <Stack alignItems="center" sx={{ py: 6 }}>
          <CircularProgress />
        </Stack>
      )}

      {plan && (
        <>
          {blocking.map((note) => (
            <Alert severity="error" sx={{ mb: 2 }} key={note.message}>
              <AlertTitle>This programme does not work</AlertTitle>
              {note.message}
            </Alert>
          ))}
          {warnings.map((note) => (
            <Alert severity="warning" sx={{ mb: 2 }} key={note.message}>
              {note.message}
            </Alert>
          ))}

          <Paper sx={{ p: 2, mb: 2 }}>
            <Typography variant="overline" color="text.secondary">
              What this job needs
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={4} sx={{ mt: 1 }}>
              <Headline
                label="Money to find"
                value={lakh(plan.workingCapital.peakFundingRequired)}
                note={
                  plan.workingCapital.peakMonth
                    ? `deepest in ${plan.workingCapital.peakMonth}`
                    : 'the trough never goes negative'
                }
              />
              <Headline
                label="Before day one"
                value={lakh(plan.workingCapital.moneyBeforeDayOne)}
                note="EMD, guarantee commission, site setup"
              />
              <Headline
                label="Turns positive"
                value={plan.workingCapital.breakEvenMonth ?? 'not within the plan'}
                note="cumulative receipts overtake spend"
              />
              <Headline
                label="Retention locked"
                value={lakh(plan.workingCapital.totalRetentionHeld)}
                note={
                  plan.workingCapital.retentionReleasedOn
                    ? `released ${plan.workingCapital.retentionReleasedOn}`
                    : 'released after the defect liability period'
                }
              />
            </Stack>
          </Paper>

          <Section title="Phases" subtitle="The tender's own milestones, and what this plan reaches by each">
            <RecordTable<PhaseView>
              rows={plan.phases}
              rowKey={(row) => String(row.sequence)}
              columns={phaseColumns}
              empty="This tender stated no milestones."
            />
          </Section>

          <Section title="Cash flow" subtitle="Net receipts are what fund the next phase, not the gross bill">
            <RecordTable<CashView>
              rows={plan.cash}
              rowKey={(row) => row.month}
              columns={cashColumns}
              empty="No months were costed."
            />
          </Section>

          <Section title="Labour" subtitle="Head count is man-days over the month's working days">
            <RecordTable<LabourView>
              rows={plan.labour}
              rowKey={(row) => `${row.month}-${row.skillCode}`}
              columns={labourColumns}
              empty="No productivity norm matched this schedule."
            />
          </Section>

          <Section
            title="Material"
            subtitle="Required is what a month consumes; order-by is what has to leave the office for it to happen"
          >
            <RecordTable<MaterialView>
              rows={plan.material}
              rowKey={(row) => `${row.month}-${row.materialCode}`}
              columns={materialColumns}
              empty="No consumption norm matched this schedule."
            />
          </Section>

          {assumptions.length > 0 && (
            <Paper sx={{ p: 2, mb: 2 }}>
              <Typography variant="overline" color="text.secondary">
                What this plan assumed
              </Typography>
              <Divider sx={{ my: 1 }} />
              <Stack spacing={1}>
                {assumptions.map((note) => (
                  <Box key={note.subject ?? note.message}>
                    <Typography variant="body2">
                      <strong>{note.subject}</strong>
                      {note.value ? ` — ${note.value}` : ''}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {note.message}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            </Paper>
          )}
        </>
      )}
    </Box>
  );
}

function Headline({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h5">{value}</Typography>
      <Typography variant="caption" color="text.secondary">
        {note}
      </Typography>
    </Box>
  );
}

function Section({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: React.ReactNode;
}) {
  return (
    <Paper sx={{ p: 2, mb: 2 }}>
      <Typography variant="subtitle1">{title}</Typography>
      <Typography variant="caption" color="text.secondary">
        {subtitle}
      </Typography>
      <Box sx={{ mt: 1 }}>{children}</Box>
    </Paper>
  );
}

const phaseColumns: RecordColumn<PhaseView>[] = [
  { key: 'sequence', header: '#', cell: (row) => row.sequence },
  {
    key: 'description',
    header: 'Milestone',
    cell: (row) => (
      <Stack direction="row" spacing={1} alignItems="center">
        <Typography variant="body2" sx={{ maxWidth: 460 }}>
          {row.description ?? '—'}
        </Typography>
        {row.physical && <Chip size="small" label="physical" />}
      </Stack>
    ),
  },
  { key: 'endDate', header: 'Due', cell: (row) => row.endDate },
  {
    key: 'targetPercent',
    header: 'Required',
    align: 'right',
    cell: (row) => (row.targetPercent === null ? '—' : `${row.targetPercent}%`),
  },
  {
    key: 'plannedPercent',
    header: 'Planned',
    align: 'right',
    cell: (row) => (
      <Typography variant="body2" color={row.onTarget ? 'text.primary' : 'error.main'}>
        {row.plannedPercent === null ? '—' : `${row.plannedPercent}%`}
      </Typography>
    ),
  },
  {
    key: 'withheldPercent',
    header: 'At risk',
    align: 'right',
    cell: (row) => (row.withheldPercent === null ? '—' : `${row.withheldPercent}%`),
  },
];

const cashColumns: RecordColumn<CashView>[] = [
  { key: 'month', header: 'Month', cell: (row) => row.month },
  { key: 'totalOutflow', header: 'Out', align: 'right', cell: (row) => formatAmount(row.totalOutflow) },
  { key: 'grossBilled', header: 'Billed', align: 'right', cell: (row) => formatAmount(row.grossBilled) },
  { key: 'deductions', header: 'Deducted', align: 'right', cell: (row) => formatAmount(row.deductions) },
  { key: 'netReceived', header: 'Received', align: 'right', cell: (row) => formatAmount(row.netReceived) },
  {
    key: 'cumulative',
    header: 'Cumulative',
    align: 'right',
    cell: (row) => (
      <Typography variant="body2" color={row.cumulative < 0 ? 'error.main' : 'success.main'}>
        {formatAmount(row.cumulative)}
      </Typography>
    ),
  },
];

const labourColumns: RecordColumn<LabourView>[] = [
  { key: 'month', header: 'Month', cell: (row) => row.month },
  { key: 'skillCode', header: 'Trade', cell: (row) => row.skillCode },
  { key: 'headCount', header: 'Head count', align: 'right', cell: (row) => row.headCount },
  { key: 'manDays', header: 'Man-days', align: 'right', cell: (row) => row.manDays },
  {
    key: 'cost',
    header: 'Cost',
    align: 'right',
    cell: (row) => (row.cost === null ? '—' : formatAmount(row.cost)),
  },
];

const materialColumns: RecordColumn<MaterialView>[] = [
  { key: 'month', header: 'Month', cell: (row) => row.month },
  { key: 'materialName', header: 'Material', cell: (row) => row.materialName ?? row.materialCode },
  { key: 'requiredQty', header: 'Required', align: 'right', cell: (row) => row.requiredQty },
  { key: 'procureQty', header: 'To order', align: 'right', cell: (row) => row.procureQty },
  { key: 'orderByDate', header: 'Order by', cell: (row) => row.orderByDate ?? '—' },
];
