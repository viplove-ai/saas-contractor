import {
  Alert,
  Box,
  Chip,
  CircularProgress,
  Divider,
  LinearProgress,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { tokens } from '../../app/theme';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount } from '../../shared/formatters';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { ReleaseCalendarChart } from './ReleaseCalendarChart';
import { today, useTreasuryDashboard } from './api';
import {
  STATUS_STYLE,
  TYPE_COLOR,
  TYPE_LABEL,
  TYPE_SHORT,
  TYPE_TINT,
  releaseWording,
  urgencyColor,
} from './palette';
import type { ProjectTreasuryRow, Security, TypeSlice } from './types';

/**
 * The company's blocked money, in one place.
 *
 * <p>The thing this screen exists to get right is that <b>money we placed and money withheld
 * from bills are never one figure</b>. A fixed deposit came out of the contractor's own working
 * capital and can be got back into a bank account; a retention was kept out of bills he never
 * received. Both are "not with us", which is a real question worth totalling — but a reader who
 * cannot see the split will treat the total as cash he can go and fetch, and a quarter of it
 * is not.</p>
 *
 * <p>Everything below the headline answers one of three questions an office actually asks:
 * what have I still to find, what is coming back and when, and what is already free to fund the
 * next tender.</p>
 */
export function TreasuryDashboardPage() {
  const [asOf, setAsOf] = useState(today());
  const dashboard = useTreasuryDashboard(asOf);
  const data = dashboard.data;

  return (
    <Stack spacing={2} sx={{ pb: 4 }}>
      <Typography variant="h1">Treasury</Typography>
      <Typography variant="body2" color="text.secondary">
        Earnest money, guarantees and retentions across every contract — what is out, when it
        comes back, and what is free to bid with.
      </Typography>

      <TextField
        label="As of"
        type="date"
        size="small"
        sx={{ maxWidth: 220 }}
        InputLabelProps={{ shrink: true }}
        value={asOf}
        onChange={(event) => setAsOf(event.target.value)}
        helperText="Every countdown on this screen is measured from here."
      />

      {dashboard.isLoading && <CircularProgress />}
      {dashboard.isError && <Alert severity="error">{apiErrorDetail(dashboard.error)}</Alert>}

      {data && (
        <>
          {/*
            Grid rather than MUI's Grid component: its gutters are a negative margin, and the
            Stack around this screen zeroes its children's margins — the same reason the
            company dashboard lays its tiles out by hand.
          */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: 'repeat(2, minmax(0, 1fr))',
                md: 'repeat(4, minmax(0, 1fr))',
              },
              gap: 2,
            }}
          >
            <Tile
              label="Blocked with departments"
              value={formatAmount(data.totalBlocked)}
              accent={tokens.ink}
            />
            <Tile
              label="Releasable now"
              value={formatAmount(data.releasableNow)}
              note={`${data.releasableNowCount} deposit(s) past their date`}
              accent={data.releasableNow > 0 ? tokens.stop : tokens.muted}
            />
            <Tile
              label="Still to lodge"
              value={formatAmount(data.awaitingLodgement)}
              note={`${data.awaitingLodgementCount} recorded and not yet placed`}
              accent={data.awaitingLodgement > 0 ? tokens.signal : tokens.muted}
            />
            <Tile
              label="Free to bid with"
              value={formatAmount(data.freeToReuse)}
              note={`${data.freeToReuseCount} released, nothing named`}
              accent={data.freeToReuse > 0 ? tokens.ok : tokens.muted}
            />
          </Box>

          {/* The split, given a card of its own rather than a footnote under the total. */}
          <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
            <Typography fontWeight={600} gutterBottom>
              What the {formatAmount(data.totalBlocked)} is made of
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} useFlexGap>
              <Half
                label="Deposits we placed"
                value={data.lodgedFromOwnFunds}
                total={data.totalBlocked}
                color={TYPE_COLOR.PERFORMANCE_GUARANTEE}
                note="Our own cash, in a bank, against earnest money and guarantees. It comes back to us."
              />
              <Half
                label="Withheld from our bills"
                value={data.retainedFromBills}
                total={data.totalBlocked}
                color={TYPE_COLOR.SECURITY_DEPOSIT}
                note="Never received. Releasing it is a payment against the final bill, not a deposit returning."
              />
            </Stack>
          </Paper>

          {/* By kind: four hues, because they are four different holding periods. */}
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: 'repeat(2, minmax(0, 1fr))',
                md: 'repeat(4, minmax(0, 1fr))',
              },
              gap: 2,
            }}
          >
            {data.byType.map((slice) => (
              <TypeTile key={slice.securityType} slice={slice} />
            ))}
          </Box>

          <ReleaseCalendarChart buckets={data.releaseCalendar} />

          <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
            <Typography fontWeight={600} gutterBottom>
              Coming back
            </Typography>
            <Stack direction="row" spacing={4} flexWrap="wrap" useFlexGap>
              <Figure label="Within 30 days" value={formatAmount(data.releasingIn30Days)} />
              <Figure label="Within 90 days" value={formatAmount(data.releasingIn90Days)} />
              <Figure label="Within a year" value={formatAmount(data.releasingIn365Days)} />
            </Stack>
            <Divider sx={{ my: 2 }} />
            <Typography variant="overline" color="text.secondary">
              This financial year
            </Typography>
            <Stack direction="row" spacing={4} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
              <Figure label="Released" value={formatAmount(data.releasedThisFinancialYear)} />
              <Figure
                label="Put onto another tender"
                value={formatAmount(data.redeployedThisFinancialYear)}
              />
              <Figure
                label="Forfeited (all time)"
                value={formatAmount(data.forfeitedToDate)}
                color={data.forfeitedToDate > 0 ? tokens.stop : undefined}
              />
            </Stack>
          </Paper>

          {data.needsAttention.length > 0 && (
            <Paper
              elevation={0}
              sx={{ p: 2, border: 2, borderColor: tokens.stop, borderRadius: 1 }}
            >
              <Typography fontWeight={700} color={tokens.stop} gutterBottom>
                Needs a telephone call
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Past the date the department should have released it, or a deposit maturing
                before the guarantee it stands behind has run out. The second one lapses quietly
                and the department finds it first.
              </Typography>
              <RecordTable
                columns={ATTENTION_COLUMNS}
                rows={data.needsAttention}
                rowKey={(row) => row.id}
                ariaLabel="Deposits needing attention"
              />
            </Paper>
          )}

          {data.reusable.length > 0 && (
            <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
              <Typography fontWeight={600} gutterBottom>
                Free to fund the next tender
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Released, with no other work named against them. Naming one on its next tender
                takes it off this list.
              </Typography>
              <RecordTable
                columns={REUSABLE_COLUMNS}
                rows={data.reusable}
                rowKey={(row) => row.id}
                ariaLabel="Deposits free to re-use"
              />
            </Paper>
          )}

          {data.byBank.length > 0 && (
            <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
              <Typography fontWeight={600} gutterBottom>
                Where the deposits are held
              </Typography>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Retentions are not here — they are with a department, not a bank.
              </Typography>
              <Stack spacing={1.5} sx={{ mt: 1 }}>
                {data.byBank.map((bank) => (
                  <Box key={bank.bankName}>
                    <Stack direction="row" justifyContent="space-between">
                      <Typography variant="body2" fontWeight={600}>
                        {bank.bankName}
                        <Typography component="span" variant="body2" color="text.secondary">
                          {' '}
                          · {bank.count} deposit(s)
                        </Typography>
                      </Typography>
                      <Typography variant="caption">{formatAmount(bank.held)}</Typography>
                    </Stack>
                    <LinearProgress
                      variant="determinate"
                      value={share(bank.held, data.lodgedFromOwnFunds)}
                      sx={{
                        mt: 0.5,
                        height: 8,
                        borderRadius: 4,
                        bgcolor: TYPE_TINT.PERFORMANCE_GUARANTEE,
                        '& .MuiLinearProgress-bar': {
                          bgcolor: TYPE_COLOR.PERFORMANCE_GUARANTEE,
                        },
                      }}
                    />
                  </Box>
                ))}
              </Stack>
            </Paper>
          )}

          <Typography fontWeight={600}>By contract</Typography>
          <RecordTable
            columns={PROJECT_COLUMNS}
            rows={data.projects}
            rowKey={(row) => row.projectId}
            ariaLabel="Deposits by contract"
            empty="Nothing is recorded against any contract yet. Open a project and add its deposits."
          />

          <Alert severity="info">{data.caveat}</Alert>
        </>
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ tables

