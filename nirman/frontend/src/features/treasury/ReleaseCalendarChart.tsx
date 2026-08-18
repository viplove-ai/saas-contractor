import { Paper, Typography } from '@mui/material';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { tokens } from '../../app/theme';
import { formatAmount } from '../../shared/formatters';
import { TYPE_COLOR } from './palette';
import type { ReleaseBucket } from './types';

/**
 * What comes back, month by month.
 *
 * <p>Bars rather than a line, because these are eighteen separate events and not a quantity
 * that flows between them — the gap between March and August is the information, and a line
 * drawn across it invites the reader to see money arriving in May.</p>
 *
 * <p>Split into the two halves the whole screen keeps apart: a released fixed deposit lands in
 * a bank account and a released retention is a payment against the final bill. Stacked, so the
 * top of each bar is still the month's total.</p>
 */
export function ReleaseCalendarChart({ buckets }: { buckets: ReleaseBucket[] }) {
  const anything = buckets.some((bucket) => bucket.lodged > 0 || bucket.retained > 0);

  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Typography fontWeight={600}>When it comes back</Typography>
      <Typography variant="body2" color="text.secondary" gutterBottom>
        The next eighteen months. Anything already overdue is shown in the first column.
      </Typography>
      {!anything ? (
        <Typography variant="body2" color="text.secondary" sx={{ py: 4 }}>
          Nothing is due back in the next eighteen months. A deposit with no expected release
          date recorded does not appear here — set one on the contract's calendar.
        </Typography>
      ) : (
        <div style={{ width: '100%', height: 260 }}>
          <ResponsiveContainer>
            <BarChart data={buckets} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
              <CartesianGrid stroke={tokens.line} vertical={false} />
              <XAxis
                dataKey="month"
                tick={{ fontSize: 12, fill: tokens.muted }}
                // Eighteen labels in the space of six, so every third month is named and the
                // tooltip carries the rest.
                interval={2}
                tickFormatter={monthLabel}
              />
              <YAxis
                tick={{ fontSize: 12, fill: tokens.muted }}
                width={72}
                tickFormatter={compact}
              />
              <Tooltip
                formatter={(value: number) => formatAmount(value)}
                labelFormatter={monthLabel}
              />
              <Legend />
              <Bar
                dataKey="lodged"
                name="Deposits we placed"
                stackId="release"
                fill={TYPE_COLOR.PERFORMANCE_GUARANTEE}
              />
              <Bar
                dataKey="retained"
                name="Withheld from bills"
                stackId="release"
                fill={TYPE_COLOR.SECURITY_DEPOSIT}
              />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </Paper>
  );
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** `2026-08` becomes `Aug 26`. */
function monthLabel(month: string): string {
  const [year, index] = month.split('-');
  const name = index ? MONTHS[Number(index) - 1] : undefined;
  return name && year ? `${name} ${year.slice(2)}` : month;
}

/** Lakhs and crores, because an axis of full rupee amounts is unreadable at this width. */
function compact(value: number): string {
  if (value >= 10_000_000) return `${(value / 10_000_000).toFixed(1)}Cr`;
  if (value >= 100_000) return `${(value / 100_000).toFixed(1)}L`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}k`;
  return String(value);
}
