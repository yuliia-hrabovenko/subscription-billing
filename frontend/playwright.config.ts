import { defineConfig } from '@playwright/test';

// Backend must already be running (mvn -pl api spring-boot:run against docker-compose's
// postgres) — there's no lightweight way to boot the full Spring context from here, and
// these tests exercise the real API end to end rather than mocking it.
export default defineConfig({
  testDir: './e2e',
  use: {
    baseURL: 'http://localhost:5173',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
  },
});
