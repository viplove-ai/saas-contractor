import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import InsightsIcon from '@mui/icons-material/Insights';
import DescriptionIcon from '@mui/icons-material/Description';
import PhotoLibraryIcon from '@mui/icons-material/PhotoLibrary';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatQuantity } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useBoqItems, useNitDocument, useProject, useUnits } from './api';
import type { BoqItem, NitFields, ProjectStatus } from './types';

const STATUS_COLOR: Record<ProjectStatus, 'success' | 'default' | 'warning' | 'error'> = {
  ACTIVE: 'success',
  PLANNED: 'default',
  ON_HOLD: 'warning',
  COMPLETED: 'default',
  CLOSED: 'error',
};

/** Empty string included so an unset nature reads as a blank field, not as a missing key. */
const WORK_NATURE_LABEL: Record<string, string> = {
  CONSTRUCTION: 'Construction — guarantee released a year after completion',
  MAINTENANCE: "Maintenance — guarantee released six months after the department's letter",
};

const STATUS_LABEL: Record<ProjectStatus, string> = {
  ACTIVE: 'Active',
  PLANNED: 'Planned',
  ON_HOLD: 'On hold',
  COMPLETED: 'Completed',
  CLOSED: 'Closed',
};

/**
 * Everything known about one project: the contract it runs under, the tender it was won
 * from, and the schedule of work it was awarded.
 *
 * <p>The three are on one page rather than behind tabs because they answer one question
 * between them — what did we agree to build, for how much, under what terms. Splitting them
 * up would mean checking a rate against the DSR year involved navigating.</p>
 */
