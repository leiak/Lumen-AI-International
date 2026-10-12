/**
 * Internal helper for the dispatch service modules (Iteration 4.1 PR6).
 *
 * The `request` axios instance's response interceptor returns the
 * unwrapped data (`T` from `R<T>`) at RUNTIME, but TypeScript still sees
 * the underlying call as `Promise<AxiosResponse<R<T>>>` because that is
 * what `axios.get<T>(...)` declares. Service methods need to expose the
 * unwrapped shape so callers can write `const o = await service.get(id);
 * o.no` without an `as unknown as ...` cast at every call site.
 *
 * `asData<T>(p)` is a 1-line adapter that erases the AxiosResponse
 * layer for TS purposes. The runtime value is already correct — we are
 * only making TypeScript agree with the interceptor.
 *
 * Lives in `_util.ts` (underscore prefix) so the import is not part of
 * the public service barrel.
 */

export async function asData<T>(p: Promise<unknown>): Promise<T> {
  return (await p) as T;
}
