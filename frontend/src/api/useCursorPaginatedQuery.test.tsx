import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { useCursorPaginatedQuery } from './useCursorPaginatedQuery';

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

describe('useCursorPaginatedQuery', () => {
  it('omits the cursor on the first page and passes nextCursor on the second', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValueOnce({ items: [1, 2], nextCursor: 'page-2' })
      .mockResolvedValueOnce({ items: [3], nextCursor: undefined });

    const { result } = renderHook(
      () => useCursorPaginatedQuery({ queryKey: ['test'], fetchPage }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchPage).toHaveBeenNthCalledWith(1, undefined);
    expect(result.current.hasNextPage).toBe(true);

    await result.current.fetchNextPage();

    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2));
    expect(fetchPage).toHaveBeenNthCalledWith(2, 'page-2');
    expect(result.current.hasNextPage).toBe(false);
    expect(result.current.data?.pages.flatMap((page) => page.items ?? [])).toEqual([1, 2, 3]);
  });

  it('does not fetch when disabled', () => {
    const fetchPage = vi.fn();

    renderHook(() => useCursorPaginatedQuery({ queryKey: ['test'], fetchPage, enabled: false }), {
      wrapper,
    });

    expect(fetchPage).not.toHaveBeenCalled();
  });
});