export function ProjectDetailPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const project = useProject(projectId);
  const boq = useBoqItems(projectId);
  const nit = useNitDocument(projectId);
  const units = useUnits();
  const [filter, setFilter] = useState('');

  const unitCode = useMemo(() => {
    const byId = new Map((units.data ?? []).map((unit) => [unit.id, unit.code]));
    return (id: string) => byId.get(id) ?? '';
  }, [units.data]);

  // Memoised so the derived lists below do not see a new array identity every render.
  const lines = useMemo(() => boq.data ?? [], [boq.data]);
  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return lines;
    }
    return lines.filter(
      (line) =>
        line.itemNumber.toLowerCase().includes(needle) ||
        line.description.toLowerCase().includes(needle) ||
        (line.category ?? '').toLowerCase().includes(needle),
    );
  }, [lines, filter]);

  /*
    Built here rather than at module scope because the quantity column needs `unitCode`, which
    only exists once the unit master data has loaded.
  */
  const boqColumns = useMemo<RecordColumn<BoqItem>[]>(
    () => [
      {
        key: 'item',
        header: 'Item',
        sx: { whiteSpace: 'nowrap', fontFamily: 'monospace' },
        cell: (line) => line.itemNumber,
      },
      {
        key: 'description',
        header: 'Description',
        card: 'title',
        sx: { minWidth: 260 },
        cell: (line) => (
          <>
            <Typography variant="body2">{line.description}</Typography>
            <Stack direction="row" spacing={0.5} sx={{ mt: 0.5 }}>
              {line.workPart && (
                <Chip size="small" variant="outlined" label={line.workPart} sx={{ height: 18 }} />
              )}
              {line.synthetic && (
                <Tooltip title="A gap between the extracted lines and the tender's stated total. Nothing can be charged against it.">
                  <Chip size="small" color="warning" label="reconciliation" sx={{ height: 18 }} />
                </Tooltip>
              )}
            </Stack>
          </>
        ),
      },
      {
        key: 'category',
        header: 'Category',
        sx: { whiteSpace: 'nowrap' },
        cell: (line) => (
          <Typography variant="caption" color="text.secondary">
            {line.category ?? '—'}
          </Typography>
        ),
      },
      {
        key: 'quantity',
        header: 'Quantity',
        align: 'right',
        sx: { whiteSpace: 'nowrap' },
        cell: (line) => formatQuantity(line.contractQuantity, unitCode(line.unitId)),
      },
      {
        key: 'rate',
        header: 'Rate',
        align: 'right',
        sx: { whiteSpace: 'nowrap' },
        cell: (line) => formatAmount(line.contractRate),
      },
      {
        key: 'amount',
        header: 'Amount',
        align: 'right',
        sx: { whiteSpace: 'nowrap' },
        cell: (line) => formatAmount(line.contractAmount),
      },
    ],
    [unitCode],
  );

  const totals = useMemo(() => {
    const value = lines.reduce((sum, line) => sum + line.contractAmount, 0);
    const reconciliation = lines
      .filter((line) => line.synthetic)
      .reduce((sum, line) => sum + line.contractAmount, 0);
    return { value, reconciliation };
  }, [lines]);

  if (project.isLoading) {
    return (
      <Stack alignItems="center" sx={{ py: 8 }}>
        <CircularProgress />
      </Stack>
    );
  }

  if (project.isError || !project.data) {
    return (
      <Stack spacing={2}>
        <BackLink />
        <Alert severity="error">{apiErrorDetail(project.error)}</Alert>
      </Stack>
    );
  }

  const p = project.data;

  return (
    <Stack spacing={3}>
      {/*
        Column on a phone. The button's label is six words long and there is no room beside a
        project name on a 360px screen — squeezed into a row it wraps to three lines and pushes
        the heading off the left. Below the heading it is a full-width tap target instead.
      */}
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1}
        alignItems={{ xs: 'stretch', sm: 'flex-start' }}
      >
        <Stack direction="row" spacing={1} alignItems="flex-start" sx={{ minWidth: 0, flexGrow: 1 }}>
        <Tooltip title="Back to all projects">
          <IconButton component={Link} to="/projects" aria-label="Back to all projects" edge="start">
            <ArrowBackIcon />
          </IconButton>
        </Tooltip>
        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
            <Typography variant="h5" component="h1">
              {p.code}
            </Typography>
            <Chip
              size="small"
              color={STATUS_COLOR[p.status]}
              label={STATUS_LABEL[p.status]}
            />
          </Stack>
          <Typography color="text.secondary">{p.name}</Typography>
        </Box>
        </Stack>
        {/*
          Both sit beside the contract rather than under the BOQ, because both are about the
          whole job — the time allowed, the money to find, the money already found and lodged —
          and not about any one priced line.
        */}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ flexShrink: 0 }}>
          <Button
            component={Link}
            to={`/projects/${p.id}/planning`}
            variant="outlined"
            startIcon={<InsightsIcon />}
            sx={{ whiteSpace: 'nowrap' }}
          >
            Planning &amp; execution
          </Button>
          <Button
            component={Link}
            to={`/projects/${p.id}/securities`}
            variant="outlined"
            startIcon={<AccountBalanceIcon />}
            sx={{ whiteSpace: 'nowrap' }}
          >
            Deposits &amp; guarantees
          </Button>
          {/*
            Every picture of the work, off every daily report of every site — the one place
            the whole job can be looked at rather than read about.
          */}
          <Button
            component={Link}
            to={`/projects/${p.id}/gallery`}
            variant="outlined"
            startIcon={<PhotoLibraryIcon />}
            sx={{ whiteSpace: 'nowrap' }}
          >
            Photographs
          </Button>
        </Stack>
      </Stack>

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle2" gutterBottom>
          Contract
        </Typography>
        <FieldGrid
          fields={[
            ['Client department', p.clientDepartment],
            ['NIT number', p.nitNumber],
            ['Agreement no.', p.agreementNo],
            ['Tender reference', p.tenderReference],
            /*
              The quote leads and the estimate follows it, because the quoted value is what
              the work pays and the contract value is only what the department put to tender.
              Both stay on the page: the guarantee arithmetic stands on whichever is higher,
              so a screen carrying one of them cannot answer what a deposit was worked out on.
            */
            ['Quoted value', p.quotedCost == null ? null : formatAmount(p.quotedCost)],
            [
              'Estimate put to tender',
              p.contractValue == null ? null : formatAmount(p.contractValue),
            ],
            ['Budget', p.budgetAmount == null ? null : formatAmount(p.budgetAmount)],
            ['Start date', p.startDate],
            ['Expected completion', p.expectedCompletionDate],
            ['Actual completion', p.actualCompletionDate],
          ]}
        />
        {/*
          The contract's own calendar, kept apart from the figures above. Every deposit release
          date is worked out from these together with the start and completion dates, so a
          blank here is the reason a guarantee on the treasury screen shows no date rather than
          a wrong one.
        */}
        <Divider sx={{ my: 2 }} />
        <Typography variant="overline" color="text.secondary">
          Contract calendar
        </Typography>
        <FieldGrid
          fields={[
            ['Nature of work', WORK_NATURE_LABEL[p.workNature ?? ''] ?? null],
            [
              'Defect liability',
              p.defectLiabilityMonths == null ? null : `${p.defectLiabilityMonths} months`,
            ],
          ]}
        />
        {p.description && (
          <>
            <Divider sx={{ my: 2 }} />
            <Typography variant="body2" color="text.secondary">
              {p.description}
            </Typography>
          </>
        )}
      </Paper>

      {nit.data && <TenderCard fields={nit.data.fields} fileName={nit.data.fileName}
        pageCount={nit.data.pageCount} warnings={nit.data.warnings} />}

      <Paper variant="outlined">
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          spacing={2}
          alignItems={{ sm: 'center' }}
          justifyContent="space-between"
          sx={{ p: 2 }}
        >
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="subtitle2">Schedule of quantities</Typography>
            <Typography variant="body2" color="text.secondary">
              {lines.length === 0
                ? 'No work items recorded for this project yet.'
                : `${lines.length} lines, ${formatAmount(totals.value)}`}
            </Typography>
          </Box>
          {lines.length > 0 && (
            <TextField
              size="small"
              label="Find an item"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              // Fixed rather than flexing: left to grow it swallows the row and folds the
              // heading beside it onto three lines.
              sx={{ width: { xs: '100%', sm: 260 }, flexShrink: 0 }}
            />
          )}
        </Stack>

        {boq.isLoading && (
          <Stack alignItems="center" sx={{ py: 4 }}>
            <CircularProgress size={24} />
          </Stack>
        )}

        {boq.isError && (
          <Alert severity="error" sx={{ m: 2 }}>
            {apiErrorDetail(boq.error)}
          </Alert>
        )}

        {lines.length > 0 && (
          <>
            {/* The list scrolls inside its own box; the page itself never scrolls sideways. */}
            <Box sx={{ px: { xs: 2, sm: 0 } }}>
              <RecordTable
                nested
                maxHeight={560}
                columns={boqColumns}
                rows={visible}
                rowKey={(line) => line.id}
                // Reconciliation placeholders are muted for what they are.
                rowSx={(line) => (line.synthetic ? { bgcolor: 'action.hover' } : {})}
                ariaLabel="Schedule of quantities"
              />
            </Box>
            {visible.length === 0 && (
              <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
                No item matches “{filter}”.
              </Typography>
            )}
            <Stack
              direction="row"
              justifyContent="space-between"
              sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}
            >
              <Typography variant="body2" color="text.secondary">
                {filter ? `${visible.length} of ${lines.length} lines` : `${lines.length} lines`}
                {totals.reconciliation > 0 &&
                  ` · ${formatAmount(totals.reconciliation)} unallocated`}
              </Typography>
              <Typography variant="body2" fontWeight={600}>
                {formatAmount(totals.value)}
              </Typography>
            </Stack>
          </>
        )}
      </Paper>
    </Stack>
  );
}

