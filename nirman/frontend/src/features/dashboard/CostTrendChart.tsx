import { Paper, Typography } from '@mui/material';
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { tokens } from '../../app/theme';
import { formatAmount } from '../../shared/formatters';
import type { DailyCost } from './types';

/**
 * The cost trend, as three stacked series rather than one line.
 *
 * <p>A single "cost" line hides the thing a trend is read for. A week where labour held steady
 * and material consumption tripled is a different story from one where both rose, and only the
 * split tells them apart — the blended line looks identical in both cases.</p>
 *
 * <p>Stacked, so the top edge is still the day's total: the reader gets the composition and the
 * total from one shape instead of choosing between them.</p>
 */
export function CostTrendChart({ trend }: { trend: DailyCost[] }) {
  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Typography fontWeight={600} gutterBottom>
        Cost by day
      </Typography>
      <div style={{ width: '100%', height: 260 }}>
        <ResponsiveContainer>
          <AreaChart data={trend} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
            <CartesianGrid stroke={tokens.line} vertical={false} />
            <XAxis
              dataKey="date"
              tick={{ fontSize: 12, fill: tokens.muted }}
              // A point per day over a month is thirty labels in the space of eight, so only
              // every fifth is drawn. The tooltip carries the exact date.
              interval={4}
              tickFormatter={(value: string) => value.slice(5)}
            />
            <YAxis
              tick={{ fontSize: 12, fill: tokens.muted }}
              width={72}
              tickFormatter={(value: number) => compact(value)}
            />
            <Tooltip formatter={(value: number) => formatAmount(value)} />
            <Legend />
            <Area
              type="monotone"
              dataKey="labourCost"
              name="Labour"
              stackId="cost"
              stroke={tokens.ink}
              fill={tokens.ink}
              fillOpacity={0.7}
            />
            <Area
              type="monotone"
              dataKey="materialConsumed"
              name="Material consumed"
              stackId="cost"
              stroke={tokens.signal}
              fill={tokens.signal}
              fillOpacity={0.7}
            />
            <Area
              type="monotone"
              dataKey="otherCost"
              name="Other"
              stackId="cost"
              stroke={tokens.muted}
              fill={tokens.muted}
              fillOpacity={0.5}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </Paper>
  );
}

/** Lakhs and thousands, because an axis of full rupee amounts is unreadable on a phone. */
function compact(value: number): string {
  if (Math.abs(value) >= 100000) return `${(value / 100000).toFixed(1)}L`;
  if (Math.abs(value) >= 1000) return `${Math.round(value / 1000)}k`;
  return String(value);
}
