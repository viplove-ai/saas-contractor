/**
 * ESLint 8 (eslintrc format — matches the pinned eslint ^8.57).
 *
 * Division of labour: tsconfig is already strict (noUncheckedIndexedAccess,
 * exactOptionalPropertyTypes), so the compiler owns type errors and this file owns only the
 * patterns tsc cannot see. `npm run lint` runs with --max-warnings 0, so every rule below is
 * either error or off — nothing is left at "warn" to be quietly ignored.
 */
module.exports = {
  root: true,
  env: { browser: true, es2022: true },
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  plugins: ['@typescript-eslint', 'react-hooks'],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: [
    'dist',
    'dev-dist',
    'node_modules',
    'coverage',
    'playwright-report',
    'test-results',
    '.eslintrc.cjs',
  ],
  rules: {
    // TypeScript resolves identifiers itself; the base rule reports false positives on
    // types, interfaces and DOM globals.
    'no-undef': 'off',

    // Leading underscore is the deliberate "unused on purpose" marker.
    'no-unused-vars': 'off',
    '@typescript-eslint/no-unused-vars': [
      'error',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
    ],

    // The React root mount is the one place a non-null assertion is honest: if #root is
    // missing the app cannot start at all, and a guard would only defer the same crash.
    '@typescript-eslint/no-non-null-assertion': 'off',

    // Money and quantity handling must not silently accept `any` off an API boundary.
    '@typescript-eslint/no-explicit-any': 'error',

    // == between a numeric string and a number is exactly the bug class this app cannot
    // afford. `x == null` is exempt: it is the deliberate "null or undefined" idiom, used
    // in shared/formatters.ts so a missing amount renders as an em dash rather than "0".
    eqeqeq: ['error', 'always', { null: 'ignore' }],
    'no-console': ['error', { allow: ['warn', 'error'] }],
  },
  overrides: [
    {
      // Build and test tooling runs in Node, not the browser.
      files: ['vite.config.ts', 'playwright.config.ts', 'e2e/**/*.ts'],
      env: { node: true, browser: true },
    },
    {
      // Playwright and Vitest supply their own globals.
      files: ['e2e/**/*.ts', 'src/**/*.test.ts', 'src/**/*.test.tsx', 'src/test/**/*.ts'],
      rules: { 'no-console': 'off' },
    },
  ],
};
