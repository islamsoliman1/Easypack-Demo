import { getLanguage, originalData, type Language } from './i18n';
export type User = {
  id: string; email: string; displayName: string; active: boolean;
  mustChangePassword: boolean; version: number; roleIds: string[];
  overrides: Record<string, boolean>; permissions: string[];
};
export type Role = { id: string; name: string; description: string; version: number; permissions: string[] };
export type Session = { accessToken: string; user: User };
export class ApiError extends Error {
  constructor(public status: number, public code: string) { super(code); }
}
let access: string | null = null;
let csrf: string | null = null;
let refreshTask: Promise<Session> | null = null;
let csrfTask: Promise<void> | null = null;
export function forgetSession() { access = null; }
async function ensureCsrf(fresh = false) {
  if (csrf && !fresh) return;
  if (!csrfTask) csrfTask = fetch('/api/v1/auth/csrf', { credentials: 'same-origin', headers: { 'Accept-Language': getLanguage() } })
    .then(async r => { if (!r.ok) throw new ApiError(r.status, 'NETWORK'); csrf = (await r.json()).token; })
    .finally(() => { csrfTask = null; });
  await csrfTask;
}
async function raw<T>(path: string, method = 'GET', body?: unknown, binary = false, language: Language = getLanguage()): Promise<T> {
  // Authentication may clear the CSRF cookie; cookie-authenticated requests need its current token.
  if (method !== 'GET') await ensureCsrf(['/auth/login', '/auth/refresh', '/auth/logout', '/auth/demo'].includes(path));
  const multipart = body instanceof FormData;
  const headers: Record<string, string> = multipart ? {} : { 'Content-Type': 'application/json' };
  headers['Accept-Language'] = language;
  if (access) headers.Authorization = `Bearer ${access}`;
  if (method !== 'GET' && csrf) headers['X-XSRF-TOKEN'] = csrf;
  const response = await fetch(`/api/v1${path}`, {
    method, headers, credentials: 'same-origin', body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(response.status, problem.code ?? 'NETWORK');
  }
  const result = response.status === 204 ? undefined as T : binary ? await response.blob() as T : originalData(await response.json()) as T;
  if (method !== 'GET' && !path.startsWith('/auth/') && !path.startsWith('/notifications/')) window.dispatchEvent(new Event('workflow-saved'));
  return result;
}
export function refreshSession(): Promise<Session> {
  if (!refreshTask) {
    // Refresh authentication uses the cookie, not a possibly expired bearer token.
    access = null;
    const rotate = async () => {
      try { return await raw<Session>('/auth/refresh', 'POST'); }
      catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) throw error;
        return raw<Session>('/auth/demo', 'POST');
      }
    };
    const pending = (navigator.locks ? navigator.locks.request('easyback-refresh', rotate) : rotate()) as Promise<Session>;
    refreshTask = pending.then(session => {
      access = session.accessToken; return session;
    }).finally(() => { refreshTask = null; });
  }
  return refreshTask;
}
export async function login(email: string, password: string) {
  access = null;
  const session = await raw<Session>('/auth/login', 'POST', { email, password });
  access = session.accessToken; return session;
}
export async function api<T>(path: string, method = 'GET', body?: unknown, binary = false): Promise<T> {
  const language = getLanguage();
  try { return await raw<T>(path, method, body, binary, language); }
  catch (e) {
    if (!(e instanceof ApiError) || e.status !== 401) throw e;
    try { await refreshSession(); }
    catch {
      forgetSession(); window.dispatchEvent(new Event('session-ended')); throw e;
    }
    return raw<T>(path, method, body, binary, language);
  }
}
export async function logout() {
  access = null;
  try { await raw('/auth/logout', 'POST'); }
  finally { forgetSession(); }
}
