import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import {
  Box,
  Chip,
  Stack,
  TextField,
  Tooltip,
  Typography,
  useTheme,
} from '@mui/material';
import { useMediaQuery } from '@mui/material';
import { useVirtualizer } from '@tanstack/react-virtual';
import { memo, useCallback, useEffect, useMemo, useRef } from 'react';
import { inkEdge } from '../../app/sketch';
import type { PreviewBoqLine } from './types';

interface Props {
  lines: PreviewBoqLine[];
  onChange: (index: number, patch: Partial<PreviewBoqLine>) => void;
  disabled?: boolean;
}

/** Rupees the way a contractor reads them: 42,26,546. */
const INR = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });

/**
 * Row height is fixed so the virtualiser can size the scroll area without measuring. Two
 * lines of description at the default body font, plus padding.
 */
const ROW_HEIGHT = 68;

/**
 * The same row as a card: the item and its description, then quantity and rate as full-width
 * fields, then the amount. Taller than the desk row and still fixed, so the virtualiser keeps
 * working on the phone rather than being switched off exactly where the machine is slowest.
 */
const PHONE_ROW_HEIGHT = 232;

/** Beyond this the list is windowed; below it the DOM cost is not worth the machinery. */
const VIRTUALISE_ABOVE = 40;

const COLUMNS = '110px minmax(220px, 1fr) 110px 90px 120px 130px';

/**
 * The extracted schedule, laid out for checking rather than for data entry.
 *
 * <p>A tender schedule runs to three hundred lines, and the common case is that the reader
 * got them all right. So the table is built for scanning: the rows that need attention —
 * a unit that had to be invented, a printed amount that disagrees with quantity × rate, a
 * reconciliation placeholder — are marked, and everything else is quiet.</p>
 *
 * <p>Rows are held by the parent outside React Hook Form and edited through {@code onChange}.
 * Three hundred registered fields re-validate the whole form on every keystroke, which on a
 * site laptop is the difference between typing and waiting.</p>
 */
export function NitBoqReviewTable({ lines, onChange, disabled = false }: Props) {
  const theme = useTheme();
  const phone = useMediaQuery(theme.breakpoints.down('sm'), { noSsr: true });
  const scrollRef = useRef<HTMLDivElement>(null);
  const rowHeight = phone ? PHONE_ROW_HEIGHT : ROW_HEIGHT;

  const virtualiser = useVirtualizer({
    count: lines.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    overscan: 8,
  });

  // A rotated phone changes the row height under a list the virtualiser has already sized.
  useEffect(() => virtualiser.measure(), [rowHeight, virtualiser]);

  const windowed = lines.length > VIRTUALISE_ABOVE;
  const rows = windowed
    ? virtualiser.getVirtualItems()
    : lines.map((_, index) => ({ index, key: index, start: index * rowHeight }));

  const total = useMemo(
    () => lines.reduce((sum, line) => sum + (line.quantity ?? 0) * (line.rate ?? 0), 0),
    [lines],
  );

  return (
    <Stack spacing={1}>
      {/* A column heading with no column under it is noise; the card labels its own fields. */}
      {!phone && (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: COLUMNS,
            gap: 1,
            px: 1,
            py: 0.5,
            borderBottom: 1,
            borderColor: 'divider',
            typography: 'caption',
            color: 'text.secondary',
            fontWeight: 600,
          }}
        >
          <span>Item</span>
          <span>Description</span>
          <span>Quantity</span>
          <span>Unit</span>
          <span>Rate</span>
          <span style={{ textAlign: 'right' }}>Amount</span>
        </Box>
      )}

      <Box
        ref={scrollRef}
        sx={{
          maxHeight: 420,
          overflowY: 'auto',
          // Wide content scrolls inside its own box rather than pushing the dialog sideways.
          overflowX: 'auto',
        }}
      >
        <Box
          sx={{
            position: 'relative',
            height: windowed ? virtualiser.getTotalSize() : 'auto',
            minWidth: phone ? 0 : 780,
          }}
        >
          {rows.map((row) => {
            const line = lines[row.index];
            if (!line) {
              return null;
            }
            return (
              <Box
                key={row.index}
                sx={
                  windowed
                    ? {
                        position: 'absolute',
                        top: 0,
                        left: 0,
                        width: '100%',
                        transform: `translateY(${row.start}px)`,
                      }
                    : {}
                }
              >
                <BoqRow
                  line={line}
                  onChange={onChange}
                  disabled={disabled}
                  mutedBackground={theme.palette.action.hover}
                  phone={phone}
                />
              </Box>
            );
          })}
        </Box>
      </Box>

      <Stack
        direction="row"
        justifyContent="space-between"
        sx={{ px: 1, pt: 1, borderTop: 1, borderColor: 'divider' }}
      >
        <Typography variant="body2" color="text.secondary">
          {lines.length} lines
        </Typography>
        <Typography variant="body2" fontWeight={600}>
          ₹{INR.format(total)}
        </Typography>
      </Stack>
    </Stack>
  );
}

interface RowProps {
  line: PreviewBoqLine;
  onChange: (index: number, patch: Partial<PreviewBoqLine>) => void;
  disabled: boolean;
  mutedBackground: string;
  phone: boolean;
}

