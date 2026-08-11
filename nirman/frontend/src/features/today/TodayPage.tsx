import {
  Alert,
  Box,
  Button,
  CircularProgress,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatQuantity } from '../../shared/formatters';
import { figure, inkEdge, marginNote } from '../../app/sketch';
import { tokens } from '../../app/theme';
import { StatusChip } from '../../shared/StatusChip';
import { useToday } from './api';

/**
 * What today needs, in the order today needs it.
 *
 * <p>This replaces the tile grid as the landing screen. The grid was eighteen equal doors and
 * the screen's whole content was the list of doors — a supervisor opening the app at 9 a.m. had
 * to read all of them to find the one he opens every morning. The three bands here are the same
 * grouping his old home already made ("Today at site", "Check and sign off", the registers),
 * ranked instead of listed: what has not been entered, what is waiting on his signature, what
 * he might enter next.</p>
 *
 * <p>The muster card is the only emphasised card on the screen and it is only emphasised while
 * the muster is unmarked. A screen where everything is loud is a screen where nothing is.</p>
 */
export function TodayPage() {
  const [siteId, setSiteId] = useState('');
  const t = useToday(siteId || undefined);

  // Most supervisors have exactly one site; nobody should choose from a list of one.
  useEffect(() => {
    if (!siteId && t.sites.data?.length) {
      setSiteId(t.sites.data[0]!.id);
    }
  }, [t.sites.data, siteId]);

  const dash = t.dashboard.data;

  return (
    <Stack spacing={2.5} sx={{ pb: 3 }}>
      <Stack direction="row" alignItems="flex-end" justifyContent="space-between" spacing={2}>
        <Box>
          <Typography variant="h1">{longDate(t.today)}</Typography>
          <Typography variant="overline" sx={{ color: tokens.annotation, display: 'block', mt: 0.5 }}>
            {t.site ? `${t.site.code} · ${t.site.name.toUpperCase()}` : 'SELECT A SITE'}
          </Typography>
        </Box>
        {(t.sites.data?.length ?? 0) > 1 && (
          <TextField
            select
            size="small"
            label="Site"
            value={siteId}
            onChange={(event) => setSiteId(event.target.value)}
            sx={{ maxWidth: 240 }}
          >
            {(t.sites.data ?? []).map((site) => (
              <MenuItem key={site.id} value={site.id}>
                {site.code} — {site.name}
              </MenuItem>
            ))}
          </TextField>
        )}
      </Stack>

      <HandRule />

      {t.isLoading && <CircularProgress />}
      {t.dashboard.isError && <Alert severity="error">{apiErrorDetail(t.dashboard.error)}</Alert>}

      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '1.45fr 1fr' },
          gap: 2.5,
          alignItems: 'start',
        }}
      >
        <Stack spacing={2.5}>
          <MusterCard
            unmarked={t.muster.unmarked}
            marked={t.muster.marked}
            total={t.muster.total}
            locked={t.muster.locked}
            standardShiftHours={t.muster.standardShiftHours}
          />

          {/*
            A supervisor signs nothing off — no attendance to verify, no bill to approve, no
            report to counter-sign — so the band would be a permanent "nothing is waiting on
            you". His unfinished draft still is waiting on him, and it moves to the report
            card below where the rest of his day already is.
          */}
          {(t.can.signsOff || t.can.seesDashboard) && (
          <Section label={`WAITING ON YOU · ${t.signoffCount}`}>
            {t.signoffCount === 0 && t.waiting.draftReports === 0 ? (
              <Typography sx={marginNote}>Nothing is waiting on your signature.</Typography>
            ) : (
              <Stack spacing={1.25}>
                {t.waiting.attendanceRowsToVerify > 0 && (
                  <WaitingRow
                    seed={0}
                    urgent
                    title={`${t.waiting.attendanceRowsToVerify} attendance row(s) to verify`}
                    detail={
                      dash && dash.labour.unverifiedCost > 0
                        ? `${formatAmount(dash.labour.unverifiedCost)} of wage is not frozen yet`
                        : 'Marked at the site and waiting on an engineer'
                    }
                    status="SUBMITTED"
                    action="Open"
                    to="/attendance/verify"
                  />
                )}
                {t.waiting.billsToApprove > 0 && (
                  <WaitingRow
                    seed={1}
                    urgent
                    title={`${t.waiting.billsToApprove} bill(s) need approval`}
                    detail={dash ? `${formatAmount(dash.cash.payable)} still owed on this site` : ''}
                    status="PENDING"
                    action="Open"
                    to="/approvals"
                  />
                )}
                {t.waiting.reportsToVerify > 0 && (
                  <WaitingRow
                    seed={2}
                    urgent
                    title={`${t.waiting.reportsToVerify} daily report(s) to verify`}
                    detail="Submitted by the site and waiting on a signature"
                    status="SUBMITTED"
                    action="Open"
                    to="/dprs"
                  />
                )}
                {t.waiting.draftReports > 0 && (
                  <WaitingRow
                    seed={3}
                    title={`${t.waiting.draftReports} daily report(s) still a draft`}
                    detail="Started and not submitted"
                    status="DRAFT"
                    action="Resume"
                    to="/dprs"
                  />
                )}
              </Stack>
            )}
          </Section>
          )}

          {/*
            The end of the supervisor's day, and the reason the four buttons below it are
            optional rather than required: whatever he did not enter as it happened, he can
            enter here, and the report is what reaches the office either way.
          */}
          <DailyReportCard
            draftCount={t.waiting.draftReports}
            emphasise={!t.can.signsOff}
            siteName={t.site?.name}
          />

          <Section label="ENTER SOMETHING">
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(3, 1fr)' }, gap: 1.25 }}>
              <EntryButton seed={0} to="/inventory/receive" label="Receive material" />
              <EntryButton seed={1} to="/inventory/issue" label="Issue material" />
              <EntryButton seed={2} to="/expenses/new" label="Add expense" />
            </Box>
            <Typography sx={{ ...marginNote, mt: 1 }}>
              Or leave them and enter the lot on tonight&rsquo;s report.
            </Typography>
          </Section>

          {t.unsent.waiting > 0 && (
            <Typography sx={marginNote}>
              {t.unsent.waiting} record(s) are still on this device.{' '}
              <Box component={Link} to="/sync" sx={{ color: 'inherit' }}>
                Open the sync list
              </Box>
              {t.unsent.stuck > 0 && ' — some need a decision.'}
            </Typography>
          )}
        </Stack>

        {/* The month, kept to the side: context, never the reason anybody opened the app. */}
        {dash && (
          <Stack spacing={2}>
            <Paper variant="outlined" sx={{ ...inkEdge(4), p: 2.25 }}>
              <Typography variant="overline" sx={{ color: tokens.annotation, display: 'block', mb: 1.5 }}>
                SITE, MONTH TO DATE
              </Typography>
              <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2 }}>
                <Figure label="Man-days" value={formatQuantity(dash.labour.manDays)} />
                <Figure label="Wage cost" value={formatAmount(dash.labour.cost)} />
                <Figure label="Booked" value={formatAmount(dash.cash.totalBooked)} />
                <Figure label="Still owed" value={formatAmount(dash.cash.payable)} tone={tokens.warn} />
              </Box>
              {dash.labour.unverifiedCost > 0 && (
                <Alert severity="warning" sx={{ mt: 2 }}>
                  {formatAmount(dash.labour.unverifiedCost)} of the wage bill is not verified yet, so
                  it can still change.
                </Alert>
              )}
            </Paper>

            <Paper variant="outlined" sx={{ ...inkEdge(1), p: 2.25 }}>
              <Stack direction="row" alignItems="baseline" justifyContent="space-between" sx={{ mb: 0.75 }}>
                <Typography variant="overline" sx={{ color: tokens.annotation }}>
                  CONTRACT PROGRESS
                </Typography>
                <Typography sx={{ ...figure, fontSize: '0.95rem' }}>
                  {dash.progress.percentComplete == null ? '—' : `${dash.progress.percentComplete.toFixed(1)}%`}
                </Typography>
              </Stack>
              <LinearProgress
                variant="determinate"
                value={Math.min(dash.progress.percentComplete ?? 0, 100)}
                color="secondary"
                sx={{
                  height: 14,
                  borderRadius: 8,
                  border: `1.5px solid ${tokens.ink}`,
                  bgcolor: tokens.paperDeep,
                  '& .MuiLinearProgress-bar': { borderRadius: 0 },
                }}
              />
              <Typography sx={{ ...marginNote, mt: 1.25 }}>By value, not by line count.</Typography>
              {dash.topWorkItems.length > 0 && (
                <Stack spacing={1.25} sx={{ mt: 1.75 }}>
                  {dash.topWorkItems.slice(0, 3).map((item) => (
                    <Stack key={item.boqItemId} direction="row" justifyContent="space-between" spacing={1.5}>
                      <Typography sx={{ fontSize: '0.8125rem', fontWeight: 500 }} noWrap>
                        {item.description}
                      </Typography>
                      <Typography sx={{ ...figure, fontSize: '0.8125rem', color: 'text.secondary', flexShrink: 0 }}>
                        {formatQuantity(item.completedQuantity)} / {formatQuantity(item.contractQuantity)}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              )}
            </Paper>

            {/*
              The dates rather than the count — the same decision the site dashboard makes.
              "Eleven days unreported" is a complaint; eleven dates are a morning's work.
            */}
            {(dash.dpr.daysWithoutReport.length > 0 || dash.labour.daysWithoutAttendance > 0) && (
              <Box
                sx={{
                  border: '1.6px dashed rgba(20,24,29,0.45)',
                  borderRadius: '14px 8px 15px 9px / 9px 15px 8px 14px',
                  bgcolor: 'rgba(20,24,29,0.03)',
                  p: 2,
                }}
              >
                <Typography variant="overline" sx={{ color: tokens.annotation, display: 'block', mb: 1 }}>
                  MISSING FROM THE RECORD
                </Typography>
                {dash.labour.daysWithoutAttendance > 0 && (
                  <Typography sx={{ fontSize: '0.8125rem', fontWeight: 500 }}>
                    {dash.labour.daysWithoutAttendance} day(s) in this period have no muster at all.
                  </Typography>
                )}
                {dash.dpr.daysWithoutReport.length > 0 && (
                  <Typography sx={{ fontSize: '0.8125rem', fontWeight: 500, mt: 0.5 }}>
                    No report on <Box component="span" sx={figure}>{dash.dpr.daysWithoutReport.join(', ')}</Box>.
                  </Typography>
                )}
              </Box>
            )}
          </Stack>
        )}
      </Box>

      <Button variant="text" component={Link} to="/home" sx={{ alignSelf: 'flex-start', color: tokens.muted }}>
        All screens →
      </Button>
    </Stack>
  );
}

