import { useInfiniteQuery } from '@tanstack/react-query';

export interface CursorPage<TItem> {
  items?: TItem[];
  nextCursor?: string;
}

/**
 * Wraps @tanstack/react-query's useInfiniteQuery for this backend's cursor pagination,
 * bypassing openapi-react-query's own useInfiniteQuery wrapper: that wrapper injects
 * initialPageParam straight into the query string as the first request's cursor value,
 * but this backend has no cursor value that means "first page" -- it rejects anything
 * but a real one with INVALID_CURSOR. fetchPage only receives a cursor once a real one
 * exists, so the first request omits it entirely.
 */
export function useCursorPaginatedQuery<TItem>({
  queryKey,
  fetchPage,
  enabled = true,
}: {
  queryKey: readonly unknown[];
  fetchPage: (cursor: string | undefined) => Promise<CursorPage<TItem>>;
  enabled?: boolean;
}) {
  return useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }: { pageParam: string | undefined }) => fetchPage(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled,
  });
}