const ATTENTION_COLUMNS: RecordColumn<Security>[] = [
  {
    key: 'contract',
    header: 'Contract',
    card: 'title',
    cell: (row) => (
      <>
        <Typography fontWeight={600}>{row.projectName ?? '—'}</Typography>
        <Typography variant="body2" color="text.secondary">
          {row.projectCode} · {TYPE_LABEL[row.securityType]}
        </Typography>
      </>
    ),
  },
  {
    key: 'reference',
    header: 'Reference',
    cell: (row) => (
      <>
        <Typography variant="body2">{row.referenceNo ?? 'Not recorded'}</Typography>
        <Typography variant="body2" color="text.secondary">
          {row.bankName ?? '—'}
        </Typography>
      </>
    ),
  },
  { key: 'held', header: 'Held', align: 'right', cell: (row) => formatAmount(row.heldAmount) },
  {
    key: 'why',
    header: 'Why',
    card: 'status',
    cell: (row) => (
      <Chip
        size="small"
        label={reasonFor(row)}
        sx={{
          bgcolor: '#FDECEC',
          color: tokens.stop,
          fontWeight: 600,
          borderRadius: 1,
        }}
      />
    ),
  },
  {
    key: 'due',
    header: 'Due back',
    align: 'right',
    cell: (row) => (
      <Typography variant="caption" sx={{ color: urgencyColor(row.daysToRelease) }}>
        {row.expectedReleaseOn ?? 'No date set'}
        <br />
        {releaseWording(row.daysToRelease)}
      </Typography>
    ),
  },
];

