import { baseUrl } from '../../api/client';
import { sessionStore } from '../../auth/sessionStore';

/**
 * The receipt endpoint's response media type is declared as a wildcard (a PDF, not
 * JSON) in the generated schema, which openapi-fetch's typed `parseAs: 'blob'` overload
 * can't resolve against — it collapses to `never`. There's no JSON body to type-check
 * here anyway, so this calls fetch directly instead of fighting that generic,
 * replicating the auth middleware's two jobs (attach the bearer token, clear the
 * session on 401).
 */
export async function downloadReceipt(invoiceId: string): Promise<void> {
  const session = sessionStore.get();
  const response = await fetch(`${baseUrl}/api/v1/invoices/${invoiceId}/receipt`, {
    headers: session ? { Authorization: `Bearer ${session.accessToken}` } : undefined,
  });

  if (response.status === 401) {
    sessionStore.clear();
  }
  if (!response.ok) {
    throw new Error(`Failed to download receipt: ${response.status} ${response.statusText}`);
  }

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `invoice-${invoiceId}-receipt.pdf`;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
