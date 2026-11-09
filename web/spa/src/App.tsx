import { BrowserRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PublicPage } from './pages/PublicPage';
import { StagePage } from './pages/StagePage';
import { ConsolePage } from './pages/ConsolePage';

const queryClient = new QueryClient({ defaultOptions: { queries: { refetchOnWindowFocus: false } } });

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<PublicPage />} />
          <Route path="/stage" element={<StagePage />} />
          <Route path="/console" element={<ConsolePage />} />
          <Route path="*" element={<PublicPage />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
