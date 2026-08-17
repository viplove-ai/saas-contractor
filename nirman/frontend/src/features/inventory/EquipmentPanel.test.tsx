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
  /** u-1 is who the mocked session is, so this is the supervisor's own entry. */
  createdBy: 'u-1',
  version: 0,
};

/** Small enough that the compressor passes it through, which is what jsdom can run. */
function jpeg(name = 'mixer.jpg'): File {
  return new File([new Uint8Array(64)], name, { type: 'image/jpeg' });
}

function renderPanel(rows: Equipment[] = [SITE_ENTRY]) {
  get.mockImplementation((url: string) => {
    if (url === '/inventory/equipment') return Promise.resolve({ data: rows });
    if (url === '/vendors') return Promise.resolve({ data: { content: [] } });
    if (url.startsWith('/attachments/')) {
      return Promise.resolve({ data: { url: 'blob:signed', fileName: 'mixer.jpg' } });
    }
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

  /**
   * Accepting and removing stay the office's. Correcting is not theirs alone — the site
   * corrects the register it can see, which is the line the server draws and this screen has
   * to draw the same way.
   */
  it('offers the site a correction, and no way to accept or remove', async () => {
    renderPanel();
    const register = await findRegister();

    expect(register.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument();
  });

  /**
   * And on a row somebody else entered. A site is a shift roster: the man who wrote the mixer
   * down in March is elsewhere in June, and the breaker whose jaw has come off is visible to
   * whoever is standing there now. His correction re-opens the row, so the office still reads
   * it before it counts.
   */
  it('offers the site a correction on somebody else’s row', async () => {
    renderPanel([{ ...SITE_ENTRY, createdBy: 'u-2' }]);
    const register = await findRegister();

    expect(register.getByRole('button', { name: 'Edit' })).toBeInTheDocument();
  });

  /**
   * The point of the whole change: the mixer the office accepted in March has broken, and the
   * man looking at it can say so. The warning is asserted because a correction that silently
   * undoes an acceptance is one he makes once.
   */
  it('warns the site that correcting an accepted machine sends it back, then sends it', async () => {
    const accepted: Equipment = { ...SITE_ENTRY, status: 'ACCEPTED', version: 3 };
    put.mockResolvedValue({ data: { ...accepted, condition: 'UNDER_REPAIR', status: 'PENDING' } });
    const user = userEvent.setup({ delay: null });
    renderPanel([accepted]);
    const register = await findRegister();

    await user.click(register.getByRole('button', { name: 'Edit' }));
    expect(
      screen.getByText(/Saving a change sends it back to them to accept again/),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save and send it back' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    const [url, body] = put.mock.calls[0] as [string, { version: number }];
    expect(url).toBe('/inventory/equipment/eq-1');
    // The version rides along, so two people correcting the same row is a 409 and not a
    // silent overwrite of whichever of them saved first.
    expect(body.version).toBe(3);
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

  /*
    The half of the feature that decides whether it gets used at all. A machine is written
    down at the gate in the rain and photographed on Thursday, so the register has to take the
    picture on a day of its own — from the row, without reopening or correcting anything.
  */
  it('photographs a machine already on the register, in two calls', async () => {
    post.mockResolvedValue({ data: { id: 'att-9' } });
    put.mockResolvedValue({ data: { ...SITE_ENTRY, photoAttachmentId: 'att-9' } });
    const user = userEvent.setup({ delay: null });
    renderPanel();
    const register = await findRegister();

    await user.upload(register.getByLabelText('Photograph it'), jpeg());

    // The file into storage first: a row pointing at an attachment that does not exist is a
    // broken picture on the office's screen, while a file with no owner is only a stray file.
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
    const [uploadUrl, , config] = post.mock.calls[0] as [
      string,
      FormData,
      { params: { ownerEntityType: string; kind: string; siteId: string } },
    ];
    expect(uploadUrl).toBe('/attachments');
    expect(config.params).toMatchObject({
      ownerEntityType: 'SITE_EQUIPMENT',
      kind: 'PHOTO',
      siteId: 'site-a',
    });

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(put.mock.calls[0]![0]).toBe('/inventory/equipment/eq-1/photo');
    expect(put.mock.calls[0]![1]).toEqual({ attachmentId: 'att-9' });
  });

  /** A picture on the entry is what the office is reading the register for. */
  it('shows the picture on the row once there is one', async () => {
    renderPanel([{ ...SITE_ENTRY, photoAttachmentId: 'att-9' }]);
    const register = await findRegister();

    const thumbnail = await waitFor(() =>
      register.getByRole('img', { name: 'Concrete Mixer 10/7' }),
    );
    // Signed on demand rather than carried on forty rows nobody scrolls through.
    expect(get).toHaveBeenCalledWith('/attachments/att-9/url');
    expect(thumbnail).toHaveAttribute('src', 'blob:signed');
    expect(register.queryByRole('button', { name: 'Photograph it' })).not.toBeInTheDocument();
  });

  /*
    The camera is offered exactly where it would work, and the server now lets it work on any
    row at the site: after the office has decided, because a picture a man can only take in
    the hours before somebody clicks Accept is one he mostly cannot take, and on an entry he
    did not make, because the machine is in front of him either way.
  */
  it('offers the camera on every row at the site, whoever entered it', async () => {
    renderPanel([
      SITE_ENTRY,
      { ...SITE_ENTRY, id: 'eq-2', name: 'Somebody else’s vibrator', createdBy: 'u-9' },
      { ...SITE_ENTRY, id: 'eq-3', name: 'Already accepted mixer', status: 'ACCEPTED' },
    ]);
    await findRegister();
    const rowFor = (name: string) =>
      within(within(table()).getByText(name).closest('tr') as HTMLElement);

    expect(rowFor('Concrete Mixer 10/7').getByLabelText('Photograph it')).toBeInTheDocument();
    // The office deciding about it did not put it out of reach.
    expect(rowFor('Already accepted mixer').getByLabelText('Photograph it')).toBeInTheDocument();
    // Nor did somebody else having entered it.
    expect(rowFor('Somebody else’s vibrator').getByLabelText('Photograph it')).toBeInTheDocument();
  });

  /** The office may photograph any row, decided or not: correcting the register is theirs. */
  it('lets the office photograph a row it has already accepted', async () => {
    permissions = ['equipment:read', 'equipment:approve', 'equipment:write'];
    renderPanel([{ ...SITE_ENTRY, status: 'ACCEPTED', createdBy: 'u-9' }]);
    const register = await findRegister();

    expect(register.getByLabelText('Photograph it')).toBeInTheDocument();
  });

  /**
   * Adding a machine and photographing it is one act to the person doing it and two calls on
   * the wire, in that order: the picture cannot be attached to a row that does not exist yet.
   */
  it('sends the picture after the machine it belongs to', async () => {
    post.mockImplementation((url: string) =>
      url === '/attachments'
        ? Promise.resolve({ data: { id: 'att-9' } })
        : Promise.resolve({ data: { ...SITE_ENTRY, id: 'eq-new', siteId: 'site-a' } }),
    );
    put.mockResolvedValue({ data: SITE_ENTRY });
    const user = userEvent.setup({ delay: null });
    renderPanel();
    await findRegister();

    await user.click(screen.getByRole('button', { name: 'Add equipment' }));
    // The row behind carries a camera of its own, so this stays inside the form.
    const form = within(await screen.findByRole('dialog'));
    await user.type(form.getByRole('textbox', { name: 'What is it' }), 'Needle vibrator');
    await user.upload(form.getByLabelText('Photograph it'), jpeg());
    // Seen before it is sent, so the thumb over the lens is caught by the man who took it.
    expect(await form.findByRole('img', { name: 'mixer.jpg' })).toBeInTheDocument();
    await user.click(form.getByRole('button', { name: 'Add it' }));

    await waitFor(() => expect(put).toHaveBeenCalledOnce());
    expect(post.mock.calls.map((call) => call[0])).toEqual(['/inventory/equipment', '/attachments']);
    expect(put.mock.calls[0]![0]).toBe('/inventory/equipment/eq-new/photo');
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
