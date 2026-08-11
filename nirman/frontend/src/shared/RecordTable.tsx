import {
  Box,
  Divider,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
  useMediaQuery,
  useTheme,
  type SxProps,
  type Theme,
} from '@mui/material';
import type { KeyboardEvent, ReactNode } from 'react';
import { inkEdge } from '../app/sketch';

/**
 * Layers a few style objects into one.
 *
 * <p>`sx` also accepts an array, but an array whose members are themselves `SxProps` does not
 * type, and every layer here — {@link inkEdge}, the local rules, a caller's `rowSx` — is a
 * plain object. The cast says so in one place rather than at four call sites.</p>
 */
function mergeSx(...parts: (SxProps<Theme> | false | undefined)[]): SxProps<Theme> {
  return Object.assign({}, ...parts.filter(Boolean)) as SxProps<Theme>;
}

/**
 * Where a column lands once the row becomes a card.
 *
 * <p>`title` is the headline — the one thing that identifies the record. `status` sits beside
 * it on the right, because a chip read at the top of a card is what tells a supervisor whether
 * to bother with the rest of it. `field` is a labelled value in the body. `actions` is the row
 * of buttons under the rule. `hidden` drops the column on a phone: a column that only exists to
 * disambiguate a wide table is noise once the record has a card to itself.</p>
 */
export type CardSlot = 'title' | 'status' | 'field' | 'actions' | 'hidden';

export interface RecordColumn<T> {
  /** Stable key. Also the fallback field label if `header` is not a string. */
  key: string;
  /** Column heading on the table, and the field label on the card. */
  header: string;
  /** Table alignment. Cards are always left-aligned — a card has no column to align against. */
  align?: 'left' | 'right';
  /** Defaults to `field`. The first column is not special; say `card: 'title'` if it is. */
  card?: CardSlot;
  /** Applied to the table cell only. */
  sx?: SxProps<Theme>;
  /**
   * The value. Rendered as-is in both layouts.
   *
   * <p>An `actions` column returns bare buttons — this component wraps them, because the
   * table wants them pushed right and the card wants them wrapping from the left.</p>
   */
  cell: (row: T) => ReactNode;
  /** Overrides `cell` on the card, for a value that only reads well in a column. */
  cardCell?: (row: T) => ReactNode;
}

interface Props<T> {
  columns: RecordColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Whole-row tap — a drawer or a drill-down. Never combined with an `actions` column. */
  onRowClick?: (row: T) => void;
  /** Shown in place of the rows when there are none. The heading row stays on the table. */
  empty?: ReactNode;
  /** Scrolls the rows inside their own box, header pinned. Applies to both layouts. */
  maxHeight?: number;
  /**
   * The table already sits inside a card. Drops the outer paper and draws the phone rows as
   * ruled blocks rather than as cards — a card inside a card reads as a mistake.
   */
  nested?: boolean;
  /** Per-row emphasis, e.g. the muted fill on a reconciliation line. */
  rowSx?: (row: T) => SxProps<Theme>;
  /** Labels the table for assistive tech, and titles nothing visually. */
  ariaLabel?: string;
}

/**
 * One list of records, drawn as a table on a desk and as cards in a hand.
 *
 * <p>Every register in the app goes through here. A phone showing a six-column table gives the
 * reader a horizontal scrollbar and a choice about which half of each row to look at, and the
 * half he needs is never the half on screen — so under `sm` each row becomes its own card with
 * the identifying line at the top, the status beside it, and the rest as labelled values that
 * wrap. The table is unchanged above `sm`; nothing about the desk view was traded for this.</p>
 *
 * <p>Columns declare where they land on the card rather than the component guessing from
 * position. Guessing gets the first column right and everything after it wrong.</p>
 */
export function RecordTable<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  empty,
  maxHeight,
  nested = false,
  rowSx,
  ariaLabel,
}: Props<T>) {
  const theme = useTheme();
  // noSsr: this is a client-only PWA. Left to its default the first paint is always the
  // desktop table, and a phone would watch the table it is not meant to get flick to cards.
  const phone = useMediaQuery(theme.breakpoints.down('sm'), { noSsr: true });

  return phone ? (
    <CardList
      columns={columns}
      rows={rows}
      rowKey={rowKey}
      {...(onRowClick ? { onRowClick } : {})}
      {...(empty === undefined ? {} : { empty })}
      {...(maxHeight === undefined ? {} : { maxHeight })}
      nested={nested}
      {...(rowSx ? { rowSx } : {})}
    />
  ) : (
    <TableView
      columns={columns}
      rows={rows}
      rowKey={rowKey}
      {...(onRowClick ? { onRowClick } : {})}
      {...(empty === undefined ? {} : { empty })}
      {...(maxHeight === undefined ? {} : { maxHeight })}
      nested={nested}
      {...(rowSx ? { rowSx } : {})}
      {...(ariaLabel ? { ariaLabel } : {})}
    />
  );
}

