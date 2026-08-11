import {
  Alert,
  Box,
  CircularProgress,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatHours, formatQuantity } from '../../shared/formatters';
import { monthToDate, useSiteDashboard, useSites } from './api';
import { CostTrendChart } from './CostTrendChart';
import { MaterialPositionCard } from './MaterialPositionCard';
import { PeriodPicker } from './PeriodPicker';

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
  const dashboard = useSiteDashboard(routeSiteId, from, to);

  // Landing on /dashboard/site with no site picks the first one the caller can see, rather
  // than showing an empty screen and asking them to choose from a list of one.
  useEffect(() => {
    if (!routeSiteId && sites.data?.length) {
      navigate(`/dashboard/site/${sites.data[0]!.id}`, { replace: true });
    }
  }, [routeSiteId, sites.data, navigate]);

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
              <Table size="small" sx={{ mt: 1.5 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Item</TableCell>
                    <TableCell align="right">Contract</TableCell>
                    <TableCell align="right">Done</TableCell>
                    <TableCell align="right">Complete</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {dashboard.data.topWorkItems.map((item) => (
                    <TableRow key={item.boqItemId}>
                      <TableCell>
                        <Typography fontWeight={600}>{item.itemNumber}</Typography>
                        <Typography variant="body2" color="text.secondary">
                          {item.description}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">{formatQuantity(item.contractQuantity)}</TableCell>
                      <TableCell align="right">{formatQuantity(item.completedQuantity)}</TableCell>
                      <TableCell align="right">
                        {item.percentComplete == null
                          ? '—'
                          : `${item.percentComplete.toFixed(1)}%`}
                        {item.percentComplete != null && (
                          <LinearProgress
                            variant="determinate"
                            value={Math.min(item.percentComplete, 100)}
                            color={item.overClaimedQuantity > 0 ? 'warning' : 'primary'}
                            sx={{ mt: 0.5, height: 6, borderRadius: 3 }}
                          />
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
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