/** What the tender notice said, beyond what the project record carries. */
function TenderCard({
  fields,
  fileName,
  pageCount,
  warnings,
}: {
  fields: NitFields;
  fileName: string;
  pageCount: number;
  warnings: string[];
}) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <DescriptionIcon fontSize="small" color="action" />
        <Typography variant="subtitle2">Tender notice</Typography>
        <Typography variant="caption" color="text.secondary">
          {fileName} · {pageCount} pages
        </Typography>
      </Stack>

      <FieldGrid
        fields={[
          /*
            The notice's own number leads, before anything it said. The Contract card above
            carries the project's, which somebody may since have corrected or shortened; this
            one is what the reader found on the paper, and where the two differ the difference
            is worth seeing rather than hiding behind whichever was typed last.
          */
          ['NIT number', fields.nitNo],
          ['Work name', fields.workName],
          ['Location', fields.location],
          ['Division', fields.division],
          ['Bid type', fields.bidType],
          ['Estimated cost', money(fields.estimatedCost)],
          ['Civil component', money(fields.civilEstimatedCost)],
          ['Electrical component', money(fields.electricalEstimatedCost)],
          ['Earnest money', money(fields.emdAmount)],
          ['Completion period', fields.completionPeriod],
          ['Submission closes', dateTime(fields.submissionClosing)],
          ['Bid opening', dateTime(fields.bidOpening)],
          ['Performance guarantee', percent(fields.performanceGuaranteePercent)],
          ['Security deposit', percent(fields.securityDepositPercent)],
          ['Civil DSR', schedule(fields.civilDsrYear, fields.civilCostIndexPercent)],
          ['Electrical DSR', schedule(fields.electricalDsrYear, fields.electricalCostIndexPercent)],
        ]}
      />

      {(fields.contractorEligibility || fields.similarWorkCriteria) && (
        <>
          <Divider sx={{ my: 2 }} />
          <Stack spacing={1.5}>
            {fields.contractorEligibility && (
              <LongField label="Eligibility" value={fields.contractorEligibility} />
            )}
            {fields.similarWorkCriteria && (
              <LongField label="Similar work" value={fields.similarWorkCriteria} />
            )}
          </Stack>
        </>
      )}

      {warnings.length > 0 && (
        <Alert severity="warning" sx={{ mt: 2 }}>
          <AlertTitle>Noted when this tender was read</AlertTitle>
          <Stack component="ul" sx={{ pl: 2, m: 0 }} spacing={0.5}>
            {warnings.map((warning) => (
              <Typography component="li" variant="body2" key={warning}>
                {warning}
              </Typography>
            ))}
          </Stack>
        </Alert>
      )}
    </Paper>
  );
}