function TableView<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  empty,
  maxHeight,
  nested,
  rowSx,
  ariaLabel,
}: Props<T>) {
  // `hidden` is a statement about the card, not about the table — the desk view keeps every
  // column it was given.
  const visible = columns;

  const table = (
    <Table size="small" stickyHeader={maxHeight != null} aria-label={ariaLabel ?? undefined}>
      <TableHead>
        <TableRow>
          {visible.map((column) => (
            <TableCell key={column.key} align={column.align ?? 'left'}>
              {column.header}
            </TableCell>
          ))}
        </TableRow>
      </TableHead>
      <TableBody>
        {rows.map((row) => (
          <TableRow
            key={rowKey(row)}
            hover
            {...(onRowClick ? { onClick: () => onRowClick(row) } : {})}
            sx={mergeSx(onRowClick && { cursor: 'pointer' }, rowSx?.(row))}
          >
            {visible.map((column) => (
              <TableCell
                key={column.key}
                align={column.align ?? 'left'}
                {...(column.sx ? { sx: column.sx } : {})}
              >
                {column.card === 'actions' ? (
                  <Stack direction="row" spacing={1} justifyContent="flex-end">
                    {column.cell(row)}
                  </Stack>
                ) : (
                  column.cell(row)
                )}
              </TableCell>
            ))}
          </TableRow>
        ))}
        {rows.length === 0 && empty && (
          <TableRow>
            <TableCell colSpan={visible.length}>
              <Box sx={{ py: 2 }}>{empty}</Box>
            </TableCell>
          </TableRow>
        )}
      </TableBody>
    </Table>
  );

  const scrolled =
    maxHeight == null ? table : <TableContainer sx={{ maxHeight }}>{table}</TableContainer>;

  if (nested) {
    // Still boxed, because a wide nested table has to scroll inside its own card rather than
    // taking the page sideways with it.
    return <Box sx={{ overflowX: 'auto', mt: 1.5 }}>{scrolled}</Box>;
  }
  return (
    <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
      {scrolled}
    </Paper>
  );
}

function CardList<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  empty,
  maxHeight,
  nested,
  rowSx,
}: Props<T>) {
  const title = columns.find((column) => column.card === 'title');
  const status = columns.find((column) => column.card === 'status');
  const actions = columns.find((column) => column.card === 'actions');
  const fields = columns.filter(
    (column) => (column.card ?? 'field') === 'field' && column !== title,
  );

  if (rows.length === 0) {
    return empty ? (
      <Box sx={nested ? { mt: 1.5 } : mergeSx(inkEdge(0), { p: 2 })}>{empty}</Box>
    ) : null;
  }

  return (
    <Stack
      spacing={nested ? 0 : 1.5}
      sx={{
        ...(nested ? { mt: 1.5 } : {}),
        ...(maxHeight == null ? {} : { maxHeight, overflowY: 'auto' }),
      }}
    >
      {rows.map((row, index) => {
        const body = (
          <>
            {(title || status) && (
              <Stack direction="row" spacing={1} alignItems="flex-start">
                <Box sx={{ flexGrow: 1, minWidth: 0 }}>{title?.cardCell?.(row) ?? title?.cell(row)}</Box>
                {status && (
                  <Box sx={{ flexShrink: 0 }}>{status.cardCell?.(row) ?? status.cell(row)}</Box>
                )}
              </Stack>
            )}

            {fields.length > 0 && (
              <Box
                sx={{
                  display: 'grid',
                  // Two per line at any phone width worth supporting; a value that needs the
                  // full width says so by being the only thing that fits on its line.
                  gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                  columnGap: 2,
                  rowGap: 1.25,
                  mt: title || status ? 1.5 : 0,
                }}
              >
                {fields.map((column) => (
                  <Box key={column.key} sx={{ minWidth: 0 }}>
                    <Typography
                      variant="overline"
                      color="text.secondary"
                      sx={{ display: 'block', fontSize: '0.65rem' }}
                    >
                      {column.header}
                    </Typography>
                    <Box sx={{ minWidth: 0 }}>{column.cardCell?.(row) ?? column.cell(row)}</Box>
                  </Box>
                ))}
              </Box>
            )}

            {actions && (
              <>
                <Divider sx={{ mt: 1.5 }} />
                {/*
                  The buttons are not part of the row's own tap target: on a card the two
                  overlap, and a thumb landing between them should not open a drawer the
                  supervisor did not ask for.
                */}
                <Stack
                  direction="row"
                  spacing={1}
                  flexWrap="wrap"
                  useFlexGap
                  sx={{ mt: 1 }}
                  onClick={(event) => event.stopPropagation()}
                >
                  {actions.cardCell?.(row) ?? actions.cell(row)}
                </Stack>
              </>
            )}
          </>
        );

        if (nested) {
          return (
            <Box
              key={rowKey(row)}
              {...(onRowClick ? { onClick: () => onRowClick(row) } : {})}
              sx={mergeSx(
                {
                  py: 1.5,
                  borderBottom: index === rows.length - 1 ? 0 : 1,
                  borderColor: 'divider',
                },
                onRowClick && { cursor: 'pointer' },
                rowSx?.(row),
              )}
            >
              {body}
            </Box>
          );
        }

        return (
          <Paper
            key={rowKey(row)}
            elevation={0}
            {...(onRowClick
              ? {
                  onClick: () => onRowClick(row),
                  role: 'button',
                  tabIndex: 0,
                  onKeyDown: (event: KeyboardEvent) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onRowClick(row);
                    }
                  },
                }
              : {})}
            sx={mergeSx(
              // Seeded by position so a list of cards is not one wobble repeated.
              inkEdge(index),
              { p: 1.75 },
              onRowClick && { cursor: 'pointer' },
              rowSx?.(row),
            )}
          >
            {body}
          </Paper>
        );
      })}
    </Stack>
  );
}
