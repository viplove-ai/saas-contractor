import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProjectGalleryPage } from './ProjectGalleryPage';
import type { GalleryPhoto, PageResponse } from './types';

const get = vi.fn();

vi.mock('../../shared/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../../shared/apiClient')>(
    '../../shared/apiClient',
  );
  return {
    ...actual,
    apiClient: {
      get: (...args: unknown[]) => get(...args),
    },
  };
});

const PROJECT = { id: 'p1', code: 'KSN', name: 'Kausani Hostel', status: 'ACTIVE' };
const SITES = [
  { id: 'site-a', projectId: 'p1', code: 'KSN-A', name: 'Main Block' },
  { id: 'site-b', projectId: 'p1', code: 'KSN-B', name: 'Annexe' },
];

function photo(overrides: Partial<GalleryPhoto> & { id: string }): GalleryPhoto {
  return {
    attachmentId: `att-${overrides.id}`,
    caption: 'the work face',
    dprId: 'd1',
    dprNumber: 'DPR-2025-0001',
    reportDate: '2025-03-03',
    workflowStatus: 'SUBMITTED',
    siteId: 'site-a',
    siteCode: 'KSN-A',
    siteName: 'Main Block',
    ...overrides,
  };
}

function page(content: GalleryPhoto[], last = true, pageNo = 0): PageResponse<GalleryPhoto> {
  return {
    content,
    page: pageNo,
    size: 60,
    totalElements: last ? content.length + pageNo * 60 : 61,
    totalPages: last ? pageNo + 1 : 2,
    first: pageNo === 0,
    last,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/projects/p1/gallery']}>
        <Routes>
          <Route path="/projects/:projectId/gallery" element={<ProjectGalleryPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Answers the project, its sites, the gallery pages and a signed link for every picture. */
function answer(pages: PageResponse<GalleryPhoto>[]) {
  get.mockImplementation((url: string, config?: { params?: Record<string, unknown> }) => {
    if (url === '/projects/p1') return Promise.resolve({ data: PROJECT });
    if (url === '/sites') return Promise.resolve({ data: SITES });
    if (url === '/dprs/photos') {
      const pageNo = Number(config?.params?.page ?? 0);
      return Promise.resolve({ data: pages[pageNo] ?? page([], true, pageNo) });
    }
    if (url.startsWith('/attachments/')) {
      return Promise.resolve({ data: { url: `blob:${url}`, fileName: 'site.jpg' } });
    }
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}

describe('ProjectGalleryPage', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('groups the photographs by the day they were taken for, naming site and report', async () => {
    answer([
      page([
        photo({ id: '1', caption: 'slab', reportDate: '2025-03-04', dprNumber: 'DPR-2025-0002' }),
        photo({ id: '2', caption: 'north wall' }),
        photo({ id: '3', caption: 'annexe gate', siteCode: 'KSN-B', siteId: 'site-b' }),
      ]),
    ]);
    renderPage();

    expect(await screen.findByText('KSN — Kausani Hostel')).toBeInTheDocument();
    expect(await screen.findByText('2025-03-04')).toBeInTheDocument();
    expect(screen.getByText('2025-03-03')).toBeInTheDocument();
    expect(screen.getByText('3 photographs')).toBeInTheDocument();
    expect(screen.getByText('slab')).toBeInTheDocument();
    expect(screen.getByText('annexe gate')).toBeInTheDocument();
    expect(screen.getByText('KSN-B')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'DPR-2025-0002' })).toHaveAttribute('href', '/dpr/d1');
    // The pictures themselves draw off signed links, one per photograph.
    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(3));
  });

  it('offers earlier days a page at a time', async () => {
    answer([
      page([photo({ id: '1', caption: 'this week' })], false, 0),
      page([photo({ id: '2', caption: 'last spring', reportDate: '2024-12-15' })], true, 1),
    ]);
    renderPage();

    expect(await screen.findByText('this week')).toBeInTheDocument();
    expect(screen.queryByText('last spring')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Show earlier days' }));
    expect(await screen.findByText('last spring')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show earlier days' })).not.toBeInTheDocument();
  });

  it('says so when nothing has been photographed yet', async () => {
    answer([page([])]);
    renderPage();
    expect(await screen.findByText('No photographs yet')).toBeInTheDocument();
  });
});
