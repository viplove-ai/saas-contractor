import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PhotoCard } from './DayEntry';
import type { PhotoResponse } from './types';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return { ...actual, apiClient: { get: (...args: unknown[]) => get(...args) } };
});

/**
 * The photographs on a daily report.
 *
 * <p>Two things the camera does badly. It names the file {@code IMG_20260812_104533.jpg} —
 * or {@code image.jpg}, four times over — which is what the office ends up reading against
 * the report; and a list of those names tells nobody which picture is the wrong one. So each
 * photograph is renamed after the site and the day as it is picked, and shown as a thumbnail
 * that opens.</p>
 */
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'u-sup' }, hasPermission: () => true }),
}));

/** The card is controlled; this is the state the wizard holds around it. */
function Harness({ uploaded = [] }: { uploaded?: PhotoResponse[] }) {
  const [files, setFiles] = useState<File[]>([]);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <PhotoCard
        files={files}
        onChange={setFiles}
        uploaded={uploaded}
        siteCode="KSN-A"
        reportDate="2026-08-12"
      />
    </QueryClientProvider>
  );
}

/** One already on the report, as the server hands it back. */
function onReport(id: string, caption?: string): PhotoResponse {
  return {
    id,
    attachmentId: `att-${id}`,
    ...(caption === undefined ? {} : { caption }),
    fileName: `${id}.jpg`,
    sizeBytes: 1024,
    sortOrder: 0,
  };
}

function photo(name: string): File {
  return new File([new Uint8Array([1, 2, 3])], name, { type: 'image/jpeg' });
}

describe('PhotoCard', () => {
  beforeEach(() => {
    // jsdom has no object URLs, and the thumbnails are the whole point of the card.
    URL.createObjectURL = vi.fn(() => 'blob:photo');
    URL.revokeObjectURL = vi.fn();
    get.mockImplementation((url: string) =>
      url.startsWith('/attachments/')
        ? Promise.resolve({ data: { url: 'https://minio.test/signed', fileName: 'up.jpg' } })
        : Promise.reject(new Error(`unexpected GET ${url}`)),
    );
  });

  it('renames a picked photograph after the site and the day', async () => {
    const user = userEvent.setup({ delay: null });
    render(<Harness />);

    await user.upload(screen.getByLabelText('Add photographs'), photo('IMG_20260812_104533.jpg'));

    expect(await screen.findByText('KSN-A-2026-08-12-1.jpg')).toBeInTheDocument();
  });

  /** Four pictures a phone all calls image.jpg have to end up as four different names. */
  it('numbers them in order, past the ones already on the report', async () => {
    const user = userEvent.setup({ delay: null });
    render(<Harness uploaded={[onReport('a'), onReport('b')]} />);

    await user.upload(screen.getByLabelText('Add photographs'), [
      photo('image.jpg'),
      photo('image.jpg'),
    ]);

    expect(await screen.findByText('KSN-A-2026-08-12-3.jpg')).toBeInTheDocument();
    expect(screen.getByText('KSN-A-2026-08-12-4.jpg')).toBeInTheDocument();
  });

  it('shows each one as a thumbnail that opens to a preview', async () => {
    const user = userEvent.setup({ delay: null });
    render(<Harness />);
    await user.upload(screen.getByLabelText('Add photographs'), photo('slab.jpg'));

    const thumbnail = await screen.findByRole('img', { name: 'KSN-A-2026-08-12-1.jpg' });
    await user.click(thumbnail);

    // The dialog carries the same picture at full width. Only its copy is in the a11y tree
    // while it is open — MUI hides the page behind it — so the assertion goes through it.
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('img', { name: 'KSN-A-2026-08-12-1.jpg' })).toBeInTheDocument();
  });

  it('drops the one picked by mistake', async () => {
    const user = userEvent.setup({ delay: null });
    render(<Harness />);
    await user.upload(screen.getByLabelText('Add photographs'), photo('wrong-wall.jpg'));
    await screen.findByText('KSN-A-2026-08-12-1.jpg');

    await user.click(screen.getByRole('button', { name: 'Remove KSN-A-2026-08-12-1.jpg' }));

    expect(screen.queryByText('KSN-A-2026-08-12-1.jpg')).not.toBeInTheDocument();
  });
});