/**
 * The muster, and the only card on the screen allowed a drawn drop shadow.
 *
 * <p>It says what has not happened rather than what the screen is called, and it carries the
 * two taps that answer it: the roll, and the forty-men-are-here shortcut that the mark screen
 * already has. Once the day is marked the card loses its emphasis and becomes a receipt.</p>
 */
function MusterCard({
  unmarked,
  marked,
  total,
  locked,
  standardShiftHours,
}: {
  unmarked: boolean;
  marked: number;
  total: number;
  locked: boolean;
  standardShiftHours: number;
}) {
  if (total === 0) {
    return (
      <Paper variant="outlined" sx={{ ...inkEdge(0), p: 2.5 }}>
        <Typography variant="h2">No roster for today</Typography>
        <Typography color="text.secondary" sx={{ mt: 0.5 }}>
          Nobody is posted to this site. Post workers from the worker list first.
        </Typography>
        <Button component={Link} to="/workers" variant="outlined" sx={{ mt: 2 }}>
          Open workers
        </Button>
      </Paper>
    );
  }

  return (
    <Paper variant="outlined" sx={{ ...inkEdge(0, { emphasis: unmarked }), p: 2.5 }}>
      <Stack direction="row" alignItems="baseline" justifyContent="space-between" spacing={1.5}>
        <Typography variant="overline" sx={{ color: unmarked ? 'secondary.main' : tokens.ok }}>
          {locked ? 'MONTH IS CLOSED' : unmarked ? 'NOT MARKED YET' : `${marked} OF ${total} MARKED`}
        </Typography>
        <Typography sx={{ ...figure, fontSize: '0.8125rem', color: 'text.secondary' }}>
          {total} on roster · {standardShiftHours} h shift
        </Typography>
      </Stack>
      <Typography variant="h2" sx={{ mt: 1 }}>
        Muster for today
      </Typography>
      <Typography color="text.secondary" sx={{ mt: 0.5, maxWidth: '52ch' }}>
        {locked
          ? 'This month is closed for attendance. Nothing can be entered or changed.'
          : unmarked
            ? 'Nobody is marked. Wages freeze at verification, so anything entered late keeps the cost figure moving after the fact.'
            : 'Marked and waiting on an engineer. Correct it here until it is verified.'}
      </Typography>
      {!locked && (
        <Stack direction="row" spacing={1.5} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
          <Button component={Link} to="/attendance/mark" variant="contained" color="secondary">
            {unmarked ? 'Mark attendance' : 'Open the muster'}
          </Button>
          {unmarked && (
            // Deep-links into the mark screen's own "Mark all present" — the shortcut exists
            // there already, and forty men present is the common day.
            <Button component={Link} to="/attendance/mark?all=present" variant="outlined">
              Mark all {total} present
            </Button>
          )}
        </Stack>
      )}
    </Paper>
  );
}

