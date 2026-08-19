import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createQueryClient } from '../../app/queryClient';
import { AuthProvider } from './AuthContext';
import { LoginPage } from './LoginPage';

/**
 * The api module is mocked rather than the network: what matters here is that the screen
 * validates before it calls, reports a rejected credential in the server's own words, and
 * hands the user onward on success.
 */
const login = vi.fn();
const restoreSession = vi.fn();

vi.mock('./api', () => ({
  login: (username: string, password: string) => login(username, password),
  restoreSession: () => restoreSession(),
  logout: vi.fn(),
  fetchMe: vi.fn(),
}));

/**
 * The real query client, not a stub: signing in empties the read cache, and a stub would let
 * that go untested. It is what the app ships, built per case so nothing leaks between them.
 */
let queryClient = createQueryClient();

function renderLogin() {
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<div>Signed in home</div>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    queryClient = createQueryClient();
    restoreSession.mockResolvedValue({ state: 'SIGNED_OUT', reason: 'NONE' });
  });

  it('rejects an empty form in the browser without calling the API', async () => {
    const user = userEvent.setup({ delay: null });
    renderLogin();

    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Enter your username')).toBeInTheDocument();
    expect(screen.getByText('Enter your password')).toBeInTheDocument();
    expect(login).not.toHaveBeenCalled();
  });

  it('signs in and moves on to the requested screen', async () => {
    const user = userEvent.setup({ delay: null });
    login.mockResolvedValue({
      id: 'u1',
      username: 'vivek',
      fullName: 'Vivek Aggarwal',
      mustChangePassword: false,
      roles: ['SUPERVISOR'],
      permissions: ['attendance:create'],
      siteIds: ['s1'],
      allSites: false,
    });
    renderLogin();

    await user.type(screen.getByLabelText('Username'), 'vivek');
    await user.type(screen.getByLabelText('Password'), 'Nirman@123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(login).toHaveBeenCalledWith('vivek', 'Nirman@123'));
    expect(await screen.findByText('Signed in home')).toBeInTheDocument();
  });

  /**
   * The shift changes, the phone or the desk machine does not, and the tab is never closed —
   * so the read cache is still holding the last user's sites, their projects and their
   * figures, each of them fresh enough that nothing would go and ask again. The next person
   * signed in and read somebody else's site list.
   */
  it("does not hand the next person the last one's answers", async () => {
    const user = userEvent.setup({ delay: null });
    queryClient.setQueryData(['sites'], [{ id: 's1', name: 'Almora — Block A' }]);
    login.mockResolvedValue({
      id: 'u2',
      username: 'uttam',
      fullName: 'Uttam Singh',
      mustChangePassword: false,
      roles: ['ENGINEER'],
      permissions: ['dpr:verify'],
      siteIds: ['s9'],
      allSites: false,
    });
    renderLogin();

    await user.type(screen.getByLabelText('Username'), 'uttam');
    await user.type(screen.getByLabelText('Password'), 'Nirman@123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByText('Signed in home')).toBeInTheDocument());
    expect(queryClient.getQueryData(['sites'])).toBeUndefined();
  });

  it("shows the server's message when the credentials are refused", async () => {
    const user = userEvent.setup({ delay: null });
    login.mockRejectedValue({
      isAxiosError: true,
      response: { status: 401, data: { detail: 'The username or password is incorrect.' } },
    });
    renderLogin();

    await user.type(screen.getByLabelText('Username'), 'vivek');
    await user.type(screen.getByLabelText('Password'), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('The username or password is incorrect.')).toBeInTheDocument();
  });
});
