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
  type SxProps,
  type Theme,
} from '@mui/material';
import type { KeyboardEvent, ReactNode } from 'react';

/**
 * Layers a few style objects into one.
 *
 * <p>`sx` also accepts an array, but an array whose members are themselves `SxProps` does not
 * type, and every layer here is a plain object. The cast says so in one place rather than at
 * four call sites.</p>
 */
function mergeSx(...parts: (SxProps<Theme> | false | undefined)[]): SxProps<Theme> {
  return Object.assign({}, ...parts.filter(Boolean)) as SxProps<Theme>;
}

/** Below this the register is cards; at and above it, the table. Matches the two registers
 *  that arrived at the same answer by hand — projects and sites. */
const CARDS_BELOW = 'md';

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
  /** Stable key. */
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
  /** Names the register for assistive tech, on both layouts. Titles nothing visually. */
  ariaLabel?: string;
}

/**
 * One list of records, drawn as a table on a desk and as a card per record in a hand.
 *
 * <p>Every register in the app goes through here. A phone showing a six-column table gives the
 * reader a horizontal scrollbar and a choice about which half of each row to look at, and the
 * half he needs is never the half on screen — so below {@link CARDS_BELOW} each row becomes its
 * own card with the identifying line at the top, the status beside it, and the rest as labelled
 * values. The table is untouched above it.</p>
 *
 * <p>This is the projects and sites registers' answer, generalised. Both arrived at it by hand
 * and both keep their own card, because each makes editorial choices a generic component has no
 * way to make — which field is worth an ellipsis, which pair belongs on one line. What is shared
 * here is the mechanism and the shape, so a register that has no such choices to make gets the
 * same card without a bespoke component to maintain.</p>
 *
 * <p>Both layouts are always rendered and one is hidden by a CSS breakpoint, the way those two
 * registers do it. A JS media query would be a hook that answers wrongly on the first paint and
 * corrects itself, which on a phone is a visible flick from a table to a list.</p>
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
  const title = columns.find((column) => column.card === 'title');
  const status = columns.find((column) => column.card === 'status');
  const actions = columns.find((column) => column.card === 'actions');
  const fields = columns.filter(
    (column) => (column.card ?? 'field') === 'field' && column !== title,
  );

  const table = (
    <Table size="small" stickyHeader={maxHeight != null} aria-label={ariaLabel ?? undefined}>
      <TableHead>
        <TableRow>
          {/* `hidden` is a statement about the card. The desk view keeps every column. */}
          {columns.map((column) => (
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
            {columns.map((column) => (
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
            <TableCell colSpan={columns.length}>
              <Box sx={{ py: 2 }}>{empty}</Box>
            </TableCell>
          </TableRow>
        )}
      </TableBody>
    </Table>
  );

  const scrolled =
    maxHeight == null ? table : <TableContainer sx={{ maxHeight }}>{table}</TableContainer>;

  return (
    <>
      <Stack
        component="ul"
        {...(ariaLabel ? { 'aria-label': ariaLabel } : {})}
        spacing={nested ? 0 : 2}
        sx={{
          display: { xs: 'flex', [CARDS_BELOW]: 'none' },
          listStyle: 'none',
          m: 0,
          p: 0,
          ...(nested ? { mt: 1.5 } : {}),
          ...(maxHeight == null ? {} : { maxHeight, overflowY: 'auto' }),
        }}
      >
        {rows.map((row, index) => (
          <RecordCard
            key={rowKey(row)}
            row={row}
            index={index}
            total={rows.length}
            nested={nested}
            {...(title ? { title } : {})}
            {...(status ? { status } : {})}
            {...(actions ? { actions } : {})}
            fields={fields}
            {...(onRowClick ? { onRowClick } : {})}
            {...(rowSx ? { rowSx } : {})}
          />
        ))}
        {rows.length === 0 && empty && (
          <Box component="li" sx={nested ? { mt: 1.5 } : { py: 1 }}>
            {empty}
          </Box>
        )}
      </Stack>

      {nested ? (
        // Still boxed: a wide nested table has to scroll inside its own card rather than
        // taking the page sideways with it.
        <Box sx={{ display: { xs: 'none', [CARDS_BELOW]: 'block' }, overflowX: 'auto', mt: 1.5 }}>
          {scrolled}
        </Box>
      ) : (
        <Paper
          elevation={0}
          sx={{
            display: { xs: 'none', [CARDS_BELOW]: 'block' },
            border: 1,
            borderColor: 'divider',
            overflowX: 'auto',
          }}
        >
          {scrolled}
        </Paper>
      )}
    </>
  );
}

interface CardProps<T> {
  row: T;
  index: number;
  total: number;
  nested: boolean;
  title?: RecordColumn<T>;
  status?: RecordColumn<T>;
  actions?: RecordColumn<T>;
  fields: RecordColumn<T>[];
  onRowClick?: (row: T) => void;
  rowSx?: (row: T) => SxProps<Theme>;
}

/** One record on a phone. Same paper and rule as the projects and sites cards. */
function RecordCard<T>({
  row,
  index,
  total,
  nested,
  title,
  status,
  actions,
  fields,
  onRowClick,
  rowSx,
}: CardProps<T>) {
  const body = (
    <>
      {(title || status) && (
        <Stack direction="row" spacing={1} alignItems="flex-start">
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            {title?.cardCell?.(row) ?? title?.cell(row)}
          </Box>
          {status && (
            <Box sx={{ flexShrink: 0 }}>{status.cardCell?.(row) ?? status.cell(row)}</Box>
          )}
        </Stack>
      )}

      {fields.length > 0 && (
        <Box
          sx={{
            display: 'grid',
            // Two per line at any phone width worth supporting; a value that needs the full
            // width says so by being the only thing that fits on its line.
            gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
            columnGap: 2,
            rowGap: 1.25,
            mt: title || status ? 1.5 : 0,
          }}
        >
          {fields.map((column) => (
            <Box key={column.key} sx={{ minWidth: 0 }}>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
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
            The buttons are not part of the row's own tap target: on a card the two overlap,
            and a thumb landing between them should not open a drawer nobody asked for.
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

  /*
    The tap target sits inside the list item rather than on it. Putting `role="button"` on the
    <li> replaces its listitem role, and a screen reader then reads a pile of buttons instead
    of "list, 12 items" — the count is the first useful thing it can say about a register.
  */
  const content = onRowClick ? (
    <Box
      role="button"
      tabIndex={0}
      onClick={() => onRowClick(row)}
      onKeyDown={(event: KeyboardEvent) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onRowClick(row);
        }
      }}
      sx={{ cursor: 'pointer', textAlign: 'left', width: '100%' }}
    >
      {body}
    </Box>
  ) : (
    body
  );

  if (nested) {
    return (
      <Box
        component="li"
        sx={mergeSx(
          {
            py: 1.5,
            borderBottom: index === total - 1 ? 0 : 1,
            borderColor: 'divider',
          },
          rowSx?.(row),
        )}
      >
        {content}
      </Box>
    );
  }

  return (
    <Paper
      component="li"
      elevation={0}
      sx={mergeSx({ p: 2, border: 1, borderColor: 'divider' }, rowSx?.(row))}
    >
      {content}
    </Paper>
  );
}
