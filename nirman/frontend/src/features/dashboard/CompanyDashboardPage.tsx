import {
  Alert,
  Box,
  CircularProgress,
  LinearProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { monthToDate, useCompanyDashboard } from './api';
import { CostTrendChart } from './CostTrendChart';
import { MaterialPositionCard } from './MaterialPositionCard';
import { PeriodPicker } from './PeriodPicker';
import type { ProjectRow } from './types';

/** Every project on the books over the period, as a table on a desk and as cards in a hand. */
const PROJECT_COLUMNS: RecordColumn<ProjectRow>[] = [
  {
    key: 'project',
    header: 'Project',
    card: 'title',
    cell: (project) => (
      <>
        <Typography fontWeight={600}>{project.projectName}</Typography>
        <Typography variant="body2" color="text.secondary">
          {project.projectCode} · {project.siteCount} site(s)
        </Typography>
      </>
    ),
  },
  {
    key: 'quoted',
    header: 'Quoted value',
    align: 'right',
    /*
      What the row pays at the rate bid, which is what can be billed. An em dash on a project
      nobody has quoted, never the contract value: the department's estimate and the firm's
      quote in one column is a column that adds up to nothing anybody is owed.
    */
    cell: (project) => (project.quotedCost == null ? '—' : formatAmount(project.quotedCost)),
  },
  {
    key: 'cost',
    header: 'Cost this period',
    align: 'right',
    cell: (project) => formatAmount(project.costIncurred),
  },
  {
    key: 'budget',
    header: 'Budget used',
    align: 'right',
    cell: (project) =>
      /*
        No budget is not nought per cent used — it is a question nobody can answer, and a
        bar drawn at zero would answer it wrongly.
      */
      project.percentBudgetUsed == null
        ? 'No budget set'
        : `${project.percentBudgetUsed.toFixed(1)}%`,
  },
  {
    key: 'work',
    header: 'Work done',
    align: 'right',
    cell: (project) => (
      <>
        {project.percentWorkDone == null ? '—' : `${project.percentWorkDone.toFixed(1)}%`}
        {project.percentWorkDone != null && (
          <LinearProgress
            variant="determinate"
            value={Math.min(project.percentWorkDone, 100)}
            sx={{ mt: 0.5, height: 6, borderRadius: 3 }}
          />
        )}
      </>
    ),
  },
];

/**
 * The firm over a period.
 *
 * <p>The one thing this screen exists to get right is that <b>cost and cash are never the same
 * tile</b>. Cost incurred is labour plus material consumed plus the expenses that are neither a
 * material purchase nor a wage payment, and it is the only figure that may be set against a
 * budget. Total booked is what left the books. Putting them side by side with the difference
 * spelled out is the only way a reader ends up adding the right one to something.</p>
 */
export function CompanyDashboardPage() {
  const initial = monthToDate();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);

  const dashboard = useCompanyDashboard(from, to);

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Company</Typography>

      <PeriodPicker from={from} to={to} onFromChange={setFrom} onToChange={setTo} />

      {dashboard.isLoading && <CircularProgress />}
      {dashboard.isError && <Alert severity="error">{apiErrorDetail(dashboard.error)}</Alert>}

      {dashboard.data && (
        <>
          {/* Same as the site dashboard: MUI's Grid gutters are a negative margin, and the
              Stack around this screen zeroes its children's margins, so the tiles sat 16px
              right of every card under them. */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', sm: 'repeat(4, minmax(0, 1fr))' },
              gap: 2,
            }}
          >
            <Tile label="Active projects" value={String(dashboard.data.activeProjects)} />
            <Tile label="Sites" value={String(dashboard.data.activeSites)} />
            {/*
              What the work pays at the rates bid, not what the department estimated. This is
              the amount that can be invoiced, and it is the question the tile is read for; the
              contract value is on each project's own page beside the quote that moved it.
            */}
            <Tile
              label="Quoted value"
              value={formatAmount(dashboard.data.quotedValue)}
              note={unpricedNote(dashboard.data.unpricedProjects)}
            />
            <Tile label="Budget" value={formatAmount(dashboard.data.budgetAmount)} />
          </Box>

          <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
            <Typography fontWeight={600} gutterBottom>
              What the period cost
            </Typography>
            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
              <Figure label="Labour" value={formatAmount(dashboard.data.labourCost)} />
              <Figure
                label="Material consumed"
                value={formatAmount(dashboard.data.materialConsumed)}
              />
              <Figure label="Other" value={formatAmount(dashboard.data.otherCost)} />
              <Figure
                label="Cost incurred"
                value={formatAmount(dashboard.data.costIncurred)}
                strong
              />
            </Stack>
            {/*
              Cash below cost and visibly apart from it. The two figures differ by material
              purchases and wage disbursements, both costed elsewhere, and a reader who cannot
              see the difference will add the wrong one to something.
            */}
            <Typography fontWeight={600} sx={{ mt: 2 }} gutterBottom>
              What left the books
            </Typography>
            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
              <Figure label="Total booked" value={formatAmount(dashboard.data.totalBooked)} />
              <Figure label="Still owed" value={formatAmount(dashboard.data.payable)} />
            </Stack>
            <Alert severity="info" sx={{ mt: 2 }}>
              {dashboard.data.caveat}
            </Alert>
          </Paper>

          <MaterialPositionCard material={dashboard.data.material} />

          <CostTrendChart trend={dashboard.data.trend} />

          <RecordTable
            columns={PROJECT_COLUMNS}
            rows={dashboard.data.projects}
            rowKey={(project) => project.projectId}
            ariaLabel="Projects over the period"
          />

          <Typography variant="body2">
            <Link to="/dashboard/quality">What is missing from the records</Link>
          </Typography>
        </>
      )}
    </Stack>
  );
}

function Tile({
  label,
  value,
  note,
}: {
  label: string;
  value: string;
  note?: string | undefined;
}) {
  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider', height: '100%' }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h3">{value}</Typography>
      {note && (
        <Typography variant="caption" color="text.secondary">
          {note}
        </Typography>
      )}
    </Paper>
  );
}

/**
 * What the quoted total is short by, said out loud.
 *
 * <p>A headline missing four projects looks exactly like a complete one, and a firm that never
 * fills the quote box would read ₹0 as the truth about its order book. The projects list makes
 * the same statement under its portfolio strip; this is the tile-sized version of it.</p>
 */
function unpricedNote(unpriced: number): string | undefined {
  if (unpriced === 0) return undefined;
  return unpriced === 1
    ? '1 project carries no quote and is not in this total'
    : `${unpriced} projects carry no quote and are not in this total`;
}

function Figure({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography fontWeight={strong ? 700 : 600} sx={{ fontSize: strong ? '1.25rem' : '1.1rem' }}>
        {value}
      </Typography>
    </Box>
  );
}
