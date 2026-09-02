import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { AdminAuthProvider } from './auth/AdminAuthContext';
import { AuthProvider } from './auth/AuthContext';
import { AppRoutes } from './routes/AppRoutes';
import { theme } from './theme';

const queryClient = new QueryClient();

export default function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AuthProvider>
            <AdminAuthProvider>
              <AppRoutes />
            </AdminAuthProvider>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  );
}