/**
 * The day's report, as a card rather than a button.
 *
 * <p>For a supervisor this is the end of the day and the only thing that reaches the office,
 * so it is the second emphasised card on the screen — after the muster, which is the start of
 * the day. For an engineer, who has a sign-off band above it, it stays quiet: he writes
 * reports too, but somebody else's report waiting on his signature ranks higher.</p>
 */
function DailyReportCard({
  draftCount,
  emphasise,
  siteName,
}: {
  draftCount: number;
  emphasise: boolean;
  siteName: string | undefined;
}) {
  const resuming = draftCount > 0;
  return (
    <Paper variant="outlined" sx={{ ...inkEdge(2, { emphasis: emphasise && !resuming }), p: 2.5 }}>
      <Typography variant="overline" sx={{ color: resuming ? 'secondary.main' : tokens.annotation }}>
        {resuming ? `${draftCount} REPORT(S) STILL A DRAFT` : 'END OF THE DAY'}
      </Typography>
      <Typography variant="h2" sx={{ mt: 1 }}>
        Today&rsquo;s report
      </Typography>
      <Typography color="text.secondary" sx={{ mt: 0.5, maxWidth: '52ch' }}>
        {resuming
          ? 'Started and not sent. A draft is not a report — the office sees nothing until it goes.'
          : `Labour, material and spending${siteName ? ` at ${siteName}` : ''} for the day, in one place. Anything already entered is filled in for you.`}
      </Typography>
      <Stack direction="row" spacing={1.5} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
        <Button component={Link} to="/dpr/new" variant="contained" color="secondary">
          {resuming ? 'Finish the report' : "Write today's report"}
        </Button>
        <Button component={Link} to="/dprs" variant="outlined">
          Past reports
        </Button>
      </Stack>
    </Paper>
  );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Box component="section">
      <Typography variant="overline" sx={{ color: 'text.secondary', display: 'block', mb: 1.25 }}>
        {label}
      </Typography>
      {children}
    </Box>
  );
}

