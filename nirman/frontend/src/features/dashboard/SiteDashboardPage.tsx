import {
  Alert,
  Box,
  CircularProgress,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatHours, formatQuantity } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useSelectedSite } from '../../shared/siteSelection';
import { monthToDate, useSiteDashboard, useSites } from './api';
import { CostTrendChart } from './CostTrendChart';
import { MaterialPositionCard } from './MaterialPositionCard';
import { PeriodPicker } from './PeriodPicker';
import type { OutsourcedTradeRow, WorkItemRow } from './types';

/** The lines this site has moved furthest on. */
const WORK_ITEM_COLUMNS: RecordColumn<WorkItemRow>[] = [
  {
    key: 'item',
    header: 'Item',
    card: 'title',
    cell: (item) => (
      <>
        <Typography fontWeight={600}>{item.itemNumber}</Typography>
        <Typography variant="body2" color="text.secondary">
          {item.description}
        </Typography>
      </>
    ),
  },
  {
    key: 'contract',
    header: 'Contract',
    align: 'right',
    cell: (item) => formatQuantity(item.contractQuantity),
  },
  {
    key: 'done',
    header: 'Done',
    align: 'right',
    cell: (item) => formatQuantity(item.completedQuantity),
  },
  {
    key: 'complete',
    header: 'Complete',
    align: 'right',
    cell: (item) => (
      <>
        {item.percentComplete == null ? '—' : `${item.percentComplete.toFixed(1)}%`}
        {item.percentComplete != null && (
          <LinearProgress
            variant="determinate"
            value={Math.min(item.percentComplete, 100)}
            color={item.overClaimedQuantity > 0 ? 'warning' : 'primary'}
            sx={{ mt: 0.5, height: 6, borderRadius: 3 }}
          />
        )}
      </>
    ),
  },
];

/**
 * The suppliers' trades, in head-days and man-hours and nothing else.
 *
 * <p>No money column, and there is no way to add one honestly: these men have no wage rate,
 * which is the whole point of the register the figures come from. What the supplier charged
 * is on his bill and reaches the site through the expense register.</p>
 */
const OUTSOURCED_TRADE_COLUMNS: RecordColumn<OutsourcedTradeRow>[] = [
  {
    key: 'trade',
    header: 'Trade',
    card: 'title',
    cell: (trade) => (
      <>
        <Typography fontWeight={600}>{trade.skillCategoryName ?? 'Not named'}</Typography>
        {/*
          Naming the supplier stays optional where the count is made, so it stays optional
          here. A count with nobody's name on it is still a true count, and "—" says that
          more honestly than a blank does.
        */}
        <Typography variant="body2" color="text.secondary">
          {trade.labourSupplierName ?? 'Supplier not named'}
        </Typography>
      </>
    ),
  },
  {
    key: 'headDays',
    header: 'Head-days',
    align: 'right',
    cell: (trade) => String(trade.headDays),
  },
  {
    key: 'manHours',
    header: 'Man-hours',
    align: 'right',
    // Null is not zero: nobody wrote the hours down, which is not the same as nobody working.
    cell: (trade) => (trade.manHours == null ? 'Not recorded' : formatHours(trade.manHours)),
  },
  {
    key: 'days',
    header: 'Days',
    align: 'right',
    cell: (trade) => String(trade.daysCounted),
  },
];

/**
 * One site: what it spent, what it holds, what it has built, and what its diary is missing.
 *
 * <p>Two figures are kept visibly apart from their totals, for the same reason in both cases —
 * they can still move. The unsigned part of the wage bill is not yet anybody's signature, and
 * the days with no report are the gaps that make every other figure on the page an
 * understatement rather than a measurement.</p>
 */
