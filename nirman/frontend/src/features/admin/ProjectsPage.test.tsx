import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProjectsPage } from './ProjectsPage';
import type { AdminProject, AdminSite, AdminUser, PageResponse, ProjectStatus } from './types';

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: (...args: unknown[]) => get(...args),
      post: (...args: unknown[]) => post(...args),
      put: (...args: unknown[]) => put(...args),
    },
  };
});

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions: ['project:read', 'project:write'] },
    hasPermission: (code: string) => ['project:read', 'project:write'].includes(code),
  }),
}));

const PROJECTS: AdminProject[] = [
  {
    id: 'p1',
    code: 'KSN01',
    name: 'Kausani Guest House Extension',
    clientDepartment: 'CPWD',
    contractValue: 18500000,
    status: 'ACTIVE',
    version: 1,
  },
  {
    id: 'p2',
    code: 'BAG02',
    name: 'Bageshwar Road Works',
    status: 'PLANNED',
    version: 0,
  },
];

const SITES: AdminSite[] = [
  {
    id: 'site-a',
    projectId: 'p1',
    code: 'KSN-A',
    name: 'Kausani Main Block',
    status: 'ACTIVE',
    standardShiftHours: 7,
    monthlyWageDays: 26,
    usesOutsourcedLabour: false,
    siteEngineerIds: [],
    supervisorIds: [],
    version: 0,
  },
];

const USERS: PageResponse<AdminUser> = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

