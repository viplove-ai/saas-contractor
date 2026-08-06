import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InstallPrompt } from './InstallPrompt';

/**
 * The prompt's whole value is in when it does *not* appear: once installed, and once
 * refused. Those two are the cases worth pinning down.
 */

const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1';
const ANDROID_UA =
  'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

function setUserAgent(ua: string) {
  Object.defineProperty(window.navigator, 'userAgent', { value: ua, configurable: true });
}

/** jsdom ships no matchMedia; the component asks it whether the app is already installed. */
function setDisplayMode({ standalone }: { standalone: boolean }) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: query.includes('standalone') ? standalone : false,
      media: query,
      addEventListener() {},
      removeEventListener() {},
    }),
  });
}

/** Stands in for Chrome's beforeinstallprompt. */
function fireInstallable(outcome: 'accepted' | 'dismissed' = 'accepted') {
  const event = new Event('beforeinstallprompt') as Event & {
    prompt: () => Promise<void>;
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
  };
  event.prompt = vi.fn().mockResolvedValue(undefined);
  event.userChoice = Promise.resolve({ outcome });
  // The listener sets state, so React wants the dispatch inside act.
  act(() => {
    window.dispatchEvent(event);
  });
  return event;
}

beforeEach(() => {
  localStorage.clear();
  setUserAgent(ANDROID_UA);
  setDisplayMode({ standalone: false });
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('InstallPrompt', () => {
  it('offers the install once the browser says the app is installable', async () => {
    render(<InstallPrompt />);
    expect(screen.queryByText(/install nirman/i)).not.toBeInTheDocument();

    fireInstallable();

    expect(await screen.findByText(/install nirman on this phone/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Install' })).toBeInTheDocument();
  });

  it('hands the install to the browser and closes', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<InstallPrompt />);
    const event = fireInstallable('accepted');

    await user.click(await screen.findByRole('button', { name: 'Install' }));

    expect(event.prompt).toHaveBeenCalledOnce();
    await waitFor(() => expect(screen.queryByText(/install nirman/i)).not.toBeInTheDocument());
  });

  it('does not ask again after the offer is dismissed', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const first = render(<InstallPrompt />);
    fireInstallable();
    await user.click(await screen.findByRole('button', { name: /close/i }));
    first.unmount();

    render(<InstallPrompt />);
    fireInstallable();

    await waitFor(() => expect(screen.queryByText(/install nirman/i)).not.toBeInTheDocument());
  });

  it('stays quiet when the app already runs from the home screen', async () => {
    setDisplayMode({ standalone: true });
    render(<InstallPrompt />);

    fireInstallable();

    await waitFor(() => expect(screen.queryByText(/install nirman/i)).not.toBeInTheDocument());
  });

  it('shows the Add to Home Screen steps on iOS, where there is no install event', async () => {
    setUserAgent(IPHONE_UA);
    render(<InstallPrompt />);
    expect(screen.queryByText(/install nirman/i)).not.toBeInTheDocument();

    act(() => vi.advanceTimersByTime(4000));

    expect(await screen.findByText(/add to home screen/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Install' })).not.toBeInTheDocument();
  });
});
