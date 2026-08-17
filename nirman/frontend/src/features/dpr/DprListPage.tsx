import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Drawer,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/Edit';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatHours, formatQuantity } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useSelectedSite } from '../../shared/siteSelection';
import { StatusChip, type RecordStatus } from '../../shared/StatusChip';
import { useAuth } from '../auth/AuthContext';
import { DeleteRecordDialog } from '../../shared/DeleteRecordDialog';
import { downloadDprPdf, useDecideDpr, useDeleteDpr, useDpr, useDprs, useSites } from './api';
import { causeLabel, isEditable, isWritable } from './types';
import type { Dpr, DprWorkflow, WorkItemResponse } from './types';

const STATUS_CHIP: Record<DprWorkflow, RecordStatus> = {
  DRAFT: 'DRAFT',
  SUBMITTED: 'SUBMITTED',
  VERIFIED: 'VERIFIED',
  REJECTED: 'REJECTED',
};

/**
 * The lines this report claims. The work is the headline, because an item number is what the
 * contract calls it and the activity is what the engineer recognises.
 */
const CLAIMED_COLUMNS: RecordColumn<WorkItemResponse>[] = [
  { key: 'item', header: 'Item', cell: (item) => item.itemNumber ?? '—' },
  {
    key: 'work',
    header: 'Work',
    card: 'title',
    cell: (item) => (
      <>
        {item.activity}
        {item.workLocation && (
          <Typography variant="body2" color="text.secondary">
            {item.workLocation}
          </Typography>
        )}
      </>
    ),
  },
  {
    key: 'quantity',
    header: 'Quantity',
    align: 'right',
    cell: (item) => formatQuantity(item.quantity),
  },
];

/**
 * The reports, and the engineer's queue.
 *
 * <p>Verification is not a formality here — it is the act that claims measured work against
 * the contract, so the panel puts the measured lines in front of the engineer before it puts
 * the button. Signing a report you have not read is easy to do when the screen makes it
 * easy, and what gets claimed is what gets billed.</p>
 */
