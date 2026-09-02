import { Card, CardContent, Typography } from '@mui/material';

export function StatCard({ label, value }: { label: string; value: number | string }) {
  return (
    <Card variant="outlined" sx={{ minWidth: 160 }}>
      <CardContent>
        <Typography variant="h4">{value}</Typography>
        <Typography color="text.secondary">{label}</Typography>
      </CardContent>
    </Card>
  );
}
