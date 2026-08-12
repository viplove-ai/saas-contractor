import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PhotoCard } from './DayEntry';

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
function Harness({ uploaded = 0 }: { uploaded?: number }) {
  const [files, setFiles] = useState<File[]>([]);
  return (
    <PhotoCard
      files={files}
      onChange={setFiles}
      uploaded={uploaded}
      siteCode="KSN-A"
      reportDate="2026-08-12"
    />
  );
}

function photo(name: string): File {
  return new File([new Uint8Array([1, 2, 3])], name, { type: 'image/jpeg' });
}

describe('PhotoCard', () => {
  beforeEach(() => {
    // jsdom has no object URLs, and the thumbnails are the whole point of the card.
    URL.createObjectURL = vi.fn(() => 'blob:photo');
    URL.revokeObjectURL = vi.fn();
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
    render(<Harness uploaded={2} />);

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