function renderPage(projects: AdminProject[] = PROJECTS) {
  get.mockImplementation((url: string) => {
    if (url === '/projects') return Promise.resolve({ data: { ...USERS, content: projects } });
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/users') return Promise.resolve({ data: USERS });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ProjectsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Both views are rendered and CSS decides which one a screen sees, so jsdom — which does not
 * resolve the breakpoint — holds both at once. Every query has to say which it means.
 */
const table = () => within(screen.getByRole('table'));
const cards = () => within(screen.getByRole('list', { name: 'Projects' }));

describe('ProjectsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    post.mockResolvedValue({ data: {} });
    put.mockResolvedValue({ data: {} });
  });

  it('heads the page with the order book, banded by where each contract stands', async () => {
    const priced = (id: string, status: ProjectStatus, quotedCost: number): AdminProject => ({
      id,
      code: id.toUpperCase(),
      name: 'A contract',
      status,
      quotedCost,
      version: 0,
    });
    renderPage([
      priced('p1', 'ACTIVE', 16187500),
      priced('p3', 'ACTIVE', 5000000),
      priced('p4', 'COMPLETED', 2500000),
    ]);
    await screen.findAllByText('P1');

    const strip = within(screen.getByRole('region', { name: 'Order book' }));
    const inHand = strip.getByText('Work in hand').closest('div') as HTMLElement;
    // The two running contracts added, and read as a contractor reads it.
    expect(within(inHand).getByText('\u20b92.12 Cr')).toBeInTheDocument();
    expect(within(inHand).getByText('2 projects')).toBeInTheDocument();

    const done = strip.getByText('Completed').closest('div') as HTMLElement;
    expect(within(done).getByText('\u20b925 L')).toBeInTheDocument();
    expect(within(done).getByText('1 project')).toBeInTheDocument();
  });

  it('says how many projects are counted in the strip but not totalled', async () => {
    // Neither fixture carries a quoted cost, so the headline is zero and has to explain why.
    renderPage();
    await screen.findAllByText('KSN01');

    expect(
      screen.getByText(
        '2 projects carry no quoted value: they are counted above but not totalled.',
      ),
    ).toBeInTheDocument();
  });

  it('shows the quote with its sign, on the row and on the card', async () => {
    renderPage([
      {
        id: 'p1',
        code: 'KSN01',
        name: 'Kausani Guest House Extension',
        status: 'ACTIVE',
        quotedPercent: -12.5,
        version: 0,
      },
      { id: 'p5', code: 'RNK04', name: 'Ranikhet Quarters', status: 'ACTIVE', version: 0 },
    ]);
    await screen.findAllByText('KSN01');

    const below = table().getByText('KSN01').closest('tr') as HTMLElement;
    expect(within(below).getByText('-12.5%')).toBeInTheDocument();
    // No quote is no answer, not a bid at par.
    const unquoted = table().getByText('RNK04').closest('tr') as HTMLElement;
    expect(within(unquoted).getAllByText('\u2014').length).toBeGreaterThan(0);

    const card = cards().getByText('KSN01').closest('li') as HTMLElement;
    expect(within(card).getByText('-12.5%')).toBeInTheDocument();
  });

  it('reorders the rows from the headers, and turns a column around on a second click', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage([
      { id: 'p1', code: 'KSN01', name: 'Kausani', status: 'ACTIVE', quotedCost: 900, version: 0 },
      { id: 'p2', code: 'ALM03', name: 'Almora', status: 'ACTIVE', quotedCost: 500, version: 0 },
      { id: 'p3', code: 'BAG02', name: 'Bageshwar', status: 'ACTIVE', version: 0 },
    ]);
    await screen.findAllByText('KSN01');

    const codes = () =>
      table()
        .getAllByRole('row')
        .slice(1)
        .map((row) => within(row).getAllByRole('cell')[0]?.textContent?.slice(0, 5));

    // Opens on the order the server sent, which is by code.
    expect(codes()).toEqual(['ALM03', 'BAG02', 'KSN01']);

    await user.click(screen.getByRole('button', { name: 'Quoted value' }));
    // Smallest first, and the project with no quoted value at the foot rather than the head.
    expect(codes()).toEqual(['ALM03', 'KSN01', 'BAG02']);

    await user.click(screen.getByRole('button', { name: 'Quoted value' }));
    expect(codes()).toEqual(['KSN01', 'ALM03', 'BAG02']);
  });

  it('shows which projects have no site to record anything against', async () => {
    renderPage();
    await screen.findAllByText('KSN01');
    const withSite = table().getByText('KSN01').closest('tr') as HTMLElement;
    const withoutSite = table().getByText('BAG02').closest('tr') as HTMLElement;

    // The count itself stopped being a column; what it was there to catch did not.
    expect(within(withoutSite).getByText('No sites')).toBeInTheDocument();
    expect(within(withSite).queryByText('No sites')).not.toBeInTheDocument();
  });

  it('says when each contract is due, and how long that leaves', async () => {
    const today = new Date();
    const inTenDays = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 10);
    const iso = (date: Date) =>
      `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
        date.getDate(),
      ).padStart(2, '0')}`;
    renderPage([
      {
        id: 'p1',
        code: 'KSN01',
        name: 'Kausani Guest House Extension',
        status: 'ACTIVE',
        expectedCompletionDate: iso(inTenDays),
        version: 0,
      },
      { id: 'p2', code: 'BAG02', name: 'Bageshwar Road Works', status: 'PLANNED', version: 0 },
    ]);
    await screen.findAllByText('KSN01');

    const due = table().getByText('KSN01').closest('tr') as HTMLElement;
    expect(within(due).getByText('(10 days left)')).toBeInTheDocument();
    // A project with no date says nothing rather than counting to one it does not have.
    const undated = table().getByText('BAG02').closest('tr') as HTMLElement;
    expect(within(undated).queryByText(/days/)).not.toBeInTheDocument();
  });

  it('says the same thing on a phone, where the table would scroll off the screen', async () => {
    renderPage();
    await screen.findAllByText('KSN01');

    const withSite = cards().getByText('KSN01').closest('li') as HTMLElement;
    const withoutSite = cards().getByText('BAG02').closest('li') as HTMLElement;

    // The two facts the table put in columns nobody could reach on a phone.
    expect(within(withSite).getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(within(withSite).queryByText('No sites yet')).not.toBeInTheDocument();
    expect(within(withoutSite).getByText('No sites yet')).toBeInTheDocument();
  });

  it('edits from a card, not just from a row', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    const card = cards().getByText('KSN01').closest('li') as HTMLElement;
    await user.click(within(card).getByRole('button', { name: 'Edit' }));

    expect(await screen.findByLabelText('Project code')).toBeDisabled();
  });

  it('adds a project from a code and a name alone', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    await user.click(screen.getByRole('button', { name: 'Add a project' }));
    // Adding now opens on a choice: start from the tender PDF, or type it in.
    await user.click(screen.getByRole('button', { name: 'Enter manually' }));
    await user.type(screen.getByLabelText('Project code'), 'ALM03');
    await user.type(screen.getByLabelText('Project name'), 'Almora Block C');
    await user.click(screen.getByRole('button', { name: 'Add project' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [
      string,
      { code: string; name: string; contractValue?: number },
    ];
    expect(url).toBe('/projects');
    expect(body.code).toBe('ALM03');
    // A blank amount is unknown, not zero — the server must not be told the contract is nil.
    expect(body.contractValue).toBeUndefined();
  });

  it('rejects an amount that is not a number before sending it', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    await user.click(screen.getByRole('button', { name: 'Add a project' }));
    // Adding now opens on a choice: start from the tender PDF, or type it in.
    await user.click(screen.getByRole('button', { name: 'Enter manually' }));
    await user.type(screen.getByLabelText('Project code'), 'ALM03');
    await user.type(screen.getByLabelText('Project name'), 'Almora Block C');
    await user.type(screen.getByLabelText('Contract value (optional)'), '18.5 lakh');
    await user.click(screen.getByRole('button', { name: 'Add project' }));

    expect(await screen.findByText('A number, up to two decimals')).toBeInTheDocument();
    expect(post).not.toHaveBeenCalled();
  });

  /**
   * The quoted cost and the budget follow from the contract value and the quote, and both are
   * worked out where the person entering them can see them. The arithmetic itself is
   * projectFigures' to test; what is asserted here is that the form fills the boxes and sends
   * what is in them.
   */
  it('works the quoted cost and the budget out as the quote is typed', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    await user.click(screen.getByRole('button', { name: 'Add a project' }));
    await user.click(screen.getByRole('button', { name: 'Enter manually' }));
    await user.type(screen.getByLabelText('Project code'), 'ALM03');
    await user.type(screen.getByLabelText('Project name'), 'Almora Block C');
    await user.type(screen.getByLabelText('Contract value (optional)'), '10000000');
    await user.type(screen.getByLabelText('Quoted % (optional)'), '-12.5');

    // A crore bid 12.5% below, and three quarters of that.
    expect(screen.getByLabelText('Quoted cost (optional)')).toHaveValue('8750000');
    expect(screen.getByLabelText('Budget (optional)')).toHaveValue('6562500');

    await user.click(screen.getByRole('button', { name: 'Add project' }));
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [, body] = post.mock.calls[0] as [
      string,
      { contractValue?: number; quotedPercent?: number; quotedCost?: number; budgetAmount?: number },
    ];
    expect(body.contractValue).toBe(10000000);
    expect(body.quotedPercent).toBe(-12.5);
    expect(body.quotedCost).toBe(8750000);
    expect(body.budgetAmount).toBe(6562500);
  });

  /**
   * An override is a decision about one pair of figures. Once the quote moves, what is in the
   * box was derived from a percentage that no longer applies — so it is worked out again and
   * put back in front of the person who overrode it, rather than left standing as a number
   * nobody chose.
   */
  it('works them out again when the quote moves under an overridden figure', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    await user.click(screen.getByRole('button', { name: 'Add a project' }));
    await user.click(screen.getByRole('button', { name: 'Enter manually' }));
    await user.type(screen.getByLabelText('Project code'), 'ALM03');
    await user.type(screen.getByLabelText('Project name'), 'Almora Block C');
    await user.type(screen.getByLabelText('Contract value (optional)'), '10000000');
    await user.type(screen.getByLabelText('Quoted % (optional)'), '-10');

    const budget = screen.getByLabelText('Budget (optional)');
    await user.clear(budget);
    await user.type(budget, '7000000');
    expect(budget).toHaveValue('7000000');

    // The quote is corrected: both derived boxes answer to the new one.
    await user.clear(screen.getByLabelText('Quoted % (optional)'));
    await user.type(screen.getByLabelText('Quoted % (optional)'), '-20');

    expect(screen.getByLabelText('Quoted cost (optional)')).toHaveValue('8000000');
    expect(budget).toHaveValue('6000000');
  });

  it('sends the version and the code-free body when editing', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');
    const row = table().getByText('KSN01').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: 'Edit' }));

    expect(await screen.findByLabelText('Project code')).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [
      string,
      { code?: string; version: number; contractValue?: number; status: string },
    ];
    expect(url).toBe('/projects/p1');
    expect(body.version).toBe(1);
    expect(body.code).toBeUndefined();
    expect(body.contractValue).toBe(18500000);
    expect(body.status).toBe('ACTIVE');
  });

  it('points at the next step when there are no projects at all', async () => {
    renderPage([]);
    // Once in each view; both must point at the same next step.
    expect(await screen.findAllByText(/Add the contract you are working under/)).toHaveLength(2);
  });

  // The filters are the server's answer, not a cut of what is already on screen: a closed
  // contract from four years ago need never be loaded to be excluded.
  it('asks the server for the search text and the status', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findAllByText('KSN01');

    await user.type(screen.getByLabelText('Search by code, name or client'), 'bag');
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/projects', {
        params: { q: 'bag', status: undefined, size: 100 },
      }),
    );

    await user.click(screen.getByRole('combobox', { name: 'Status' }));
    await user.click(await screen.findByRole('option', { name: 'On hold' }));
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/projects', {
        params: { q: 'bag', status: 'ON_HOLD', size: 100 },
      }),
    );
  });

  it('does not tell someone with twelve projects to add their first', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage([]);
    await user.type(screen.getByLabelText('Search by code, name or client'), 'zzz');

    expect(await screen.findAllByText(/No project matches that/)).toHaveLength(2);
    expect(screen.queryByText(/Add the contract you are working under/)).toBeNull();
  });
});
