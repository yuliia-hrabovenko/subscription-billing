import { Button } from '@mui/material';
import { useAuth } from '../auth/useAuth';
import { SidebarLayout, type SidebarNavItem } from './SidebarLayout';

export function AppLayout() {
  const { isAuthenticated, signOut } = useAuth();

  const navItems: SidebarNavItem[] = isAuthenticated
    ? [
        { label: 'Plans', to: '/' },
        { label: 'Dashboard', to: '/dashboard' },
        { label: 'Invoices', to: '/dashboard/invoices' },
      ]
    : [{ label: 'Plans', to: '/' }];

  return (
    <SidebarLayout
      brand="Subscription Billing"
      navItems={navItems}
      contentMaxWidth={960}
      footer={
        isAuthenticated ? (
          <Button variant="outlined" size="small" onClick={signOut} sx={{ color: 'inherit', borderColor: 'rgba(255,255,255,0.4)' }}>
            Sign out
          </Button>
        ) : undefined
      }
    />
  );
}
