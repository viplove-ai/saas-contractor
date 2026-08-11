import { ThemeProvider } from '@mui/material/styles';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { theme } from '../app/theme';
import { RecordTable, type RecordColumn } from './RecordTable';

interface Row {
  id: string;
  name: string;
  site: string;
  rate: string;
  status: string;
}

const ROWS: Row[] = [
  { id: '1', name: 'Ramesh Kumar', site: 'KSN-A', rate: '₹625', status: 'Working' },
  { id: '2', name: 'Suresh Yadav', site: 'KSN-B', rate: '₹540', status: 'Left' },
];

const COLUMNS: RecordColumn<Row>[] = [
  { key: 'name', header: 'Worker', card: 'title', cell: (row) => row.name },
  { key: 'site', header: 'Site', cell: (row) => row.site },
  { key: 'rate', header: 'Rate', align: 'right', cell: (row) => row.rate },
  { key: 'status', header: 'Status', card: 'status', cell: (row) => row.status },
];

function renderTable(props: Partial<Parameters<typeof RecordTable<Row>>[0]> = {}) {
  return render(
    <ThemeProvider theme={theme}>
      <RecordTable
        columns={COLUMNS}
        rows={ROWS}
        rowKey={(row) => row.id}
        ariaLabel="Workers"
        {...props}
      />
    </ThemeProvider>,
  );
}

/** The cards. A list, so a screen reader counts the records rather than reading a wall. */
const cards = () => screen.getByRole('list', { name: 'Workers' });
const table = () => screen.getByRole('table', { name: 'Workers' });

describe('RecordTable', () => {
  it('draws both layouts and lets a breakpoint choose, rather than a hook', () => {
    renderTable();
    /*
      Both are in the DOM at once and a CSS breakpoint hides one. A JS media query would
      answer wrongly on the first paint and correct itself, which on a phone is a visible
      flick from a table to a list.

      Which one is *shown* is not asserted here: jsdom applies no layout and answers media
      queries against a fixed 1024px window, so a computed `display` proves nothing about a
      375px phone. The breakpoint itself is covered by looking at the app, not by this file.
    */
    expect(cards()).toBeInTheDocument();
    expect(table()).toBeInTheDocument();
    expect(cards()).not.toContainElement(table());
  });

  it('gives each record a card carrying every column the table has', () => {
    renderTable();
    const items = within(cards()).getAllByRole('listitem');
    expect(items).toHaveLength(2);

    const first = within(items[0]!);
    expect(first.getByText('Ramesh Kumar')).toBeInTheDocument();
    // The heading a value sits under is what makes ₹625 a rate rather than an amount.
    expect(first.getByText('Site')).toBeInTheDocument();
    expect(first.getByText('KSN-A')).toBeInTheDocument();
    expect(first.getByText('Rate')).toBeInTheDocument();
    expect(first.getByText('₹625')).toBeInTheDocument();
    expect(first.getByText('Working')).toBeInTheDocument();
  });

  it('does not label the title or the status — a card has no room for a heading nobody reads', () => {
    renderTable();
    const first = within(within(cards()).getAllByRole('listitem')[0]!);
    expect(first.queryByText('Worker')).not.toBeInTheDocument();
    expect(first.queryByText('Status')).not.toBeInTheDocument();
    // Both are still headings on the table.
    expect(within(table()).getByRole('columnheader', { name: 'Worker' })).toBeInTheDocument();
  });

  it('leaves the desk view a plain table', () => {
    renderTable();
    // One heading row and one row per record.
    expect(within(table()).getAllByRole('row')).toHaveLength(3);
    expect(within(table()).getByRole('columnheader', { name: 'Rate' })).toBeInTheDocument();
  });

  it('drops a column marked hidden from the card and keeps it on the table', () => {
    const columns: RecordColumn<Row>[] = [
      COLUMNS[0]!,
      COLUMNS[1]!,
      { key: 'rate', header: 'Rate', card: 'hidden', cell: (row: Row) => row.rate },
    ];
    renderTable({ columns });

    expect(within(cards()).queryByText('₹625')).not.toBeInTheDocument();
    expect(within(table()).getByText('₹625')).toBeInTheDocument();
  });

  it('opens a record from either layout', async () => {
    const onRowClick = vi.fn();
    const user = userEvent.setup();
    renderTable({ onRowClick });

    await user.click(within(table()).getByText('Ramesh Kumar'));
    expect(onRowClick).toHaveBeenCalledWith(ROWS[0]);

    // The card's tap target is inside the list item, so the item keeps its listitem role.
    const second = within(within(cards()).getAllByRole('listitem')[1]!);
    await user.click(second.getByRole('button'));
    expect(onRowClick).toHaveBeenCalledWith(ROWS[1]);
  });

  it('does not open the record when a button on the card is pressed', async () => {
    const onRowClick = vi.fn();
    const onEdit = vi.fn();
    const user = userEvent.setup();

    renderTable({
      onRowClick,
      columns: [
        ...COLUMNS,
        {
          key: 'actions',
          header: 'Actions',
          align: 'right',
          card: 'actions',
          cell: (row: Row) => <button onClick={() => onEdit(row.id)}>Edit</button>,
        },
      ],
    });

    await user.click(within(cards()).getAllByRole('button', { name: 'Edit' })[0]!);
    expect(onEdit).toHaveBeenCalledWith('1');
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('says why the list is empty on both layouts rather than showing nothing', () => {
    const empty = <span>Nobody on your sites yet.</span>;
    renderTable({ rows: [], empty });

    expect(within(cards()).getByText('Nobody on your sites yet.')).toBeInTheDocument();
    expect(within(table()).getByText('Nobody on your sites yet.')).toBeInTheDocument();
  });
});
