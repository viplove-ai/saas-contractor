// defineConfig comes from vitest/config, not vite: only that one accepts the `test` block
// below. Importing it from 'vite' leaves `test` unknown to the compiler and fails the
// typecheck, since tsconfig type-checks this file too.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import path from 'node:path';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt', // a supervisor mid-entry must never be reloaded out from under
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'Nirman Constructions',
        short_name: 'Nirman',
        description: 'Attendance, materials, expenses and daily reports for construction sites',
        theme_color: '#14181D',
        background_color: '#FFFFFF',
        display: 'standalone',
        orientation: 'portrait',
        start_url: '/',
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-512-maskable.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        navigateFallbackDenylist: [/^\/api/],
        /*
          Read caching is an allow-list, and everything not named here stays uncached: a
          stale figure is worse than a missing one, because a missing one is obviously
          missing. Two things earn a place on the list.

          Reference data — who the workers are, what the materials are called, which sites
          exist — changes on the scale of weeks and is what every entry screen needs to
          render at all. A day-old copy of it is not a wrong answer, it is the same answer.

          The attendance roster is the one operational read that joins them, because marking
          the muster is the thing this app exists to do with no signal, and a roster that
          only lives in memory is gone the moment the supervisor closes the app. Nothing is
          decided from the cached copy: the wage rate on it is advisory until the server
          freezes the real one at verification, and a period that has closed since it was
          fetched is caught when the record is sent and comes back to the sync screen as a
          question. What the cached roster supplies is the list of names to tick.

          Order matters — workbox takes the first route that matches — so the reads that
          must never be served stale are shadowed above the pattern that would catch them.
        */
        runtimeCaching: [
          {
            // Money owed, on the same prefix as the vendor list below it.
            urlPattern: /\/api\/v1\/vendors\/balances/,
            handler: 'NetworkOnly',
          },
          {
            urlPattern: /\/api\/v1\/attendance\/roster(\?|$)/,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'roster',
              networkTimeoutSeconds: 5,
              // A week: long enough for a posting with no coverage, short enough that a
              // roster nobody has refreshed since last month is not offered as today's.
              expiration: { maxEntries: 60, maxAgeSeconds: 60 * 60 * 24 * 7 },
              cacheableResponse: { statuses: [200] },
            },
          },
          {
            urlPattern:
              /\/api\/v1\/(materials|workers|units|sites|vendors|roles|boq-items|skill-categories|expense-categories|labour-contractors)(\/|\?|$)/,
            handler: 'NetworkFirst',
            options: {
              cacheName: 'reference-data',
              networkTimeoutSeconds: 5,
              // Thirty days, up from one. A day was enough for a phone that reconnects each
              // evening and useless for the case this app is built for — the second morning
              // in a row with no signal, when the day-old copy has just expired and the
              // roster screen has nothing to draw.
              expiration: { maxEntries: 300, maxAgeSeconds: 60 * 60 * 24 * 30 },
              cacheableResponse: { statuses: [200] },
            },
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    host: true,
    // The app calls a relative /api/v1 in dev; the backend answers on 8080.
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    globals: true,
    // Vitest owns src/, Playwright owns e2e/. Without this, Vitest's default glob picks up
    // the .spec.ts files under e2e/ and fails on Playwright's imports.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    // Vitest's 5s default is not enough for the screens that open a MUI dialog or drawer:
    // in jsdom those render the whole component tree twice over, and under the parallel
    // load of a full run a test that takes ~2s alone can take three times that. The tests
    // were passing individually and failing in the suite, which is a machine speed problem
    // rather than a slow assertion, so the limit moves rather than the tests.
    testTimeout: 20_000,
  },
});