export function SiteDashboardPage() {
  const { siteId: routeSiteId } = useParams<{ siteId: string }>();
  const navigate = useNavigate();
  const initial = monthToDate();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);

  const sites = useSites();
  const [selectedSiteId, chooseSite] = useSelectedSite(sites.data);
  const dashboard = useSiteDashboard(routeSiteId, from, to);

  /*
    The site lives in the path here, because this screen is one people send each other a link
    to. So the two have to be kept in step: landing on /dashboard/site with no site goes to
    the one the rest of the app is on, and arriving on a link makes that site the app's.
  */
  useEffect(() => {
    if (!routeSiteId && selectedSiteId) {
      navigate(`/dashboard/site/${selectedSiteId}`, { replace: true });
    }
  }, [routeSiteId, selectedSiteId, navigate]);

  // Only a site the account actually reaches. A link to somebody else's block would
  // otherwise be remembered, refused by the guard, and replaced on the next screen anyway.
  const routeSiteIsReachable = (sites.data ?? []).some((site) => site.id === routeSiteId);
  useEffect(() => {
    if (routeSiteId && routeSiteIsReachable && routeSiteId !== selectedSiteId) {
      chooseSite(routeSiteId);
    }
  }, [routeSiteId, routeSiteIsReachable, selectedSiteId, chooseSite]);

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Site</Typography>

      <PeriodPicker from={from} to={to} onFromChange={setFrom} onToChange={setTo}>
        <TextField
          select
          label="Site"
          value={routeSiteId ?? ''}
          onChange={(e) => navigate(`/dashboard/site/${e.target.value}`)}
          sx={{ minWidth: 220 }}
        >
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
      </PeriodPicker>

      {dashboard.isLoading && <CircularProgress />}
      {dashboard.isError && <Alert severity="error">{apiErrorDetail(dashboard.error)}</Alert>}

      {dashboard.data && (
        <>
          <Typography color="text.secondary">
            {dashboard.data.siteCode} — {dashboard.data.siteName}
            {dashboard.data.projectName && ` · ${dashboard.data.projectName}`}
          </Typography>

          {/*
            A CSS grid rather than <Grid container>. MUI's Grid pays for its gutters with a
            negative margin on the container, and the Stack around this screen sets `margin: 0`
            on each of its children — so the negative margin was dropped while the matching
            `width: calc(100% + 16px)` was not, and Labour and Cash sat 16px right of every
            other card on the page. Nothing here needs the twelve-column arithmetic.
          */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'repeat(2, minmax(0, 1fr))' },
              gap: 2,
            }}
          >
            <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider', height: '100%' }}>
              <Typography fontWeight={600} gutterBottom>
                Labour
              </Typography>
              <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
                <Figure label="Man-days" value={formatQuantity(dashboard.data.labour.manDays)} />
                <Figure label="Regular" value={formatHours(dashboard.data.labour.regularHours)} />
                <Figure
                  label="Overtime"
                  value={formatHours(dashboard.data.labour.overtimeHours)}
                />
                <Figure label="Wage cost" value={formatAmount(dashboard.data.labour.cost)} />
              </Stack>
              {/*
                The wage is frozen at verification, so the unsigned part can still move
                without anybody editing anything. A single blended figure would move with it
                silently.
              */}
              {dashboard.data.labour.unverifiedCost > 0 && (
                <Alert severity="warning" sx={{ mt: 1.5 }}>
                  {formatAmount(dashboard.data.labour.unverifiedCost)} of this is not verified
                  yet ({dashboard.data.labour.pendingVerification} row(s) waiting), so it can
                  still change.
                </Alert>
              )}
              {dashboard.data.labour.daysWithoutAttendance > 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                  {dashboard.data.labour.daysWithoutAttendance} day(s) in this period have no
                  muster at all.
                </Typography>
              )}
            </Paper>

            <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider', height: '100%' }}>
              <Typography fontWeight={600} gutterBottom>
                Cash
              </Typography>
              <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
                <Figure
                  label="Cost incurred"
                  value={formatAmount(dashboard.data.cash.costIncurred)}
                />
                <Figure
                  label="Material purchases"
                  value={formatAmount(dashboard.data.cash.materialPurchases)}
                />
                <Figure
                  label="Wage payments"
                  value={formatAmount(dashboard.data.cash.labourDisbursements)}
                />
                <Figure
                  label="Total booked"
                  value={formatAmount(dashboard.data.cash.totalBooked)}
                  strong
                />
              </Stack>
              <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap sx={{ mt: 1.5 }}>
                <Figure label="Paid" value={formatAmount(dashboard.data.cash.paid)} />
                <Figure label="Still owed" value={formatAmount(dashboard.data.cash.payable)} />
              </Stack>
              {dashboard.data.cash.awaitingApproval > 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                  {dashboard.data.cash.awaitingApproval} bill(s) waiting on an approval.
                </Typography>
              )}
            </Paper>
          </Box>

          {/*
            External labour on a card of its own, and only where the site works that way. The
            figures never join the ones above them: those men have a wage behind their hours
            and these have none, so a "man-days" that included them would read as a wage bill
            spread over people who are not on the payroll. What the supplier charged for them
            is a bill on the expense register and is already inside Cash.

            Head-days rather than a head count, with the peak beside it. Thirty days of head
            counts added together is not how many men were there, and the only defence against
            somebody reading it that way is printing the day that actually was.
          */}
          {dashboard.data.outsourcedLabour.enabled && (
            <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
              <Typography fontWeight={600} gutterBottom>
                External labour
              </Typography>
              <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
                <Figure
                  label="Head-days"
                  value={String(dashboard.data.outsourcedLabour.headDays)}
                  strong
                />
                <Figure
                  label="Busiest day"
                  value={`${dashboard.data.outsourcedLabour.peakHeadCount} men`}
                />
                <Figure
                  label="Man-hours"
                  value={
                    dashboard.data.outsourcedLabour.manHours > 0
                      ? formatHours(dashboard.data.outsourcedLabour.manHours)
                      : 'Not recorded'
                  }
                />
                <Figure
                  label="Days counted"
                  value={String(dashboard.data.outsourcedLabour.daysCounted)}
                />
              </Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                No wage cost: the supplier bills for the work, and his bill is in Cash above.
              </Typography>
              {/*
                Said out loud rather than left to be inferred from a low figure. Hours are
                recorded where the site knows them and left out where it does not, so
                man-hours over a fortnight with four unrecorded days is a smaller number than
                the head-days beside it imply — and a reader who cannot see why will divide
                one by the other.
              */}
              {dashboard.data.outsourcedLabour.daysWithoutHours > 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                  {dashboard.data.outsourcedLabour.daysWithoutHours} counted day(s) have no
                  hours written against them, so the man-hours cover less than the head-days do.
                </Typography>
              )}
              {dashboard.data.outsourcedLabour.daysWithoutCount > 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                  {dashboard.data.outsourcedLabour.daysWithoutCount} day(s) in this period have
                  no count at all.
                </Typography>
              )}
              {dashboard.data.outsourcedLabour.trades.length > 0 && (
                <RecordTable
                  nested
                  columns={OUTSOURCED_TRADE_COLUMNS}
                  rows={dashboard.data.outsourcedLabour.trades}
                  rowKey={(trade) =>
                    `${trade.skillCategoryId}-${trade.labourSupplierId ?? 'unnamed'}`
                  }
                  ariaLabel="External labour by trade"
                />
              )}
            </Paper>
          )}

          <MaterialPositionCard material={dashboard.data.material} />

          <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
            <Typography fontWeight={600} gutterBottom>
              Contract progress
            </Typography>
            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
              <Figure
                label="Contract value"
                value={formatAmount(dashboard.data.progress.contractValue)}
              />
              <Figure
                label="Work done"
                value={formatAmount(dashboard.data.progress.valueOfWorkDone)}
              />
              <Figure
                label="Complete"
                value={
                  dashboard.data.progress.percentComplete == null
                    ? '—'
                    : `${dashboard.data.progress.percentComplete.toFixed(1)}%`
                }
                strong
              />
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              By value, not by line count — finishing nine cheap lines and none of the expensive
              one is not ninety per cent of a job.
            </Typography>
            {dashboard.data.progress.itemsOverClaimed > 0 && (
              <Alert severity="warning" sx={{ mt: 1.5 }}>
                {dashboard.data.progress.itemsOverClaimed} line(s) are claimed beyond their
                contract quantity.
              </Alert>
            )}

            {dashboard.data.topWorkItems.length > 0 && (
              <RecordTable
                nested
                columns={WORK_ITEM_COLUMNS}
                rows={dashboard.data.topWorkItems}
                rowKey={(item) => item.boqItemId}
                ariaLabel="Work items"
              />
            )}
          </Paper>

          <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
            <Typography fontWeight={600} gutterBottom>
              Daily reports
            </Typography>
            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
              <Figure label="In period" value={String(dashboard.data.dpr.reportsInRange)} />
              <Figure label="Verified" value={String(dashboard.data.dpr.verified)} />
              <Figure
                label="Waiting"
                value={String(dashboard.data.dpr.awaitingVerification)}
              />
              <Figure label="Draft" value={String(dashboard.data.dpr.draft)} />
            </Stack>
            {/*
              The dates rather than the count. "Eleven days unreported" is a complaint;
              the eleven dates are a morning's work for somebody.
            */}
            {dashboard.data.dpr.daysWithoutReport.length > 0 && (
              <Alert severity="warning" sx={{ mt: 1.5 }}>
                No report on {dashboard.data.dpr.daysWithoutReport.join(', ')}.
              </Alert>
            )}
          </Paper>

          <CostTrendChart trend={dashboard.data.trend} />

          <Alert severity="info">{dashboard.data.caveat}</Alert>
        </>
      )}
    </Stack>
  );
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
