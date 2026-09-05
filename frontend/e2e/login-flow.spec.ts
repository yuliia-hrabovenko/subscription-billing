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

test('a customer signs up, then logs back in with the same credentials and lands on the dashboard', async ({ page }) => {
  const email = `login-e2e-${Date.now()}@example.com`;
  const password = 'password123!';

  await page.goto('/');
  await page.getByRole('link', { name: 'Choose the plan' }).first().click();
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page.getByRole('heading', { name: 'Your subscription' })).toBeVisible();

  // Simulate a cleared session (new device / cleared localStorage) rather than the
  // signup response's token, so this only passes if login itself issues a working one.
  await page.evaluate(() => localStorage.clear());
  await page.goto('/login');

  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Log in' }).click();

  await expect(page.getByRole('heading', { name: 'Your subscription' })).toBeVisible();
  await expect(page.getByText('ACTIVE')).toBeVisible();
});

test('logging in with the wrong password is rejected', async ({ page }) => {
  const email = `login-wrong-pw-e2e-${Date.now()}@example.com`;

  await page.goto('/');
  await page.getByRole('link', { name: 'Choose the plan' }).first().click();
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('password123!');
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page.getByRole('heading', { name: 'Your subscription' })).toBeVisible();

  await page.evaluate(() => localStorage.clear());
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill('the-wrong-password');
  await page.getByRole('button', { name: 'Log in' }).click();

  await expect(page.getByText('Invalid email or password')).toBeVisible();
});
