export interface ApiError {
  code: string;
  message: string;
  details: Record<string, unknown>;
}

export const apiError = (code: string, message: string, details: Record<string, unknown> = {}): ApiError => ({
  code,
  message,
  details,
});

export const NO_SESSION = apiError('NO_SESSION', 'Necesitas un apodo para continuar.');
export const BAD_ADMIN = apiError('BAD_ADMIN_TOKEN', 'Acceso de presentador requerido.');
export const UPSTREAM_DOWN = apiError('UPSTREAM_UNAVAILABLE', 'El servicio no responde. Inténtalo de nuevo en unos segundos.');