/**
 * Which of the two alarms this row is ringing. They are not the same problem: one is money the
 * department owes back, and the other is a deposit about to stop existing while the guarantee
 * it stands behind is still live.
 */
function reasonFor(row: Security): string {
  if (row.daysToRelease != null && row.daysToRelease < 0) {
    return 'Release overdue';
  }
  return 'Matures before release — renew';
}

const REUSABLE_COLUMNS: RecordColumn<Security>[] = [
  {
    key: 'contract',
    header: 'Released from',
    card: 'title',
    cell: (row) => (
      <>
        <Typography fontWeight={600}>{row.projectName ?? '—'}</Typography>
        <Typography variant="body2" color="text.secondary">
          {row.projectCode} · {TYPE_LABEL[row.securityType]}
        </Typography>
      </>
    ),
  },
  {
    key: 'reference',
    header: 'Reference',
    cell: (row) => row.referenceNo ?? 'Not recorded',
  },
  { key: 'bank', header: 'Bank', cell: (row) => row.bankName ?? '—' },
  { key: 'released', header: 'Released on', cell: (row) => row.releasedOn ?? '—' },
  {
    key: 'amount',
    header: 'Amount',
    align: 'right',
    cell: (row) => (
      <Typography variant="caption" fontWeight={700} sx={{ color: tokens.ok }}>
        {formatAmount(row.amount)}
      </Typography>
    ),
  },
];

const PROJECT_COLUMNS: RecordColumn<ProjectTreasuryRow>[] = [
  {
    key: 'contract',
    header: 'Contract',
    card: 'title',
    cell: (row) => (
      <>
        <Typography fontWeight={600}>
          <Link to={`/projects/${row.projectId}`}>{row.projectName ?? row.projectId}</Link>
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {row.projectCode} · {formatAmount(row.contractValue)}
        </Typography>
      </>
    ),
  },
  {
    key: 'split',
    header: 'By kind',
    cell: (row) => (
      <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
        <KindChip type="EMD" value={row.emdHeld} />
        <KindChip type="PERFORMANCE_GUARANTEE" value={row.performanceGuaranteeHeld} />
        <KindChip type="ADDITIONAL_PG" value={row.additionalPgHeld} />
        <KindChip type="SECURITY_DEPOSIT" value={row.securityDepositHeld} />
      </Stack>
    ),
  },
  {
    key: 'total',
    header: 'Held',
    align: 'right',
    cell: (row) => (
      <Typography variant="caption" fontWeight={700}>
        {formatAmount(row.totalHeld)}
      </Typography>
    ),
  },
  {
    key: 'awaiting',
    header: 'To lodge',
    align: 'right',
    cell: (row) =>
      row.awaiting > 0 ? (
        <Typography variant="caption" sx={{ color: tokens.signal, fontWeight: 700 }}>
          {formatAmount(row.awaiting)}
        </Typography>
      ) : (
        '—'
      ),
  },
  {
    key: 'next',
    header: 'Next release',
    align: 'right',
    cell: (row) =>
      row.nextReleaseOn ? (
        <>
          <Typography variant="caption">{row.nextReleaseOn}</Typography>
          <br />
          <Typography variant="body2" color="text.secondary">
            {formatAmount(row.nextReleaseAmount)}
          </Typography>
        </>
      ) : (
        '—'
      ),
  },
  {
    key: 'attention',
    header: '',
    card: 'status',
    cell: (row) =>
      row.attentionCount > 0 ? (
        <Chip
          size="small"
          label={`${row.attentionCount} to chase`}
          sx={{ bgcolor: '#FDECEC', color: tokens.stop, fontWeight: 600, borderRadius: 1 }}
        />
      ) : null,
  },
];

