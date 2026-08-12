import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AddExpensePage } from './AddExpensePage';
import type { Expense, ExpenseCategory, PageResponse, Site } from './types';

/**
 * "Other" on the what-kind picker.
 *
 * <p>The starting taxonomy is the CPWD list and no site's spending fits it entirely. Without
 * this answer the bill goes under whichever head is least wrong, and the office reads a
 * "Miscellaneous" total it cannot break down. What the supervisor types becomes a head, and
 * the expense is booked against it in the same press.</p>
 */
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

let permissions = ['expense:create', 'expense:read', 'masterdata:provisional:head'];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-sup', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITES: Site[] = [{ id: 'site-a', code: 'KSN-A', name: 'Kausani Main Block' }];

const CATEGORIES: ExpenseCategory[] = [
  {
    id: 'cat-site',
    code: 'MISC-SITE',
    name: 'Site Expenses',
    materialPurchase: false,
    labourPayment: false,
    requiresVendor: false,
    active: true,
  },
];

const NO_EXPENSES: PageResponse<Expense> = {
  content: [],
  page: 0,
  size: 100,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AddExpensePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Fills everything except the kind, which is what each test below is about. */
async function fillTheBill(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByRole('textbox', { name: 'What was it for' }), 'Rat traps');
  await user.type(screen.getByRole('spinbutton', { name: 'Amount before tax' }), '900');
}

describe('AddExpensePage — an expense the taxonomy has no head for', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['expense:create', 'expense:read', 'masterdata:provisional:head'];
    get.mockImplementation((url: string) => {
      if (url === '/sites') return Promise.resolve({ data: SITES });
      if (url === '/expense-categories') return Promise.resolve({ data: CATEGORIES });
      if (url === '/expenses') return Promise.resolve({ data: NO_EXPENSES });
      return Promise.reject(new Error(`unexpected GET ${url}`));
    });
  });

  it('asks what to call it, opens the head, and books the expense against it', async () => {
    const named: ExpenseCategory = {
      id: 'cat-new',
      code: 'EXH-2026-0001',
      name: 'Rat menace control',
      materialPurchase: false,
      labourPayment: false,
      requiresVendor: false,
      active: true,
      provisional: true,
    };
    post.mockImplementation((url: string) => {
      if (url === '/expense-categories/field') {
        // The server has the head from here on, and the screen refetches the list — so the
        // mock has to start answering with it, or the picker is asserted against a taxonomy
        // no server would return.
        get.mockImplementation((path: string) =>
          path === '/expense-categories'
            ? Promise.resolve({ data: [...CATEGORIES, named] })
            : path === '/sites'
              ? Promise.resolve({ data: SITES })
              : Promise.resolve({ data: NO_EXPENSES }),
        );
        return Promise.resolve({ data: named });
      }
      return Promise.resolve({ data: { id: 'e1', expenseNumber: 'EXP-2026-0001' } });
    });
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Other…' }));
    await user.type(
      screen.getByRole('textbox', { name: 'What kind of expense is it' }),
      'Rat menace control',
    );
    await fillTheBill(user);
    await user.click(screen.getByRole('button', { name: 'Save as draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    // The head first: an expense carries a head id and there is nowhere to put a phrase.
    expect(post.mock.calls[0]).toEqual(['/expense-categories/field', { name: 'Rat menace control' }]);
    const [url, body] = post.mock.calls[1] as [string, { categoryId: string }];
    expect(url).toBe('/expenses');
    expect(body.categoryId).toBe('cat-new');
  });

  /** A kind that is only a sentinel is not a kind: nothing may be booked against it. */
  it('will not book until the other kind has been named', async () => {
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Other…' }));
    await fillTheBill(user);

    expect(screen.getByRole('button', { name: 'Save as draft' })).toBeDisabled();
  });

  /**
   * The one thing on this form a phone with no signal cannot do. Booking it under the wrong
   * head to save the trip is the mess the whole answer exists to avoid, so it stops and says
   * so rather than quietly filing it somewhere.
   */
  it('does not book the expense when the head could not be opened', async () => {
    post.mockRejectedValue(new Error('Network Error'));
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'Site' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    await user.click(await screen.findByRole('option', { name: 'Other…' }));
    await user.type(
      screen.getByRole('textbox', { name: 'What kind of expense is it' }),
      'Rat menace control',
    );
    await fillTheBill(user);
    await user.click(screen.getByRole('button', { name: 'Save as draft' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/expense-categories/field');
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  /** Naming a head is its own permission. Without it the picker offers the list and no more. */
  it('offers no "Other" to somebody who may not name a head', async () => {
    permissions = ['expense:create', 'expense:read'];
    const user = userEvent.setup({ delay: null });
    renderPage();
    await screen.findByRole('combobox', { name: 'What kind' });

    await user.click(screen.getByRole('combobox', { name: 'What kind' }));
    const options = await screen.findAllByRole('option');

    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent('Site Expenses');
  });
});
