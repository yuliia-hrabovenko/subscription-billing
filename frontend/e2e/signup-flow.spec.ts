import { expect, test } from '@playwright/test';

const API_BASE_URL = process.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

test.beforeAll(async ({ request }) => {
  const response = await request.get(`${API_BASE_URL}/api/v1/plans`).catch(() => null);
  if (!response?.ok()) {
    throw new Error(
      `Backend not reachable at ${API_BASE_URL} -- start it first: mvn -pl api spring-boot:run (with docker-compose's postgres up).`,
    );
  }
});

test('browse plans, sign up for the free plan, land on dashboard, then cancel', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Choose a plan' })).toBeVisible();

  // The Free plan is always seeded first and needs no payment method, unlike Pro.
  await page.getByRole('link', { name: 'Sign up' }).first().click();
  await expect(page.getByRole('heading', { name: 'Sign up' })).toBeVisible();

  await page.getByLabel('Email').fill(`e2e-${Date.now()}@example.com`);
  await page.getByRole('button', { name: 'Sign up' }).click();

  await expect(page.getByRole('heading', { name: 'Your subscription' })).toBeVisible();
  await expect(page.getByText('ACTIVE')).toBeVisible();

  await page.getByRole('button', { name: 'Cancel subscription' }).click();
  await expect(page.getByText('CANCELED')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Cancel subscription' })).not.toBeVisible();
});

test('an unauthenticated visitor hitting /dashboard lands on the session-expired screen', async ({ page }) => {
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Session expired' })).toBeVisible();
});
