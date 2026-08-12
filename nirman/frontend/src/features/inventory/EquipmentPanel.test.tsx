import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EquipmentPanel } from './EquipmentPanel';
import type { Equipment } from './types';

const get = vi.fn();
const post = vi.fn();
const put = vi.fn();
const del = vi.fn();

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
      delete: (...args: unknown[]) => del(...args),
    },
  };
});

let permissions: string[] = [];

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-1', permissions },
    hasPermission: (code: string) => permissions.includes(code),
  }),
}));

const SITE_ENTRY: Equipment = {
  id: 'eq-1',
  siteId: 'site-a',
  storeId: 'store-a',
  storeName: 'site-KSN-A',
  name: 'Concrete Mixer 10/7',
  quantity: 1,
  ownership: 'OWNED',
  condition: 'WORKING',
  status: 'PENDING',
  createdAt: '2026-08-11T04:00:00Z',
  version: 0,
};

function renderPanel(rows: Equipment[] = [SITE_ENTRY]) {
  get.mockImplementation((url: string) => {
    if (url === '/inventory/equipment') return Promise.resolve({ data: rows });
    if (url === '/vendors') return Promise.resolve({ data: { content: [] } });
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EquipmentPanel storeId="store-a" />
    </QueryClientProvider>,
  );
}

/**
 * RecordTable draws the table and the phone cards at once and lets a breakpoint choose, so
 * every row's text is in the DOM twice. The assertions below go through the table to keep
 * "found two of them" from reading as a bug.
 */
const table = () => screen.getByRole('table', { name: 'Equipment' });
const findRegister = async () => {
  await screen.findAllByText('Concrete Mixer 10/7');
  return within(table());
};

describe('EquipmentPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissions = ['equipment:read', 'equipment:create'];
    post.mockResolvedValue({ data: { ...SITE_ENTRY, id: 'eq-new' } });
  });

  /**
   * The supervisor's half of the split: he may say the machine is there, and the screen tells
   * him plainly that somebody else has to agree. Hiding that would have him enter it again on
   * Thursday.
   */
  it('lets the site enter a machine and says it is waiting on the office', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const register = await findRegister();

    expect(register.getByText('Waiting on the office')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Add equipment' }));
    await user.type(screen.getByRole('textbox', { name: 'What is it' }), 'Needle vibrator');
    await user.click(screen.getByRole('button', { name: 'Add it' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [url, body] = post.mock.calls[0] as [string, { id: string; name: string }];
    expect(url).toBe('/inventory/equipment');
    expect(body.name).toBe('Needle vibrator');
    // Generated on the device, so an entry sent three times from a yard is one machine.
    expect(body.id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });

  /** Accepting, correcting and removing are the office's. A supervisor sees none of them. */
  it('offers the site no way to accept, correct or remove a row', async () => {
    renderPanel();
    await findRegister();

    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  it('lets an administrator accept the entry', async () => {
    permissions = ['equipment:read', 'equipment:create', 'equipment:approve', 'equipment:write'];
    post.mockResolvedValue({ data: { ...SITE_ENTRY, status: 'ACCEPTED' } });
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const register = await findRegister();

    await user.click(register.getByRole('button', { name: 'Accept' }));

    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    expect(post.mock.calls[0]![0]).toBe('/inventory/equipment/eq-1/decision');
    expect(post.mock.calls[0]![1]).toMatchObject({ action: 'ACCEPT' });
  });

  it('removes a row on the administrator’s say-so', async () => {
    permissions = ['equipment:read', 'equipment:approve', 'equipment:write'];
    del.mockResolvedValue({ data: undefined });
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const register = await findRegister();

    await user.click(register.getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(del).toHaveBeenCalledOnce());
    expect(del.mock.calls[0]![0]).toBe('/inventory/equipment/eq-1');
  });

  /**
   * A hired machine is costing money every day it stands in the yard. The server refuses one
   * with nobody to pay; saying so on the form beats being refused after it is all typed.
   */
  it('will not send a hired machine without saying who it is hired from', async () => {
    const user = userEvent.setup({ delay: null });
    renderPanel();
    await findRegister();

    await user.click(screen.getByRole('button', { name: 'Add equipment' }));
    await user.type(screen.getByRole('textbox', { name: 'What is it' }), 'Hired JCB');
    await user.click(screen.getByRole('combobox', { name: 'Whose is it' }));
    await user.click(await screen.findByRole('option', { name: 'Hired' }));

    expect(screen.getByRole('button', { name: 'Add it' })).toBeDisabled();
    expect(screen.getByRole('combobox', { name: 'Hired from' })).toBeInTheDocument();
  });

  /** The number already on the register is the one thing the person typing can act on. */
  it('shows the server’s refusal on the form rather than behind it', async () => {
    const refusal = new Error('conflict') as Error & { isAxiosError: boolean; response: unknown };
    refusal.isAxiosError = true;
    refusal.response = {
      status: 409,
      data: { detail: 'Number UK-04-CA-1120 is already on the register as Hired JCB.' },
    };
    post.mockRejectedValue(refusal);
    const user = userEvent.setup({ delay: null });
    renderPanel();
    await findRegister();

    await user.click(screen.getByRole('button', { name: 'Add equipment' }));
    await user.type(screen.getByRole('textbox', { name: 'What is it' }), 'Second JCB');
    await user.type(screen.getByRole('textbox', { name: 'Number on it' }), 'UK-04-CA-1120');
    await user.click(screen.getByRole('button', { name: 'Add it' }));

    expect(await screen.findByText(/already on the register as Hired JCB/)).toBeInTheDocument();
    // Still open, with what was typed still in it.
    expect(screen.getByRole('textbox', { name: 'What is it' })).toHaveValue('Second JCB');
  });
});