export function DprListPage() {
  const { hasPermission } = useAuth();
  // Arrived at from "Resume" on the day's screen, which knows it wants the drafts. The filter
  // stays the screen's own from then on — the link sets it, it does not own it.
  const [params] = useSearchParams();
  const [status, setStatus] = useState<DprWorkflow | ''>(
    () => (params.get('status') as DprWorkflow | null) ?? '',
  );
  const [openId, setOpenId] = useState<string | null>(null);

  const sites = useSites();
  // Opens on the site the rest of the app is on, and still widens to every site — a register
  // read across sites for a minute is not a change of where anybody is working.
  const [siteId, setSiteId] = useSelectedSite(sites.data, { allowAll: true });
  const reports = useDprs(siteId || undefined, status);
  const canVerify = hasPermission('dpr:verify');
  const canDelete = hasPermission('dpr:delete');

  const columns: RecordColumn<Dpr>[] = [
    { key: 'report', header: 'Report', card: 'title', cell: (report) => report.dprNumber },
    { key: 'site', header: 'Site', cell: (report) => report.siteName },
    {
      key: 'date',
      header: 'Date',
      cell: (report) => (
        <>
          {report.reportDate}
          {/*
            A lost day, said on the row rather than only inside the report. A register where
            "no work, rain" looks exactly like a day with nothing filled in is a register
            somebody has to open twelve reports to read.
          */}
          {!report.siteOperational && (
            <Typography variant="body2" color="text.secondary">
              No work — {causeLabel(report.nonOperationalCause)}
            </Typography>
          )}
        </>
      ),
    },
    {
      /*
        Every man on the site, ours and the suppliers' together. The register is read as a
        month of days, and a column that counted only the muster roll would show four men on
        a day thirty stood on the site because twenty-six of them were somebody else's to
        pay — which is a fact about the wage bill, not about the day.
      */
      key: 'men',
      header: 'Men',
      align: 'right',
      cell: (report) => report.menOnSite ?? report.labourPresentCount ?? '—',
    },
    {
      key: 'cost',
      header: 'Day cost',
      align: 'right',
      cell: (report) => formatAmount(report.dayCost),
    },
    {
      key: 'status',
      header: 'Status',
      card: 'status',
      cell: (report) => <StatusChip status={STATUS_CHIP[report.workflowStatus]} />,
    },
    {
      /*
        The way back into an unfinished report. Without it a draft is a row you can read and
        never finish, which is what a draft is least useful as. The heading is blank because
        the column holds one conditional button, and "Actions" over mostly-empty cells reads
        as a column that failed to load.
      */
      key: 'continue',
      header: '',
      align: 'right',
      card: 'actions',
      cell: (report) =>
        isWritable(report.workflowStatus, canVerify) && (
          <Button
            component={Link}
            to={`/dpr/${report.id}`}
            size="small"
            startIcon={<EditIcon />}
            onClick={(event) => event.stopPropagation()}
          >
            {/*
              Two different acts behind one column. A draft is unfinished and its writer is
              coming back to it; a submitted report is finished as far as its writer is
              concerned and is waiting on the engineer for the half only he can write.
            */}
            {report.workflowStatus === 'SUBMITTED' ? 'Complete and sign' : 'Continue'}
          </Button>
        ),
    },
  ];

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Daily reports</Typography>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">Every site you can see</MenuItem>
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          select
          label="Status"
          value={status}
          onChange={(e) => setStatus(e.target.value as DprWorkflow | '')}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">Any</MenuItem>
          <MenuItem value="DRAFT">Draft</MenuItem>
          <MenuItem value="SUBMITTED">Waiting for signature</MenuItem>
          <MenuItem value="VERIFIED">Verified</MenuItem>
          <MenuItem value="REJECTED">Sent back</MenuItem>
        </TextField>
      </Stack>

      {reports.isLoading && <CircularProgress />}
      {reports.isError && <Alert severity="error">{apiErrorDetail(reports.error)}</Alert>}
      {reports.data?.content.length === 0 && (
        <Alert severity="info">
          No reports here yet. A supervisor writes one from the daily report screen.
        </Alert>
      )}

      {reports.data && reports.data.content.length > 0 && (
        <RecordTable
          columns={columns}
          rows={reports.data.content}
          rowKey={(report) => report.id}
          onRowClick={(report) => setOpenId(report.id)}
          ariaLabel="Daily reports"
        />
      )}

      <Drawer
        anchor="right"
        open={Boolean(openId)}
        onClose={() => setOpenId(null)}
        PaperProps={{ sx: { width: { xs: '100%', sm: 640 }, p: 2 } }}
      >
        {openId && (
          <ReportPanel
            id={openId}
            canVerify={canVerify}
            canDelete={canDelete}
            onClose={() => setOpenId(null)}
          />
        )}
      </Drawer>
    </Stack>
  );
}

