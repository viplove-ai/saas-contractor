import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionUser } from '../../shared/session';
import { VerifyAttendancePage } from './VerifyAttendancePage';
import type { AttendanceListRow, PageResponse, Site } from './types';

const get = vi.fn();
const post = vi.fn();
let permissions: string[] = ['attendance:verify', 'attendance:correct'];

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

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { permissions } as SessionUser,
    initialising: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [
  { id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block', standardShiftHours: 7 },
];

function row(id: string, workerName: string, overrides: Partial<AttendanceListRow> = {}) {
  return {
    id,
    siteId: 'site-a',
    workerId: `w-${id}`,
    workerName,
    attendanceDate: '2025-06-02',
    status: 'PRESENT',
    breakMinutes: 0,
    workedHours: 9,
    regularHours: 7,
    overtimeHours: 2,
    workflowStatus: 'SUBMITTED',
    version: 0,
    ...overrides,
  } satisfies AttendanceListRow;
}

function queue(...content: AttendanceListRow[]): PageResponse<AttendanceListRow> {
  return {
    content,
    page: 0,
    size: 200,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  };
}

const QUEUE = queue(row('a1', 'Karam Singh'), row('a2', 'Ramesh Kumar'));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <VerifyAttendancePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('VerifyAttendancePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['attendance:verify', 'attendance:correct'];
    get.mockImplementation((url: string, config?: { params: { status: string } }) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/attendance') {
        return Promise.resolve({
          data:
            config?.params.status === 'VERIFIED'
              ? queue(row('v1', 'Naresh Kumar', { workflowStatus: 'VERIFIED', enteredHours: 7 }))
              : QUEUE,
        });
      }
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
    post.mockResolvedValue({ data: { affected: 1, action: 'VERIFY' } });
  });

  it('asks the server only for submitted rows at the chosen site', async () => {
    renderPage();
    await screen.findByText('Karam Singh');

    const [, config] = get.mock.calls.find(([url]) => url === '/attendance') as [
      string,
      { params: Record<string, unknown> },
    ];
    expect(config.params.status).toBe('SUBMITTED');
    expect(config.params.siteId).toBe('site-a');
  });

  it('verifies the selected rows and says what verification did', async () => {
    post.mockResolvedValue({ data: { affected: 2, action: 'VERIFY' } });
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Select all' }));
    await user.click(screen.getByRole('button', { name: /Verify 2 record/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, { ids: string[]; action: string }];
    expect(url).toBe('/attendance/verify');
    expect(body.action).toBe('VERIFY');
    expect(body.ids).toEqual(['a1', 'a2']);
    expect(await screen.findByText(/posted to the worker ledger/)).toBeInTheDocument();
  });

  it('will not send rows back without a reason for the supervisor', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(
      screen.getByRole('checkbox', { name: 'Select Karam Singh on 2025-06-02' }),
    );
    expect(screen.getByRole('button', { name: /Send back 1 record/ })).toBeDisabled();

    await user.type(screen.getByLabelText('Remarks'), 'Check-out time is missing.');
    const sendBack = screen.getByRole('button', { name: /Send back 1 record/ });
    expect(sendBack).toBeEnabled();

    await user.click(sendBack);
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { action: string; remarks: string }];
    expect(body.action).toBe('REJECT');
    expect(body.remarks).toBe('Check-out time is missing.');
  });

  it('selects only the row that was ticked', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(
      screen.getByRole('checkbox', { name: 'Select Ramesh Kumar on 2025-06-02' }),
    );
    await user.click(screen.getByRole('button', { name: /Verify 1 record/ }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [string, { ids: string[] }];
    expect(body.ids).toEqual(['a2']);
  });

  it('says so plainly when there is nothing to verify', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      return Promise.resolve({ data: queue() });
    });
    renderPage();

    expect(await screen.findByText(/Nothing is waiting for verification/)).toBeInTheDocument();
  });

  it('corrects a verified day and sends the reason and version with it', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Verified' }));
    await screen.findByText('Naresh Kumar');
    // Signing off is not on offer for a day already paid.
    expect(screen.queryByRole('button', { name: /^Verify \d/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Correct' }));
    const hoursField = await screen.findByLabelText('Hours worked');
    await user.clear(hoursField);
    await user.type(hoursField, '9');

    // A reason is not optional when money moves.
    const apply = screen.getByRole('button', { name: 'Apply correction' });
    expect(apply).toBeDisabled();
    await user.type(
      screen.getByLabelText('Reason for the correction'),
      'Gate register shows he left at seven.',
    );
    expect(apply).toBeEnabled();

    await user.click(apply);
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { enteredHours: number; correctionReason: string; version: number },
    ];
    expect(url).toBe('/attendance/v1/correct');
    expect(body.enteredHours).toBe(9);
    expect(body.correctionReason).toBe('Gate register shows he left at seven.');
    expect(body.version).toBe(0);
  });

  it('will not apply a correction that changes nothing', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Verified' }));
    await screen.findByText('Naresh Kumar');
    await user.click(screen.getByRole('button', { name: 'Correct' }));

    await screen.findByText(/Same as the verified figure/);
    expect(screen.getByRole('button', { name: 'Apply correction' })).toBeDisabled();
  });

  it('offers no correction to a verifier who cannot correct', async () => {
    permissions = ['attendance:verify'];
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByText('Karam Singh');

    await user.click(screen.getByRole('button', { name: 'Verified' }));
    await screen.findByText('Naresh Kumar');

    expect(screen.queryByRole('button', { name: 'Correct' })).not.toBeInTheDocument();
  });

  it('shows nothing to verify to a role without the permission', async () => {
    permissions = ['attendance:create'];
    renderPage();

    expect(
      await screen.findByText(/Verifying attendance is not part of your role/),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Site')).not.toBeInTheDocument();
  });
});
