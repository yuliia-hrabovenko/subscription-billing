import { Box, Divider, Drawer, List, ListItemButton, ListItemText, Toolbar, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';

const DRAWER_WIDTH = 240;
const SIDEBAR_BG = '#0f172a';
const SIDEBAR_FG = '#e2e8f0';

export interface SidebarNavItem {
  label: string;
  to: string;
}

function findActiveItem(navItems: SidebarNavItem[], pathname: string): SidebarNavItem | undefined {
  const exact = navItems.find((item) => item.to === pathname);
  if (exact) {
    return exact;
  }
  // Falls back to the longest matching ancestor for a detail route (e.g. /admin/plans/:id
  // should still highlight "Plans") -- "/" is excluded here or it would match every path.
  return navItems
    .filter((item) => item.to !== '/' && pathname.startsWith(`${item.to}/`))
    .sort((a, b) => b.to.length - a.to.length)[0];
}

/**
 * Dark sidebar + light content shell shared by the admin and customer portals so the two
 * personas' nav chrome stays visually consistent without duplicating the Drawer styling.
 * Auth state, nav destinations, and the footer (sign-out, etc.) are all persona-specific
 * and passed in by the caller -- this component knows nothing about either persona.
 */
export function SidebarLayout({
  brand,
  navItems,
  footer,
  contentMaxWidth,
}: {
  brand: string;
  navItems: SidebarNavItem[];
  footer?: ReactNode;
  contentMaxWidth?: number;
}) {
  const { pathname } = useLocation();
  const activeItem = findActiveItem(navItems, pathname);

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
            bgcolor: SIDEBAR_BG,
            color: SIDEBAR_FG,
          },
        }}
      >
        <Toolbar>
          <Typography variant="h6" sx={{ color: 'inherit' }}>
            {brand}
          </Typography>
        </Toolbar>
        <List sx={{ flexGrow: 1 }}>
          {navItems.map((item) => (
            <ListItemButton
              key={item.to}
              component={RouterLink}
              to={item.to}
              selected={item === activeItem}
              sx={{
                color: 'inherit',
                '&:hover': { bgcolor: 'rgba(255,255,255,0.08)' },
                '&.Mui-selected': { bgcolor: 'rgba(255,255,255,0.16)' },
                '&.Mui-selected:hover': { bgcolor: 'rgba(255,255,255,0.2)' },
              }}
            >
              <ListItemText primary={item.label} />
            </ListItemButton>
          ))}
        </List>
        {footer && (
          <>
            <Divider sx={{ borderColor: 'rgba(255,255,255,0.12)' }} />
            <Box sx={{ p: 2 }}>{footer}</Box>
          </>
        )}
      </Drawer>
      <Box
        component="main"
        sx={{
          flexGrow: 1,
          p: 4,
          ...(contentMaxWidth ? { maxWidth: contentMaxWidth, mx: 'auto', width: '100%' } : {}),
        }}
      >
        <Outlet />
      </Box>
    </Box>
  );
}