function ReportPanel({
  id,
  canVerify,
  canDelete,
  onClose,
}: {
  id: string;
  canVerify: boolean;
  canDelete: boolean;
  onClose: () => void;
}) {
  const report = useDpr(id);
  const decide = useDecideDpr();
  const deleteDpr = useDeleteDpr();
  const [rejecting, setRejecting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [remarks, setRemarks] = useState('');
  const [downloadError, setDownloadError] = useState<string | null>(null);

  if (report.isLoading) return <CircularProgress />;
  if (report.isError) return <Alert severity="error">{apiErrorDetail(report.error)}</Alert>;
  if (!report.data) return null;

  const data: Dpr = report.data;
  const measured = data.workItems.filter((item) => item.measured);
  const described = data.workItems.filter((item) => !item.measured);

  async function download() {
    try {
      setDownloadError(null);
      await downloadDprPdf(data.id, data.dprNumber);
    } catch (error) {
      setDownloadError(apiErrorDetail(error));
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" alignItems="center" justifyContent="space-between">
        <div>
          <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
            {data.dprNumber}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {data.siteName} · {data.reportDate} · prepared by {data.preparedByName ?? 'unknown'}
          </Typography>
        </div>
        <IconButton aria-label="Close" onClick={onClose} sx={{ width: 48, height: 48 }}>
          <CloseIcon />
        </IconButton>
      </Stack>

      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <StatusChip status={STATUS_CHIP[data.workflowStatus]} />
        <Button size="small" startIcon={<DownloadIcon />} onClick={() => void download()}>
          Print
        </Button>
        {isWritable(data.workflowStatus, canVerify) && (
          <Button
            component={Link}
            to={`/dpr/${data.id}`}
            size="small"
            startIcon={<EditIcon />}
            onClick={onClose}
          >
            {data.workflowStatus === 'SUBMITTED'
              ? 'Complete and sign it'
              : 'Continue this report'}
          </Button>
        )}
        {/*
          Beside Continue, because they answer the same question in opposite directions: this
          report is unfinished, and either it is worth finishing or it should never have been
          opened. Only while it is still the writer's — once it has gone for signature the
          server refuses, and a button that only ever errs is worse than no button.
        */}
        {canDelete && isEditable(data.workflowStatus) && (
          <Button
            size="small"
            color="error"
            startIcon={<DeleteOutlineIcon />}
            onClick={() => setDeleting(true)}
          >
            Delete
          </Button>
        )}
      </Stack>
      {downloadError && <Alert severity="error">{downloadError}</Alert>}

      {/*
        The lost day, first and on its own. It is the whole content of the report — there is
        no work below it and no observation — so it goes above the figures rather than being
        found at the bottom of a page of dashes.
      */}
      {!data.siteOperational && (
        <Alert severity="warning" icon={false}>
          <Typography fontWeight={600}>
            Site not operational — {causeLabel(data.nonOperationalCause)}
          </Typography>
          {data.nonOperationalNote && (
            <Typography variant="body2">{data.nonOperationalNote}</Typography>
          )}
        </Alert>
      )}

      {data.workflowStatus === 'REJECTED' && data.rejectionReason && (
        <Alert severity="warning">Sent back: {data.rejectionReason}</Alert>
      )}
      {data.workflowStatus === 'VERIFIED' && (
        <Alert severity="success">
          Verified by {data.verifiedByName ?? 'the engineer'}
          {typeof data.progressPosted === 'number' &&
            ` · ${data.progressPosted} claim(s) posted to the measurement book`}
        </Alert>
      )}

      <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
        <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
          {/*
            The men first and whole, then what the whole is made of. The split is only drawn
            when a supplier sent somebody — on an ordinary day it would be the same number
            twice — and it is drawn at all because the two halves are not the same kind of
            fact: the wage cost below belongs to the muster's men alone.
          */}
          <Figure
            label="Men on site"
            value={String(data.menOnSite ?? data.labourPresentCount ?? '—')}
          />
          {(data.outsourcedHeadCount ?? 0) > 0 && (
            <>
              <Figure label="On the muster" value={String(data.labourPresentCount ?? '—')} />
              <Figure label="External (counted)" value={String(data.outsourcedHeadCount)} />
            </>
          )}
          <Figure label="Regular" value={formatHours(data.labourRegularHours)} />
          <Figure label="Overtime" value={formatHours(data.labourOvertimeHours)} />
          <Figure label="Labour cost" value={formatAmount(data.labourCost)} />
          <Figure label="Material consumed" value={formatAmount(data.materialConsumedValue)} />
          <Figure label="Other cost" value={formatAmount(data.expenseAmount)} />
          <Figure label="Day cost" value={formatAmount(data.dayCost)} />
        </Stack>
        {/*
          A submitted report's figures stop tracking the records. Saying so on the panel is
          what stops an engineer reading a discrepancy as a bug when it is the point: the
          document says what it said when it was signed.
        */}
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
          {data.snapshotFrozen
            ? 'These figures were frozen when the report was sent. A later correction to a record behind them shows up as a difference rather than by rewriting this report.'
            : 'This is a draft, so its figures still follow the records underneath.'}
        </Typography>
      </Paper>

      {measured.length > 0 && (
        <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <Typography fontWeight={600} gutterBottom>
            Claimed against the contract
          </Typography>
          <RecordTable
            nested
            columns={CLAIMED_COLUMNS}
            rows={measured}
            rowKey={(item) => item.id}
            ariaLabel="Claimed against the contract"
          />
        </Paper>
      )}

      {described.length > 0 && (
        <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <Typography fontWeight={600} gutterBottom>
            Recorded, not claimed
          </Typography>
          <Stack spacing={0.5}>
            {described.map((item) => (
              <Typography key={item.id} variant="body2">
                {item.activity}
                {item.workLocation && ` — ${item.workLocation}`}
              </Typography>
            ))}
          </Stack>
        </Paper>
      )}

      {/*
        Every one of these is drawn only when the report carries it. The first three belong to
        reports written on the older form, which asked for a summary, the delays and a note to
        the office; those reports were signed with them in and go on showing them. The last two
        are what the form asks for now.
      */}
      {(data.workSummary || data.delays || data.managementAttention
        || data.instructionsReceived || data.nextDayPlan) && (
        <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
          <PanelNote label="The day" value={data.workSummary} first />
          <PanelNote label="Delays" value={data.delays} first={!data.workSummary} />
          <PanelNote
            label="Needs the office"
            value={data.managementAttention}
            first={!data.workSummary && !data.delays}
          />
          <PanelNote
            label="Instructions from the department"
            value={data.instructionsReceived}
            first={!data.workSummary && !data.delays && !data.managementAttention}
          />
          <PanelNote
            label="Plan for tomorrow"
            value={data.nextDayPlan}
            first={
              !data.workSummary && !data.delays && !data.managementAttention
              && !data.instructionsReceived
            }
          />
        </Paper>
      )}

      {decide.isError && <Alert severity="error">{apiErrorDetail(decide.error)}</Alert>}

      {/*
        A working day nobody has written up yet. The server refuses to sign it and says so,
        but being told after pressing the button is worse than being told instead of pressing
        it — and the thing to do about it is one screen away.
      */}
      {canVerify && data.workflowStatus === 'SUBMITTED' && data.siteOperational
        && data.workItems.length === 0 && !data.workSummary && (
        <Alert severity="info">
          Nothing has been recorded as built yet, so there is nothing to sign for. Work done
          and the day&rsquo;s observations are yours to write —{' '}
          <Box component={Link} to={`/dpr/${data.id}`} onClick={onClose} sx={{ color: 'inherit' }}>
            open it
          </Box>
          .
        </Alert>
      )}

      {canVerify && data.workflowStatus === 'SUBMITTED' && (
        <Stack direction="row" spacing={2}>
          <Button
            variant="contained"
            disabled={decide.isPending}
            onClick={() => decide.mutate({ id: data.id, action: 'VERIFY' })}
          >
            {measured.length > 0
              ? `Verify and claim ${measured.length} line(s)`
              : 'Verify'}
          </Button>
          <Button variant="outlined" onClick={() => setRejecting(true)}>
            Send back
          </Button>
        </Stack>
      )}

      <Dialog open={rejecting} onClose={() => setRejecting(false)} fullWidth maxWidth="sm">
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
            value={remarks}
            onChange={(e) => setRemarks(e.target.value)}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejecting(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!remarks.trim() || decide.isPending}
            onClick={() => {
              decide.mutate(
                { id: data.id, action: 'REJECT', remarks },
                { onSuccess: () => setRejecting(false) },
              );
            }}
          >
            Send back
          </Button>
        </DialogActions>
      </Dialog>

      <DeleteRecordDialog
        open={deleting}
        kind="report"
        label={`${data.dprNumber} (${data.reportDate})`}
        description="It comes off the reports list and the day is free to be written again. The report itself is kept, with this reason on it."
        onConfirm={async (reason) => {
          await deleteDpr.mutateAsync({ id: data.id, reason });
          onClose();
        }}
        onClose={() => setDeleting(false)}
      />
    </Stack>
  );
}

/** One written box on the panel, drawn only when the report has one. */
function PanelNote({
  label,
  value,
  first,
}: {
  label: string;
  value: string | undefined;
  /** The rule above it is what separates two notes, so the first one does not get one. */
  first: boolean;
}) {
  if (!value) {
    return null;
  }
  return (
    <>
      {!first && <Divider sx={{ my: 1 }} />}
      <Typography fontWeight={600}>{label}</Typography>
      <Typography variant="body2">{value}</Typography>
    </>
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
