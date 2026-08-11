import { ThemeProvider } from '@mui/material/styles';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
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

/**
 * jsdom answers every media query with `false`, which is the desk layout. A phone is simulated
 * by making the query the component asks about — and only that one — match.
 */
function asPhone() {
  vi.stubGlobal(
    'matchMedia',
    (query: string) =>
      ({
        matches: query.includes('max-width'),
        media: query,
        onchange: null,
        addListener: () => {},
        removeListener: () => {},
        addEventListener: () => {},
        removeEventListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  );
}

function renderTable(props: Partial<Parameters<typeof RecordTable<Row>>[0]> = {}) {
  return render(
    <ThemeProvider theme={theme}>
      <RecordTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} {...props} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('RecordTable', () => {
  it('is a table on a desk', () => {
    renderTable();
    expect(screen.getByRole('table')).toBeInTheDocument();
    // One heading row and one row per record.
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.getByRole('columnheader', { name: 'Worker' })).toBeInTheDocument();
  });

  it('is a card per record in a hand', () => {
    asPhone();
    renderTable();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getByText('Ramesh Kumar')).toBeInTheDocument();
    expect(screen.getByText('Suresh Yadav')).toBeInTheDocument();
  });

  it('keeps every column heading as a field label on the card', () => {
    asPhone();
    renderTable();
    // The heading a value sits under is what makes ₹625 a rate rather than an amount.
    expect(screen.getAllByText('Site')).toHaveLength(2);
    expect(screen.getAllByText('Rate')).toHaveLength(2);
    expect(screen.getByText('₹625')).toBeInTheDocument();
  });

  it('does not label the title or the status — a card has no room for a heading nobody reads', () => {
    asPhone();
    renderTable();
    expect(screen.queryByText('Worker')).not.toBeInTheDocument();
    expect(screen.queryByText('Status')).not.toBeInTheDocument();
    // The status itself is still there, beside the name.
    expect(screen.getByText('Working')).toBeInTheDocument();
  });

  it('drops a column marked hidden from the card and keeps it on the table', () => {
    const columns: RecordColumn<Row>[] = [
      ...COLUMNS.slice(0, 2).map((column) => column),
      { key: 'rate', header: 'Rate', card: 'hidden', cell: (row: Row) => row.rate },
    ];
    const { unmount } = renderTable({ columns });
    expect(screen.getByRole('columnheader', { name: 'Rate' })).toBeInTheDocument();
    unmount();

    asPhone();
    renderTable({ columns });
    expect(screen.queryByText('₹625')).not.toBeInTheDocument();
  });

  it('opens a record from either layout', async () => {
    const onRowClick = vi.fn();
    const user = userEvent.setup();

    const { unmount } = renderTable({ onRowClick });
    await user.click(screen.getByText('Ramesh Kumar'));
    expect(onRowClick).toHaveBeenCalledWith(ROWS[0]);
    unmount();

    asPhone();
    renderTable({ onRowClick });
    await user.click(screen.getAllByRole('button')[1]!);
    expect(onRowClick).toHaveBeenCalledWith(ROWS[1]);
  });

  it('does not open the record when a button on the card is pressed', async () => {
    const onRowClick = vi.fn();
    const onEdit = vi.fn();
    const user = userEvent.setup();
    asPhone();

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

    await user.click(screen.getAllByRole('button', { name: 'Edit' })[0]!);
    expect(onEdit).toHaveBeenCalledWith('1');
    expect(onRowClick).not.toHaveBeenCalled();
  });

  it('says why the list is empty rather than showing nothing', () => {
    const empty = <span>Nobody on your sites yet.</span>;

    const { unmount } = renderTable({ rows: [], empty });
    expect(within(screen.getByRole('table')).getByText('Nobody on your sites yet.')).toBeInTheDocument();
    unmount();

    asPhone();
    renderTable({ rows: [], empty });
    expect(screen.getByText('Nobody on your sites yet.')).toBeInTheDocument();
  });
});