/** A kind's figure, in that kind's colour. Nothing held prints nothing — a zero is noise. */
function KindChip({ type, value }: { type: keyof typeof TYPE_COLOR; value: number }) {
  if (!value) return null;
  return (
    <Chip
      size="small"
      label={`${TYPE_SHORT[type]} ${formatAmount(value)}`}
      sx={{
        bgcolor: TYPE_TINT[type],
        color: TYPE_COLOR[type],
        fontWeight: 600,
        borderRadius: 1,
        fontSize: '0.72rem',
      }}
    />
  );
}

// ------------------------------------------------------------------ small pieces

function Tile({
  label,
  value,
  note,
  accent,
}: {
  label: string;
  value: string;
  note?: string;
  accent: string;
}) {
  return (
    <Paper
      elevation={0}
      sx={{
        p: 2,
        border: 1,
        borderColor: 'divider',
        height: '100%',
        // A left rule in the tile's own colour: the eye finds the red one without reading.
        borderLeft: `4px solid ${accent}`,
      }}
    >
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h3" sx={{ color: accent }}>
        {value}
      </Typography>
      {note && (
        <Typography variant="body2" color="text.secondary">
          {note}
        </Typography>
      )}
    </Paper>
  );
}

function Half({
  label,
  value,
  total,
  color,
  note,
}: {
  label: string;
  value: number;
  total: number;
  color: string;
  note: string;
}) {
  return (
    <Box sx={{ flex: 1, minWidth: 220 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
        <Typography variant="body2" fontWeight={600} sx={{ color }}>
          {label}
        </Typography>
        <Typography variant="caption" fontWeight={700}>
          {formatAmount(value)}
        </Typography>
      </Stack>
      <LinearProgress
        variant="determinate"
        value={share(value, total)}
        sx={{
          mt: 0.5,
          height: 10,
          borderRadius: 5,
          bgcolor: tokens.paperDeep,
          '& .MuiLinearProgress-bar': { bgcolor: color },
        }}
      />
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        {note}
      </Typography>
    </Box>
  );
}

function TypeTile({ slice }: { slice: TypeSlice }) {
  const color = TYPE_COLOR[slice.securityType];
  return (
    <Paper
      elevation={0}
      sx={{
        p: 2,
        height: '100%',
        border: 1,
        borderColor: 'divider',
        borderTop: `4px solid ${color}`,
      }}
    >
      <Typography variant="body2" sx={{ color, fontWeight: 600 }}>
        {TYPE_LABEL[slice.securityType]}
      </Typography>
      <Typography variant="h3">{formatAmount(slice.held)}</Typography>
      <Typography variant="body2" color="text.secondary">
        {slice.count} held
        {slice.awaiting > 0 && (
          <Typography component="span" variant="body2" sx={{ color: tokens.signal }}>
            {' '}
            · {formatAmount(slice.awaiting)} to lodge
          </Typography>
        )}
      </Typography>
    </Paper>
  );
}

function Figure({
  label,
  value,
  color,
}: {
  label: string;
  value: string;
  color?: string | undefined;
}) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography fontWeight={700} sx={{ fontSize: '1.1rem', color }}>
        {value}
      </Typography>
    </Box>
  );
}

/** Nought when there is no total to be a share of — never a division by zero. */
function share(value: number, total: number): number {
  return total > 0 ? Math.min((value / total) * 100, 100) : 0;
}

export { STATUS_STYLE };
