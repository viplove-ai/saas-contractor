import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MarkAttendancePage } from './MarkAttendancePage';
import type { Roster, Site } from './types';

const get = vi.fn();
const post = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: (...args: unknown[]) => get(...args),
      post: (...args: unknown[]) => post(...args),
    },
  };
});

const SITES: Site[] = [
  { id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block', standardShiftHours: 7 },
];

/** Two workers, one already marked and submitted so it can no longer be edited. */
const ROSTER: Roster = {
  siteId: 'site-a',
  date: '2025-06-02',
  standardShiftHours: 7,
  overtimeReasonRequiredAboveHours: 4,
  periodLocked: false,
  entries: [
    {
      workerId: 'w1',
      workerCode: 'W-101',
      workerName: 'Karam Singh',
      normalRate: 625,
      overtimeRate: 89.2857,
    },
    {
      workerId: 'w2',
      workerCode: 'W-102',
      workerName: 'Ramesh Kumar',
      normalRate: 450,
      overtimeRate: 64.2857,
      attendance: {
        id: 'a2',
        workerId: 'w2',
        attendanceDate: '2025-06-02',
        status: 'PRESENT',
        breakMinutes: 0,
        workedHours: 7,
        regularHours: 7,
        overtimeHours: 0,
        workflowStatus: 'SUBMITTED',
        version: 0,
      },
    },
  ],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MarkAttendancePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('MarkAttendancePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/attendance/roster') return Promise.resolve({ data: ROSTER });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({
      data: { accepted: 1, unchanged: 0, rejected: 0, outcomes: [] },
    });
  });

  it('shows the roster with the site shift length', async () => {
    renderPage();
    expect(await screen.findByText('Karam Singh')).toBeInTheDocument();
    expect(screen.getByText('Ramesh Kumar')).toBeInTheDocument();
    // Ramesh already has a submitted record, so the roll opens with one of two marked.
    expect(screen.getByText('1 of 2 marked')).toBeInTheDocument();
    expect(screen.getByText('Submitted')).toBeInTheDocument();
  });

  it('sends a client-generated id so a re-send cannot duplicate the row', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    const karamRow = screen.getByText('Karam Singh').closest('.MuiPaper-root') as HTMLElement;
    await user.click(within(karamRow).getByRole('button', { name: 'P' }));
    await user.click(screen.getByRole('button', { name: /Save 1 mark/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, { entries: { id: string }[] }];
    expect(url).toBe('/attendance/bulk');
    expect(body.entries).toHaveLength(1);
    expect(body.entries[0]!.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
  });

  it('marks everyone present in one tap, skipping rows that can no longer be edited', async () => {
    // Karam is unmarked and Ramesh is verified, so only Karam is still the supervisor's.
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      return Promise.resolve({
        data: {
          ...ROSTER,
          entries: ROSTER.entries.map((entry) =>
            entry.workerId === 'w2'
              ? { ...entry, attendance: { ...entry.attendance!, workflowStatus: 'VERIFIED' } }
              : entry,
          ),
        },
      });
    });
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Mark all present' }));

    expect(await screen.findByRole('button', { name: /Save 1 mark/ })).toBeInTheDocument();
  });

  it('surfaces a per-row rejection instead of failing the whole batch silently', async () => {
    post.mockResolvedValue({
      data: {
        accepted: 0,
        unchanged: 0,
        rejected: 1,
        outcomes: [
          {
            id: 'x',
            workerId: 'w1',
            outcome: 'REJECTED',
            reason: 'Karam Singh is already marked for 2025-06-02 at this site.',
          },
        ],
      },
    });
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    const karamRow = screen.getByText('Karam Singh').closest('.MuiPaper-root') as HTMLElement;
    await user.click(within(karamRow).getByRole('button', { name: 'P' }));
    await user.click(screen.getByRole('button', { name: /Save 1 mark/ }));

    expect(await screen.findByText(/1 row\(s\) were not saved/)).toBeInTheDocument();
    expect(screen.getByText(/already marked for 2025-06-02/)).toBeInTheDocument();
  });

  it('offers present and absent only', async () => {
    renderPage();
    await screen.findByText('Karam Singh');

    const karamRow = screen.getByText('Karam Singh').closest('.MuiPaper-root') as HTMLElement;
    expect(within(karamRow).getByRole('button', { name: 'P' })).toBeInTheDocument();
    expect(within(karamRow).getByRole('button', { name: 'A' })).toBeInTheDocument();
    expect(within(karamRow).queryByRole('button', { name: '½' })).not.toBeInTheDocument();
    expect(within(karamRow).queryByRole('button', { name: 'L' })).not.toBeInTheDocument();
  });

  it('fills a present day with the site shift, so one tap still records real hours', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    const karamRow = screen.getByText('Karam Singh').closest('.MuiPaper-root') as HTMLElement;
    await user.click(within(karamRow).getByRole('button', { name: 'P' }));

    // The hours are on the row and on the button, not hidden behind it.
    expect(within(karamRow).getByText(/7 h worked/)).toBeInTheDocument();
    expect(within(karamRow).getByRole('button', { name: '7 h' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Save 1 mark/ }));
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { entries: { enteredHours: number }[] }];
    expect(body.entries[0]!.enteredHours).toBe(7);
  });

  it('sends no hours for an absent day', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    const karamRow = screen.getByText('Karam Singh').closest('.MuiPaper-root') as HTMLElement;
    // Present first, so a stale seven would be there to leak if it were not cleared.
    await user.click(within(karamRow).getByRole('button', { name: 'P' }));
    await user.click(within(karamRow).getByRole('button', { name: 'A' }));

    expect(within(karamRow).queryByText(/h worked/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Save 1 mark/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [
      string,
      { entries: { status: string; enteredHours?: number }[] },
    ];
    expect(body.entries[0]!.status).toBe('ABSENT');
    expect(body.entries[0]!.enteredHours).toBeUndefined();
  });

  it('records nine hours as seven regular and two overtime', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    const karamRow = screen.getByText('Karam Singh').closest('.MuiPaper-root') as HTMLElement;
    await user.click(within(karamRow).getByRole('button', { name: 'P' }));
    await user.click(within(karamRow).getByRole('button', { name: '7 h' }));

    const hoursField = await screen.findByLabelText('Hours worked');
    await user.clear(hoursField);
    await user.type(hoursField, '9');
    expect(screen.getByText(/7 h regular · 2 h overtime/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await within(karamRow).findByText(/9 h worked · 2 h OT/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /Save 1 mark/ }));
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { entries: { enteredHours: number }[] }];
    expect(body.entries[0]!.enteredHours).toBe(9);
  });

  it('marks everyone present at the standard shift in one tap', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Mark all present' }));
    // Both men: Karam unmarked, and Ramesh submitted but not yet verified.
    await user.click(screen.getByRole('button', { name: /Save 2 mark/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { entries: { enteredHours: number }[] }];
    expect(body.entries).toHaveLength(2);
    expect(body.entries.map((e) => e.enteredHours)).toEqual([7, 7]);
  });

  it('lets the supervisor correct his own row while it is still only submitted', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Ramesh Kumar');

    // Ramesh is submitted and not yet verified, so he is still the supervisor's to fix.
    const rameshRow = screen.getByText('Ramesh Kumar').closest('.MuiPaper-root') as HTMLElement;
    expect(within(rameshRow).getByText('Submitted')).toBeInTheDocument();
    expect(within(rameshRow).getByRole('button', { name: 'P' })).toBeEnabled();

    await user.click(within(rameshRow).getByRole('button', { name: 'A' }));
    await user.click(screen.getByRole('button', { name: /Save 1 mark/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { entries: { status: string }[] }];
    expect(body.entries[0]!.status).toBe('ABSENT');
  });

  it('closes a verified row to the supervisor', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      return Promise.resolve({
        data: {
          ...ROSTER,
          entries: ROSTER.entries.map((entry) =>
            entry.workerId === 'w2'
              ? { ...entry, attendance: { ...entry.attendance!, workflowStatus: 'VERIFIED' } }
              : entry,
          ),
        },
      });
    });
    renderPage();
    await screen.findByText('Ramesh Kumar');

    const rameshRow = screen.getByText('Ramesh Kumar').closest('.MuiPaper-root') as HTMLElement;
    expect(within(rameshRow).getByText('Verified')).toBeInTheDocument();
    expect(within(rameshRow).getByRole('button', { name: 'P' })).toBeDisabled();
    expect(within(rameshRow).getByRole('button', { name: 'A' })).toBeDisabled();
  });

  it('blocks entry and says why when the month is closed', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      return Promise.resolve({ data: { ...ROSTER, periodLocked: true } });
    });
    renderPage();

    expect(await screen.findByText(/This month is closed for attendance/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark all present' })).toBeDisabled();
  });
});
