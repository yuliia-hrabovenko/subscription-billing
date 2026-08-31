import { Box, Button, Divider, Drawer, List, ListItemButton, ListItemText, Toolbar, Typography } from '@mui/material';
import { Link as RouterLink, Outlet } from 'react-router-dom';
import { useAdminAuth } from '../auth/useAdminAuth';

const DRAWER_WIDTH = 240;

const NAV_ITEMS = [
  { label: 'Overview', to: '/admin' },
  { label: 'Customers', to: '/admin/customers' },
  { label: 'Plans', to: '/admin/plans' },
];

export function AdminLayout() {
  const { session, signOut } = useAdminAuth();

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <Drawer
        variant="permanent"
        sx={{
          width: DRAWER_WIDTH,
          flexShrink: 0,
          '& .MuiDrawer-paper': {
            width: DRAWER_WIDTH,
            boxSizing: 'border-box',
            bgcolor: '#0f172a',
            color: '#e2e8f0',
          },
        }}
      >
        <Toolbar>
          <Typography variant="h6" sx={{ color: 'inherit' }}>
            Admin
          </Typography>
        </Toolbar>
        <List sx={{ flexGrow: 1 }}>
          {NAV_ITEMS.map((item) => (
            <ListItemButton
              key={item.to}
              component={RouterLink}
              to={item.to}
              sx={{ color: 'inherit', '&:hover': { bgcolor: 'rgba(255,255,255,0.08)' } }}
            >
              <ListItemText primary={item.label} />
            </ListItemButton>
          ))}
        </List>
        <Divider sx={{ borderColor: 'rgba(255,255,255,0.12)' }} />
        <Box sx={{ p: 2 }}>
          <Typography variant="body2" sx={{ color: 'rgba(255,255,255,0.7)', mb: 1 }}>
            {session?.username}
          </Typography>
          <Button variant="outlined" size="small" onClick={signOut} sx={{ color: 'inherit', borderColor: 'rgba(255,255,255,0.4)' }}>
            Sign out
          </Button>
        </Box>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: 4 }}>
        <Outlet />
      </Box>
    </Box>
  );
}
