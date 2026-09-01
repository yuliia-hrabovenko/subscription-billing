import { Box, Button, Typography } from '@mui/material';
import { useAdminAuth } from '../auth/useAdminAuth';
import { SidebarLayout, type SidebarNavItem } from './SidebarLayout';

const NAV_ITEMS: SidebarNavItem[] = [
  { label: 'Overview', to: '/admin' },
  { label: 'Customers', to: '/admin/customers' },
  { label: 'Plans', to: '/admin/plans' },
];

export function AdminLayout() {
  const { session, signOut } = useAdminAuth();

  return (
    <SidebarLayout
      brand="Admin"
      navItems={NAV_ITEMS}
      footer={
        <Box>
          <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.7)', mb: 1 }}>
            {session?.username}
          </Typography>
          <Button variant="outlined" size="small" onClick={signOut} sx={{ color: 'inherit', borderColor: 'rgba(255,255,255,0.4)' }}>
            Sign out
          </Button>
        </Box>
      }
    />
  );
}
