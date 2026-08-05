import { Stack, TextField } from '@mui/material';

/**
 * The window every dashboard takes. Defaults to the month to date, which is what somebody
 * opening a dashboard almost always means; the server refuses anything wider than a year,
 * because a chart with a point per day over five is neither fast nor readable.
 */
export function PeriodPicker({
  from,
  to,
  onFromChange,
  onToChange,
  children,
}: {
  from: string;
  to: string;
  onFromChange: (value: string) => void;
  onToChange: (value: string) => void;
  children?: React.ReactNode;
}) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
      {children}
      <TextField
        label="From"
        type="date"
        value={from}
        onChange={(e) => onFromChange(e.target.value)}
        InputLabelProps={{ shrink: true }}
        sx={{ maxWidth: 200 }}
      />
      <TextField
        label="To"
        type="date"
        value={to}
        onChange={(e) => onToChange(e.target.value)}
        InputLabelProps={{ shrink: true }}
        sx={{ maxWidth: 200 }}
      />
    </Stack>
  );
}
