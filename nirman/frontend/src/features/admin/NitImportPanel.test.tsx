import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProjectFormDialog } from './ProjectFormDialog';
import type { NitPreview } from './types';

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

vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'u-admin', permissions: ['project:write'] },
    hasPermission: () => true,
  }),
}));

const PREVIEW: NitPreview = {
  attachmentId: 'att-1',
  fileName: 'almora-30-tile-work.pdf',
  pageCount: 134,
  suggestedCode: '30-EE-ACD-CPWD-ALMORA-2026-27',
  suggestedName: 'Tile work in Type-2 and Type-3 quarters at VPKAS, Hawalbagh and Almora (UK).',
  nitNumber: '30/EE/ACD/CPWD/Almora/2026-27',
  tenderReference: '30/EE/ACD/CPWD/Almora/2026-27',
  contractValue: 4226546,
  fields: {
    nitNo: '30/EE/ACD/CPWD/Almora/2026-27',
    workName: 'Tile work in Type-2 and Type-3 quarters',
    estimatedCost: 4226546,
    civilEstimatedCost: null,
    electricalEstimatedCost: null,
    emdAmount: 84531,
    completionPeriod: '6 (Six) months',
    submissionClosing: '2026-07-23T15:00:00',
    bidOpening: '2026-07-23T15:30:00',
    division: 'Almora',
    location: 'VPKAS, Hawalbagh and Almora (UK)',
    bidType: 'Percentage Rate',
    contractorEligibility: null,
    similarWorkCriteria: null,
    performanceGuaranteePercent: 5,
    securityDepositPercent: 2.5,
    civilDsrYear: 2023,
    civilCostIndexPercent: 29,
    electricalDsrYear: null,
    electricalCostIndexPercent: null,
  },
  boqLines: [
    {
      index: 0,
      itemNumber: 'C/1.1.1',
      description: 'Size of Tile 600x600 mm',
      quantity: 155,
      unit: 'sqm',
      unitCode: 'SQM',
      unitRecognised: true,
      rate: 2377.15,
      amount: 368458,
      derivedAmount: 368458.25,
      workPart: 'Civil Works',
      category: 'Flooring & Finishes',
      synthetic: false,
      renumbered: true,
    },
    {
      index: 1,
      itemNumber: 'C/1.2.1',
      description: 'Grouting the joints, size of Tile 600x600 mm',
      quantity: 1410,
      unit: 'sqm',
      unitCode: 'SQM',
      unitRecognised: true,
      rate: 387.9,
      amount: 546939,
      derivedAmount: 546939,
      workPart: 'Civil Works',
      category: 'Flooring & Finishes',
      synthetic: false,
      renumbered: true,
    },
  ],
  boqTotal: 4226546,
  derivedTotal: 915397.25,
  warnings: ['2 item numbers were prefixed by work part so each line is unique within the project.'],
};

function renderDialog() {
  const onClose = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ProjectFormDialog open project={null} onClose={onClose} />
    </QueryClientProvider>,
  );
  return { onClose };
}

async function uploadNit() {
  await userEvent.click(screen.getByRole('button', { name: /import from nit pdf/i }));
  const input = screen.getByTestId('nit-file-input');
  const file = new File(['%PDF-1.4'], 'almora-30-tile-work.pdf', { type: 'application/pdf' });
  await userEvent.upload(input, file);
}

describe('importing a project from its NIT', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    get.mockResolvedValue({ data: { content: [] } });
    post.mockImplementation((url: string) => {
      if (url === '/nit-imports/preview') {
        return Promise.resolve({ data: PREVIEW });
      }
      return Promise.resolve({
        data: { project: { id: 'p-new' }, nitDocumentId: 'n1', boqLineCount: 2, boqValue: 915397.25 },
      });
    });
    del.mockResolvedValue({ data: null });
  });

  it('fills the project form from the parsed notice', async () => {
    renderDialog();
    await uploadNit();

    await waitFor(() =>
      expect(screen.getByLabelText(/project code/i)).toHaveValue(
        '30-EE-ACD-CPWD-ALMORA-2026-27',
      ),
    );
    expect(screen.getByLabelText(/project name/i)).toHaveValue(
      'Tile work in Type-2 and Type-3 quarters at VPKAS, Hawalbagh and Almora (UK).',
    );
    expect(screen.getByLabelText(/nit number/i)).toHaveValue('30/EE/ACD/CPWD/Almora/2026-27');
    expect(screen.getByLabelText(/contract value/i)).toHaveValue('4226546');
  });

  /**
   * The values arrive after mount, through {@code reset}. MUI decides whether a label floats
   * by reading its input's value once, at mount, so on an uncontrolled field it never learns
   * the box has been filled — and every label sat across the text read out of the PDF.
   */
  it('floats the labels clear of the values it filled in', async () => {
    renderDialog();
    await uploadNit();

    await waitFor(() => expect(screen.getByLabelText(/project code/i)).not.toHaveValue(''));

    for (const field of [/project code/i, /project name/i, /nit number/i, /contract value/i]) {
      const input = screen.getByLabelText(field);
      const label = input.closest('.MuiFormControl-root')?.querySelector('label');
      expect(label).not.toBeNull();
      // The class MUI adds when the label moves up out of the box.
      expect(label?.className).toContain('MuiInputLabel-shrink');
    }
  });

  it('shows what the reader was unsure about, and keeps the schedule out of the way', async () => {
    renderDialog();
    await uploadNit();

    await waitFor(() => expect(screen.getByText(/worth checking/i)).toBeInTheDocument());
    expect(screen.getByText(/2 item numbers were prefixed by work part/i)).toBeInTheDocument();

    // Collapsed by default: the common case is that the parse is right.
    const accordion = screen.getByRole('button', { name: /2 BOQ lines .* review/i });
    expect(accordion).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(accordion);
    expect(await screen.findByText('C/1.1.1')).toBeInTheDocument();
  });

  it('sends the edited quantity rather than the one that was read', async () => {
    renderDialog();
    await uploadNit();

    await waitFor(() => expect(screen.getByLabelText(/project code/i)).not.toHaveValue(''));
    await userEvent.click(screen.getByRole('button', { name: /2 BOQ lines .* review/i }));

    const quantity = await screen.findByLabelText('Quantity for item C/1.2.1');
    await userEvent.clear(quantity);
    await userEvent.type(quantity, '1400');

    await userEvent.click(screen.getByRole('button', { name: /create project with 2 boq lines/i }));

    await waitFor(() => {
      const call = post.mock.calls.find(([url]) => url === '/nit-imports');
      expect(call).toBeDefined();
      const body = call?.[1] as { boqLines: { itemNumber: string; quantity: number }[] };
      expect(body.boqLines).toHaveLength(2);
      expect(body.boqLines[1]).toMatchObject({ itemNumber: 'C/1.2.1', quantity: 1400 });
      // The amount is derived server-side; sending one would let the two disagree.
      expect(body.boqLines[1]).not.toHaveProperty('amount');
    });
  });

  it('throws away the upload when the user backs out', async () => {
    const { onClose } = renderDialog();
    await uploadNit();
    await waitFor(() => expect(screen.getByLabelText(/project code/i)).not.toHaveValue(''));

    await userEvent.click(screen.getByRole('button', { name: /^cancel$/i }));

    expect(del).toHaveBeenCalledWith('/attachments/att-1');
    expect(onClose).toHaveBeenCalled();
  });
});
