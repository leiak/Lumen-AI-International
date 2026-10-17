/**
 * Internal helper for the Finance service modules (Iteration 4.3.1 PR4).
 *
 * Mirrors `services/cs/_util.ts` and `services/dispatch/_util.ts` —
 * erases the AxiosResponse<T> layer for TypeScript so callers can write
 * `const o = await service.get(id); o.x` without casts. The runtime
 * value is already correct (request.ts response interceptor unwraps
 * `R<T>` envelope).
 *
 * Lives in `_util.ts` (underscore prefix) so the import is not part of
 * the public service barrel.
 */

export async function asData<T>(p: Promise<unknown>): Promise<T> {
  return (await p) as T;
}