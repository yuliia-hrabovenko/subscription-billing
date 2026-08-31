import { expect, test } from '@playwright/test';

const API_BASE_URL = process.env.VITE_API_BASE_URL ?? 'http://localhost:8080';
const ADMIN_USERNAME = 'admin';
const ADMIN_PASSWORD = 'dev-only-admin-password-override-with-ADMIN_PASSWORD_HASH';

let seededCustomerEmail = '';

test.beforeAll(async ({ request }) => {
  const plansResponse = await request.get(`${API_BASE_URL}/api/v1/plans`).catch(() => null);
  if (!plansResponse?.ok()) {
    throw new Error(
      `Backend not reachable at ${API_BASE_URL} -- start it first: mvn -pl api spring-boot:run (with docker-compose's postgres up).`,
    );
  }

  // The Free plan is always seeded first and needs no payment method, unlike Pro --
  // same assumption signup-flow.spec.ts makes.
  const [freePlan] = await plansResponse.json();
  seededCustomerEmail = `admin-e2e-${Date.now()}@example.com`;
  const signupResponse = await request.post(`${API_BASE_URL}/api/v1/subscriptions`, {
    data: { planId: freePlan.id, email: seededCustomerEmail, useTrial: true },
  });
  if (!signupResponse.ok()) {
    throw new Error('Failed to seed a customer for the admin e2e test');
  }
});

test('admin logs in, manages a plan, and drills into a customer', async ({ page }) => {
  await page.goto('/admin/login');
  await page.getByLabel('Username').fill(ADMIN_USERNAME);
  await page.getByLabel('Password').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL('**/admin');
  await expect(page.getByText('Total plans')).toBeVisible();

  await page.getByRole('link', { name: 'Plans' }).click();
  await page.waitForURL('**/admin/plans');

  const code = `E2E${Date.now()}`;
  await page.getByRole('button', { name: '+ New Plan' }).click();
  await page.getByLabel('Code').fill(code);
  await page.getByLabel('Name').fill('E2E Test Plan');
  await page.getByLabel('Initial price').fill('9.99');
  await page.getByRole('button', { name: 'Create' }).click();

  await expect(page.getByText(code)).toBeVisible();
  await page.getByText('E2E Test Plan').click();
  await page.waitForURL(/\/admin\/plans\/.+/);
  await expect(page.getByText(`E2E Test Plan (${code})`)).toBeVisible();

  await page.getByRole('button', { name: 'Add price version' }).click();
  await page.getByLabel('Amount').fill('12.00');
  await page.locator('input[type="date"]').fill('2026-12-01');
  await page.getByRole('button', { name: 'Add' }).click();
  await expect(page.getByText('$12')).toBeVisible();

  await page.getByRole('button', { name: 'Retire plan' }).click();
  await expect(page.getByText('RETIRED')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Retire plan' })).toHaveCount(0);

  await page.getByRole('link', { name: 'Customers' }).click();
  await page.waitForURL('**/admin/customers');
  await expect(page.getByText(seededCustomerEmail)).toBeVisible();

  await page.getByText(seededCustomerEmail).click();
  await page.waitForURL(/\/admin\/customers\/.+/);
  await expect(page.getByRole('heading', { name: seededCustomerEmail })).toBeVisible();

  await page.getByText('Free').first().click();
  await page.waitForURL(/\/admin\/subscriptions\/.+/);
  await expect(page.getByRole('heading', { name: 'Free' })).toBeVisible();
});

test('an unauthenticated visitor hitting a gated admin route lands on admin login', async ({ page }) => {
  await page.goto('/admin');
  await page.waitForURL('**/admin/login');
  await expect(page.getByRole('heading', { name: 'Admin sign in' })).toBeVisible();
});
