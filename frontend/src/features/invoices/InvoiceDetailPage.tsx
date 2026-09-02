import { Button, List, ListItem, ListItemText, Stack, Typography } from '@mui/material';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { downloadReceipt } from './downloadReceipt';
import { useInvoice } from './useInvoices';

export function InvoiceDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const { data: invoice, isLoading, isError, error, refetch } = useInvoice(id);
  const { isAuthenticated } = useAuth();
  const [downloadError, setDownloadError] = useState<unknown>(null);
  const [isDownloading, setIsDownloading] = useState(false);

  if (isLoading) {
    return <LoadingState label="Loading invoice…" />;
  }

  if (isError) {
    return <ErrorState error={error} onRetry={() => refetch()} />;
  }

  if (!invoice) {
    return <EmptyState message="Invoice not found." />;
  }

  const handleDownload = async () => {
    if (!invoice.id) {
      return;
    }
    setDownloadError(null);
    setIsDownloading(true);
    try {
      await downloadReceipt(invoice.id);
    } catch (downloadFailed) {
      // A 401 already cleared the session (downloadReceipt's own auth handling) --
      // RequireAuth will redirect this route to /session-expired on the next render,
      // so showing an inline "unauthorized" message here would just flash and vanish.
      if (isAuthenticated) {
        setDownloadError(downloadFailed);
      }
    } finally {
      setIsDownloading(false);
    }
  };

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {invoice.planName} — {invoice.billingPeriod}
      </Typography>
      <Typography>Amount: ${invoice.amount}</Typography>
      <Typography>Status: {invoice.status}</Typography>
      {invoice.createdAt && (
        <Typography color="text.secondary">Created {new Date(invoice.createdAt).toLocaleString()}</Typography>
      )}

      <Typography variant="h6">Payment attempts</Typography>
      {!invoice.paymentAttempts || invoice.paymentAttempts.length === 0 ? (
        <EmptyState message="No payment attempts recorded." />
      ) : (
        <List>
          {invoice.paymentAttempts.map((attempt) => (
            <ListItem key={attempt.id}>
              <ListItemText
                primary={attempt.status}
                secondary={attempt.attemptedAt ? new Date(attempt.attemptedAt).toLocaleString() : undefined}
              />
            </ListItem>
          ))}
        </List>
      )}

      {downloadError !== null && <ErrorState error={downloadError} />}
      <Button variant="contained" onClick={handleDownload} disabled={isDownloading} sx={{ alignSelf: 'flex-start' }}>
        {isDownloading ? 'Downloading…' : 'Download receipt'}
      </Button>
    </Stack>
  );
}
