import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { BillPhotoField } from './BillPhotoField';

/** The field is controlled, so the test holds the state the page would hold. */
function Harness() {
  const [file, setFile] = useState<File | null>(null);
  return <BillPhotoField file={file} onPick={setFile} />;
}

/** Small enough that the compressor passes it through, which is the case jsdom can run. */
function jpeg(name = 'IMG_20260812_104533.jpg'): File {
  return new File([new Uint8Array(64)], name, { type: 'image/jpeg' });
}

describe('BillPhotoField', () => {
  /*
    A file name is not evidence. The thumb over the lens and the challan photographed instead
    of the bill are identical as IMG_20260812_104533.jpg and obvious as a picture — and the
    man holding the bill is the only one who can take it again cheaply.
  */
  it('shows what was photographed, and opens it full width when tapped', async () => {
    const user = userEvent.setup({ delay: null });
    render(<Harness />);

    await user.upload(screen.getByLabelText('Photograph the bill'), jpeg());

    const thumbnail = await screen.findByRole('img', { name: 'IMG_20260812_104533.jpg' });
    expect(thumbnail).toHaveAttribute('src', 'blob:test');

    await user.click(thumbnail);
    const dialog = await screen.findByRole('dialog');
    // The question a thumbnail cannot answer is whether the figures can be read.
    expect(within(dialog).getByRole('img', { name: 'IMG_20260812_104533.jpg' })).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('removing the photograph takes the picture with it', async () => {
    const user = userEvent.setup({ delay: null });
    render(<Harness />);

    await user.upload(screen.getByLabelText('Photograph the bill'), jpeg());
    expect(await screen.findByRole('img', { name: 'IMG_20260812_104533.jpg' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove' }));
    await waitFor(() =>
      expect(screen.queryByRole('img', { name: 'IMG_20260812_104533.jpg' })).not.toBeInTheDocument(),
    );
  });

  /**
   * A vendor emails a PDF bill. There is nothing for an img to draw, so it keeps its name —
   * and it comes in off the device rather than off the camera, which is the whole reason the
   * second button exists: no lens ever produced a PDF.
   */
  it('names a PDF bill rather than pretending to draw it', async () => {
    const user = userEvent.setup({ delay: null });
    render(<Harness />);

    await user.upload(
      screen.getByLabelText('From device'),
      new File([new Uint8Array(64)], 'SS-856.pdf', { type: 'application/pdf' }),
    );

    expect(await screen.findByText(/SS-856\.pdf/)).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });
});
