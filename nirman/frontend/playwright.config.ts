import { defineConfig, devices } from '@playwright/test';

/**
 * E2E runs against a production build, not the dev server. Two reasons: the PWA manifest,
 * service worker and icons only exist after `vite build`, and those are exactly what the
 * offline story depends on — testing the dev server would skip them. It also means `npm run
 * e2e` fails if `tsc -b` fails, so a type error cannot reach a green e2e run.
 *
 * Point E2E_BASE_URL at a already-running deployment to skip the local build entirely.
 */
const PORT = 4173;
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${PORT}`;

/**
 * Which browser binary to drive. CI leaves this unset and gets Playwright's own bundled
 * Chromium, which is the build the suite is meant to be judged on.
 *
 * <p>`PLAYWRIGHT_CHANNEL=chrome` points it at the Chrome already installed on the machine.
 * That exists for the same reason AbstractIntegrationTest has a no-Docker path: the primary
 * dev machine is macOS 12, and Playwright stopped shipping a Chromium for it — so without
 * this the offline tests could only ever be run by CI, which is a poor place to find out
 * that a sync bug exists.</p>
 */
const channel = process.env.PLAYWRIGHT_CHANNEL
  ? { channel: process.env.PLAYWRIGHT_CHANNEL }
  : {};

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // Spread rather than `workers: undefined`: tsconfig sets exactOptionalPropertyTypes, which
  // rejects an explicit undefined on an optional property. Omitting it lets Playwright pick
  // its own default (one worker per core).
  ...(process.env.CI ? { workers: 1 } : {}),
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],

  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    // Supervisors are the primary users and they are on a phone, so that runs first.
    { name: 'mobile-chrome', use: { ...devices['Pixel 5'], ...channel } },
    { name: 'desktop-chrome', use: { ...devices['Desktop Chrome'], ...channel } },
  ],

  // Skipped when E2E_BASE_URL points somewhere already serving the app.
  ...(process.env.E2E_BASE_URL
    ? {}
    : {
        webServer: {
          command: `npm run build && npm run preview -- --port ${PORT} --strictPort`,
          url: `http://localhost:${PORT}`,
          reuseExistingServer: !process.env.CI,
          timeout: 180_000,
        },
      }),
});