function WaitingRow({
  seed,
  title,
  detail,
  status,
  action,
  to,
  urgent,
}: {
  seed: number;
  title: string;
  detail: string;
  status: 'DRAFT' | 'SUBMITTED' | 'PENDING';
  action: string;
  to: string;
  urgent?: boolean;
}) {
  return (
    <Stack
      direction="row"
      alignItems="center"
      spacing={1.75}
      sx={{ ...inkEdge(seed), boxShadow: 'none', border: '1.5px solid rgba(20,24,29,0.55)', p: 1.75 }}
    >
      <Box
        sx={{
          width: 9,
          height: 9,
          flexShrink: 0,
          borderRadius: '50%',
          bgcolor: urgent ? 'secondary.main' : tokens.muted,
        }}
      />
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography sx={{ fontWeight: 600, fontSize: '0.9375rem' }}>{title}</Typography>
        {detail && (
          <Typography variant="body2" color="text.secondary">
            {detail}
          </Typography>
        )}
      </Box>
      <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
        <StatusChip status={status} />
      </Box>
      <Button component={Link} to={to} variant="outlined" sx={{ minHeight: 44, flexShrink: 0 }}>
        {action}
      </Button>
    </Stack>
  );
}

function EntryButton({ seed, to, label }: { seed: number; to: string; label: string }) {
  return (
    <Button
      component={Link}
      to={to}
      variant="outlined"
      sx={{ ...inkEdge(seed), boxShadow: '2px 3px 0 rgba(20,24,29,0.10)', minHeight: 60, justifyContent: 'flex-start', px: 1.75 }}
    >
      {label}
    </Button>
  );
}

function Figure({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography sx={{ ...figure, fontSize: '1.3rem', color: tone ?? 'text.primary' }}>{value}</Typography>
    </Box>
  );
}

/** The drawn rule under a heading. One path, no library, no asset to ship. */
export function HandRule({ width = 340, color = tokens.signal }: { width?: number; color?: string }) {
  return (
    <Box
      component="svg"
      viewBox="0 0 420 10"
      preserveAspectRatio="none"
      aria-hidden="true"
      sx={{ display: 'block', width, maxWidth: '100%', height: 8, mt: -1.5 }}
    >
      <path d="M3 6.5 Q110 2 220 5.5 T417 3.5" stroke={color} strokeWidth="2.6" fill="none" strokeLinecap="round" />
    </Box>
  );
}

function longDate(isoDate: string): string {
  return new Date(`${isoDate}T00:00:00`).toLocaleDateString('en-IN', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  });
}