/**
 * Label-and-value pairs. Fields the tender did not state are dropped rather than shown
 * as blanks — a page of dashes reads as missing data when it is just an ordinary notice.
 */
function FieldGrid({ fields }: { fields: [string, string | null | undefined][] }) {
  const present = fields.filter(([, value]) => value != null && value !== '');
  if (present.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Nothing recorded yet.
      </Typography>
    );
  }
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', md: '1fr 1fr 1fr' },
        gap: 2,
      }}
    >
      {present.map(([label, value]) => (
        <Box key={label} sx={{ minWidth: 0 }}>
          <Typography variant="caption" color="text.secondary" display="block">
            {label}
          </Typography>
          <Typography variant="body2">{value}</Typography>
        </Box>
      ))}
    </Box>
  );
}

function LongField({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" display="block">
        {label}
      </Typography>
      <Typography variant="body2">{value}</Typography>
    </Box>
  );
}

function BackLink() {
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <IconButton component={Link} to="/projects" aria-label="Back to all projects" edge="start">
        <ArrowBackIcon />
      </IconButton>
      <Typography variant="body2">All projects</Typography>
    </Stack>
  );
}

function money(value: number | null): string | null {
  return value == null ? null : formatAmount(value);
}

function percent(value: number | null): string | null {
  return value == null ? null : `${value}%`;
}

function schedule(year: number | null, index: number | null): string | null {
  if (year == null) {
    return null;
  }
  return index == null ? `DSR ${year}` : `DSR ${year}, cost index ${index}%`;
}

/** The notice prints a wall-clock time; it is shown as printed, not shifted to a locale. */
function dateTime(value: string | null): string | null {
  if (!value) {
    return null;
  }
  const [date, time] = value.split('T');
  if (!date) {
    return null;
  }
  const [year, month, day] = date.split('-');
  const clock = (time ?? '').slice(0, 5);
  return clock ? `${day}/${month}/${year}, ${clock}` : `${day}/${month}/${year}`;
}
