import { expect, test } from '@playwright/test';

/**
 * Shell-level checks: the bundle boots, the auth guard routes correctly, and the PWA is
 * installable. They run against a production build with no backend, so everything here is
 * the signed-out experience — the signed-in journeys are covered by the backend
 * integration tests now and join Playwright in Phase 7 with the offline flows.
 */

test('an anonymous visit lands on the sign-in screen', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Good morning.' })).toBeVisible();
  await expect(page.getByLabel('Username')).toBeVisible();
  await expect(page.getByLabel('Password')).toBeVisible();
});

test('a deep link is guarded and redirects to sign-in', async ({ page }) => {
  await page.goto('/attendance/mark');

  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Good morning.' })).toBeVisible();
});

test('an empty submit shows field errors instead of calling the API', async ({ page }) => {
  const apiCalls: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/')) {
      apiCalls.push(request.url());
    }
  });

  await page.goto('/login');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByText('Enter your username')).toBeVisible();
  await expect(page.getByText('Enter your password')).toBeVisible();
  expect(apiCalls).toEqual([]);
});

test('the app boots without console errors', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') {
      errors.push(message.text());
    }
  });
  page.on('pageerror', (error) => errors.push(error.message));

  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Good morning.' })).toBeVisible();

  expect(errors).toEqual([]);
});

/**
 * Guards the icon set directly: a missing file under public/ makes the app fail to install
 * on a phone, which is silent in every other test we run.
 */
test('the PWA manifest is served with every icon it declares', async ({ page, request }) => {
  const response = await request.get('/manifest.webmanifest');
  expect(response.ok()).toBeTruthy();

  const manifest = await response.json();
  expect(manifest.name).toBe('Nirman Constructions');
  expect(manifest.display).toBe('standalone');
  expect(manifest.icons.length).toBeGreaterThanOrEqual(3);

  // Every declared icon must actually resolve, including the maskable one.
  for (const icon of manifest.icons) {
    const iconResponse = await request.get(`/${String(icon.src).replace(/^\//, '')}`);
    expect(iconResponse.ok(), `icon missing: ${icon.src}`).toBeTruthy();
    expect(Number(iconResponse.headers()['content-length'])).toBeGreaterThan(0);
  }

  expect(manifest.icons.some((icon: { purpose?: string }) => icon.purpose === 'maskable')).toBe(
    true,
  );

  // Referenced by index.html, so a 404 here shows up as a broken tab icon everywhere.
  const favicon = await page.request.get('/favicon.svg');
  expect(favicon.ok()).toBeTruthy();
});
