import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OutsourcedLabourCard } from './DayEntry';
import type { DayCounts } from './types';

const get = vi.fn();
const put = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: (...args: unknown[]) => get(...args),
      put: (...args: unknown[]) => put(...args),
      post: vi.fn(),
    },
  };
});

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'u-sup' }, hasPermission: () => true }),
}));

const MASON = '11111111-1111-1111-1111-111111111111';

/** A site let to an outside supplier: eleven masons at the gate, hours not yet noted. */
function counts(overrides: Partial<DayCounts> = {}): DayCounts {
  return {
    siteId: 'site-a',
    date: '2025-06-12',
    enabled: true,
    periodLocked: false,
    totalHeadCount: 11,
    totalManHours: 0,
    lines: [
      { id: 'row-1', skillCategoryId: MASON, skillCategoryName: 'Mason', headCount: 11 },
    ],
    suppliers: [],
    ...overrides,
  };
}

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OutsourcedLabourCard siteId="site-a" date="2025-06-12" onSaved={() => {}} />
    </QueryClientProvider>,
  );
}

describe('OutsourcedLabourCard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockImplementation((url: string) => {
      if (url === '/labour-counts') return Promise.resolve({ data: counts() });
      if (url === '/skill-categories') {
        return Promise.resolve({ data: [{ id: MASON, code: 'MASON', name: 'Mason' }] });
      }
      return Promise.resolve({ data: [] });
    });
    put.mockImplementation(() => Promise.resolve({ data: counts() }));
  });

  it('asks for the trade, the men and their hours — and no longer for a contractor', async () => {
    renderCard();

    await waitFor(() => expect(screen.getByText('External labour')).toBeInTheDocument());
    expect(await screen.findByLabelText('Trade')).toBeInTheDocument();
    expect(screen.getByLabelText('Men')).toBeInTheDocument();
    expect(screen.getByLabelText('Hours each')).toBeInTheDocument();
    // Naming a supplier means nothing until they are onboarded, and a picker over an empty
    // list is a question with no answers.
    expect(screen.queryByLabelText('Contractor')).not.toBeInTheDocument();
  });

  it('sends hours per man, and totals them as man-hours on the card', async () => {
    const user = userEvent.setup();
    renderCard();
    const hours = await screen.findByLabelText('Hours each');

    await user.type(hours, '8');
    // Eleven men for eight hours each is eighty-eight man-hours, and that is the figure the
    // report adds up — not the eight.
    await waitFor(() => expect(screen.getByText('88 man-hours')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Save counts' }));
    await waitFor(() =>
      expect(put).toHaveBeenCalledWith('/labour-counts', {
        siteId: 'site-a',
        date: '2025-06-12',
        lines: [
          {
            skillCategoryId: MASON,
            labourSupplierId: undefined,
            headCount: 11,
            hours: 8,
          },
        ],
        // Nobody named a supplier, so there is nobody to ask about.
        suppliers: [],
      }),
    );
  });

  /**
   * The distinction the column exists for: a day nobody noted hours on has said nothing
   * about them, and a zero would print on the report as "they worked no hours".
   */
  it('leaves hours out entirely when the field is blank, rather than sending a zero', async () => {
    const user = userEvent.setup();
    renderCard();
    await screen.findByLabelText('Hours each');

    await user.click(screen.getByRole('button', { name: 'Save counts' }));
    await waitFor(() => expect(put).toHaveBeenCalled());
    expect(put.mock.calls[0]![1].lines[0].hours).toBeUndefined();
    expect(screen.queryByText(/man-hours/)).not.toBeInTheDocument();
  });

  /** A day that already names a supplier keeps him through a re-save. */
  it('carries an already-named supplier through a re-save untouched', async () => {
    const user = userEvent.setup();
    mockWithSupplier();

    renderCard();
    await screen.findByLabelText('Hours each');
    await user.click(screen.getByRole('button', { name: 'Save counts' }));

    await waitFor(() => expect(put).toHaveBeenCalled());
    expect(put.mock.calls[0]![1].lines[0].labourSupplierId).toBe('lc-1');
  });

  /**
   * The question the site argues about a fortnight later. Asked once per supplier, not once
   * per trade — the same man on three lines is one man.
   */
  it('asks whether the named supplier was on site, and sends the answer', async () => {
    const user = userEvent.setup();
    mockWithSupplier();

    renderCard();
    await screen.findByLabelText('Hours each');

    const presence = await screen.findByRole('checkbox', {
      name: 'Kausani Labour Co-operative · 11 men',
    });
    await user.click(presence);
    await user.type(screen.getByLabelText('Who came'), 'Karam Singh');
    await user.click(screen.getByRole('button', { name: 'Save counts' }));

    await waitFor(() => expect(put).toHaveBeenCalled());
    expect(put.mock.calls[0]![1].suppliers).toEqual([
      {
        labourSupplierId: 'lc-1',
        supplierPresent: true,
        representativeName: 'Karam Singh',
      },
    ]);
  });

  /** Nobody named is nobody to ask about: the question does not appear at all. */
  it('does not ask about a supplier when the gang is unattributed', async () => {
    renderCard();
    await screen.findByLabelText('Hours each');

    expect(screen.queryByText('Was the supplier on site today?')).not.toBeInTheDocument();
  });

  /** A name box beside an off switch is a question about a man who did not come. */
  it('asks who came only once somebody says the supplier was there', async () => {
    mockWithSupplier();
    renderCard();
    await screen.findByLabelText('Hours each');
    await screen.findByRole('checkbox', { name: /Kausani Labour Co-operative/ });

    expect(screen.queryByLabelText('Who came')).not.toBeInTheDocument();
  });
});

/** A day whose masons came from a registered supplier. */
function mockWithSupplier() {
  get.mockImplementation((url: string, config?: { params?: { type?: string } }) => {
    if (url === '/labour-counts') {
      return Promise.resolve({
        data: counts({
          lines: [
            {
              id: 'row-1',
              skillCategoryId: MASON,
              skillCategoryName: 'Mason',
              labourSupplierId: 'lc-1',
              labourSupplierName: 'Kausani Labour Co-operative',
              headCount: 11,
            },
          ],
          suppliers: [
            {
              labourSupplierId: 'lc-1',
              labourSupplierName: 'Kausani Labour Co-operative',
              supplierPresent: false,
              headCount: 11,
            },
          ],
        }),
      });
    }
    if (url === '/skill-categories') {
      return Promise.resolve({ data: [{ id: MASON, code: 'MASON', name: 'Mason' }] });
    }
    if (url === '/vendors' && config?.params?.type === 'SUBCONTRACTOR') {
      return Promise.resolve({
        data: {
          content: [{ id: 'lc-1', code: 'LC-001', name: 'Kausani Labour Co-operative' }],
        },
      });
    }
    return Promise.resolve({ data: [] });
  });
}