/**
 * One row. Memoised on the fields it renders, so editing a quantity on row 3 does not
 * re-render the other 299.
 */
const BoqRow = memo(function BoqRow({
  line,
  onChange,
  disabled,
  mutedBackground,
  phone,
}: RowProps) {
  const setQuantity = useCallback(
    (value: string) => onChange(line.index, { quantity: value === '' ? null : Number(value) }),
    [line.index, onChange],
  );
  const setRate = useCallback(
    (value: string) => onChange(line.index, { rate: value === '' ? null : Number(value) }),
    [line.index, onChange],
  );

  const derived = (line.quantity ?? 0) * (line.rate ?? 0);
  // The tender rounds per line, so a rupee or two is normal; more means the row was misread.
  const printedDisagrees =
    line.amount != null && Math.abs(line.amount - derived) > 1 && !line.synthetic;

  const unit = (
    <Stack direction="row" spacing={0.5} alignItems="center" sx={{ minWidth: 0 }}>
      <Typography variant="body2" noWrap>
        {line.unitCode}
      </Typography>
      {!line.unitRecognised && (
        <Tooltip
          title={`"${line.unit ?? ''}" is not in your master data and will be added on import.`}
        >
          <WarningAmberIcon fontSize="small" color="warning" />
        </Tooltip>
      )}
    </Stack>
  );

  if (phone) {
    return (
      <Box sx={{ px: 0.5, py: 0.75 }}>
        <Box
          sx={{
            ...(inkEdge(line.index) as object),
            p: 1.5,
            ...(line.synthetic ? { backgroundColor: mutedBackground } : {}),
          }}
        >
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
            <Typography variant="body2" fontFamily="monospace" noWrap sx={{ flexGrow: 1 }}>
              {line.itemNumber}
            </Typography>
            {line.synthetic && <Chip size="small" label="reconciliation" sx={{ height: 18 }} />}
          </Stack>

          <Typography
            variant="body2"
            sx={{
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
              mb: 1,
            }}
          >
            {line.description}
          </Typography>

          <Stack direction="row" spacing={1}>
            <TextField
              size="small"
              type="number"
              label="Quantity"
              value={line.quantity ?? ''}
              onChange={(event) => setQuantity(event.target.value)}
              disabled={disabled || line.synthetic}
              inputProps={{ 'aria-label': `Quantity for item ${line.itemNumber}`, min: 0 }}
            />
            <TextField
              size="small"
              type="number"
              label="Rate"
              value={line.rate ?? ''}
              onChange={(event) => setRate(event.target.value)}
              disabled={disabled || line.synthetic}
              inputProps={{ 'aria-label': `Rate for item ${line.itemNumber}`, min: 0 }}
            />
          </Stack>

          <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mt: 1 }}>
            {unit}
            <Stack alignItems="flex-end" sx={{ minWidth: 0 }}>
              <Typography variant="body2" fontWeight={600} noWrap>
                ₹{INR.format(derived)}
              </Typography>
              {printedDisagrees && (
                <Typography variant="caption" color="warning.main" noWrap>
                  printed ₹{INR.format(line.amount ?? 0)}
                </Typography>
              )}
            </Stack>
          </Stack>
        </Box>
      </Box>
    );
  }

  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: COLUMNS,
        gap: 1,
        alignItems: 'center',
        px: 1,
        py: 1,
        minHeight: ROW_HEIGHT,
        borderBottom: 1,
        borderColor: 'divider',
        ...(line.synthetic ? { backgroundColor: mutedBackground } : {}),
      }}
    >
      <Stack spacing={0.25} sx={{ minWidth: 0 }}>
        <Typography variant="body2" fontFamily="monospace" noWrap title={line.itemNumber}>
          {line.itemNumber}
        </Typography>
        {line.synthetic && <Chip size="small" label="reconciliation" sx={{ height: 18 }} />}
      </Stack>

      <Tooltip title={line.description ?? ''} enterDelay={600}>
        <Typography
          variant="body2"
          sx={{
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
          }}
        >
          {line.description}
        </Typography>
      </Tooltip>

      <TextField
        size="small"
        type="number"
        value={line.quantity ?? ''}
        onChange={(event) => setQuantity(event.target.value)}
        disabled={disabled || line.synthetic}
        inputProps={{ 'aria-label': `Quantity for item ${line.itemNumber}`, min: 0 }}
      />

      {unit}

      <TextField
        size="small"
        type="number"
        value={line.rate ?? ''}
        onChange={(event) => setRate(event.target.value)}
        disabled={disabled || line.synthetic}
        inputProps={{ 'aria-label': `Rate for item ${line.itemNumber}`, min: 0 }}
      />

      <Stack alignItems="flex-end" sx={{ minWidth: 0 }}>
        <Typography variant="body2" noWrap>
          ₹{INR.format(derived)}
        </Typography>
        {printedDisagrees && (
          <Tooltip title={`The notice printed ₹${INR.format(line.amount ?? 0)} for this line.`}>
            <Typography variant="caption" color="warning.main" noWrap>
              printed ₹{INR.format(line.amount ?? 0)}
            </Typography>
          </Tooltip>
        )}
      </Stack>
    </Box>
  );
});
