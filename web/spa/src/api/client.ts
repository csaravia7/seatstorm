export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

/** Thrown for network-level failures (no HTTP response). Safe to retry with the same Idempotency-Key. */
export class NetworkError extends Error {}

export async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...headers },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new NetworkError('Sin conexión con el servidor.');
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) {
    const e = (data ?? {}) as { code?: string; message?: string; details?: Record<string, unknown> };
    throw new ApiError(res.status, e.code ?? 'UNKNOWN', e.message ?? 'Algo salió mal. Inténtalo de nuevo.', e.details);
  }
  return data as T;
}
