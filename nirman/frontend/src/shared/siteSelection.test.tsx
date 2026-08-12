import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { forgetSelectedSite, useSelectedSite } from './siteSelection';

const SITES = [
  { id: 'site-a', code: 'KSN-A' },
  { id: 'site-b', code: 'KSN-B' },
];

/**
 * A stand-in for any screen with a site picker on it. Two of them on one page is not a real
 * layout, but it is exactly what navigating between two screens looks like to the store: the
 * question is whether the second one agrees with the first.
 */
function Screen({
  label,
  sites = SITES,
  allowAll = false,
}: {
  label: string;
  sites?: { id: string }[] | undefined;
  allowAll?: boolean;
}) {
  const [siteId, choose] = useSelectedSite(sites, { allowAll });
  return (
    <div>
      <span data-testid={`${label}-site`}>{siteId || 'none'}</span>
      <button onClick={() => choose('site-b')}>{label}: choose B</button>
      <button onClick={() => choose('')}>{label}: every site</button>
    </div>
  );
}

describe('useSelectedSite', () => {
  beforeEach(() => {
    forgetSelectedSite();
  });

  it('opens on the first site the account can reach when nothing has been chosen', () => {
    render(<Screen label="one" />);

    expect(screen.getByTestId('one-site')).toHaveTextContent('site-a');
  });

  /** The whole point: the site chosen on one screen is the site the next one opens on. */
  it('carries the chosen site to the next screen', async () => {
    const user = userEvent.setup({ delay: null });
    const { unmount } = render(<Screen label="first" />);

    await user.click(screen.getByRole('button', { name: 'first: choose B' }));
    unmount();
    render(<Screen label="second" />);

    expect(screen.getByTestId('second-site')).toHaveTextContent('site-b');
  });

  it('agrees across two screens mounted at once', async () => {
    const user = userEvent.setup({ delay: null });
    render(
      <>
        <Screen label="left" />
        <Screen label="right" />
      </>,
    );

    await user.click(screen.getByRole('button', { name: 'left: choose B' }));

    expect(screen.getByTestId('right-site')).toHaveTextContent('site-b');
  });

  /**
   * A site the account no longer reaches — reassigned overnight, or a link to somebody
   * else's block — is not shown back to them as their site.
   */
  it('falls back when the remembered site is not one this screen can offer', async () => {
    const user = userEvent.setup({ delay: null });
    const { unmount } = render(<Screen label="wide" />);
    await user.click(screen.getByRole('button', { name: 'wide: choose B' }));
    unmount();

    render(<Screen label="narrow" sites={[{ id: 'site-a' }]} />);

    expect(screen.getByTestId('narrow-site')).toHaveTextContent('site-a');
  });

  /**
   * Widening a register to every site is a thing you do for a minute. It must not read as a
   * decision about where the person is working, or every other screen follows it.
   */
  it('does not let "every site" erase the site the other screens are on', async () => {
    const user = userEvent.setup({ delay: null });
    render(
      <>
        <Screen label="register" allowAll />
        <Screen label="entry" />
      </>,
    );
    await user.click(screen.getByRole('button', { name: 'register: choose B' }));

    await user.click(screen.getByRole('button', { name: 'register: every site' }));

    expect(screen.getByTestId('register-site')).toHaveTextContent('none');
    expect(screen.getByTestId('entry-site')).toHaveTextContent('site-b');
  });

  /** A list of one is not a choice, even on a register that can show them all. */
  it('stands a one-site account on its site even where every site is offered', () => {
    render(<Screen label="solo" sites={[{ id: 'site-a' }]} allowAll />);

    expect(screen.getByTestId('solo-site')).toHaveTextContent('site-a');
  });

  it('shows every site to a multi-site account that has chosen nothing', () => {
    render(<Screen label="all" allowAll />);

    expect(screen.getByTestId('all-site')).toHaveTextContent('none');
  });
});
