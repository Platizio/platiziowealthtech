import axios from 'axios';
import { parseServerValidation, readServerValidation, buildValidationSummary } from '../utils/serverValidation';

/** Spring Boot origin for investor-action pages (/investor-actions/*), not the Vite dev server. */
export const BACKEND_ORIGIN =
  (import.meta.env.VITE_BACKEND_ORIGIN as string | undefined)?.replace(/\/$/, '') ||
  'http://localhost:8081';

const resolveApiBaseUrl = () => {
  const configured = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.trim();
  if (configured) {
    const normalized = configured.replace(/\/$/, '');
    // Dev guard: absolute API URLs (e.g. http://localhost:8081/api/v1) are cross-origin from
    // :3000, so SameSite=Lax HttpOnly cookies are not sent and refresh appears as logout.
    if (typeof window !== 'undefined' && /^https?:\/\//i.test(normalized)) {
      try {
        const apiOrigin = new URL(normalized).origin;
        if (apiOrigin !== window.location.origin && import.meta.env.DEV) {
          console.warn(
            '[Platizio] VITE_API_BASE_URL targets',
            apiOrigin,
            'but the app runs on',
            window.location.origin,
            '— using /api/v1 proxy so auth cookies survive refresh.',
          );
          return '/api/v1';
        }
      } catch {
        // fall through to configured value
      }
    }
    return normalized;
  }
  if (typeof window !== 'undefined' && import.meta.env.DEV) {
    return '/api/v1';
  }
  return `${BACKEND_ORIGIN}/api/v1`;
};

export const API_BASE_URL = resolveApiBaseUrl();
export const SESSION_EXPIRED_EVENT = 'platizio:session-expired';

type ApiFetchInit = RequestInit & {
  skipAuthRedirect?: boolean;
};

export const apiUrl = (path: string) => {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${API_BASE_URL}${normalizedPath}`;
};

const isResolvedApiUrl = (value: string) =>
  /^https?:\/\//i.test(value) || value.startsWith(API_BASE_URL);

const isAuthRedirectExcluded = (pathOrUrl: string) => {
  const path = pathOrUrl.startsWith(API_BASE_URL)
    ? pathOrUrl.slice(API_BASE_URL.length)
    : pathOrUrl;

  // /auth/* (distributor) and /investor-auth/* (investor) are permitAll login
  // surfaces — a 401 there is a credential error, not an expired session, so it
  // must not trigger a refresh/redirect.
  return path.startsWith('/auth/') || path.startsWith('/investor-auth/');
};

const clearLocalAuthState = () => {
  window.sessionStorage.removeItem('authToken');
  window.sessionStorage.removeItem('userSession');
  window.localStorage.removeItem('authToken');
  window.localStorage.removeItem('userSession');
};

/**
 * Investor portal paths use a separate HttpOnly cookie + login flow. A 401 on an
 * investor API path (or while the browser sits on an /investor route) must bounce
 * to /investor/login — NOT the distributor session_expired flow — so the two
 * sessions never cross-redirect.
 */
const isInvestorApiPath = (path: string) =>
  path.startsWith('/investor/') ||
  path === '/investor' ||
  path.startsWith('/investor-auth/') ||
  path === '/investor-auth';

const isInvestorBrowserPath = () =>
  typeof window !== 'undefined' && window.location.pathname.startsWith('/investor');

const normalizeApiPath = (pathOrUrl: string) =>
  pathOrUrl.startsWith(API_BASE_URL) ? pathOrUrl.slice(API_BASE_URL.length) : pathOrUrl;

const redirectToLoginForExpiredSession = (pathOrUrl?: string) => {
  if (sessionRedirectInProgress || isSessionRestoreInProgress() || typeof window === 'undefined') return;

  const apiPath = pathOrUrl ? normalizeApiPath(pathOrUrl) : '';
  const investorScoped =
    (apiPath && isInvestorApiPath(apiPath)) || (!apiPath && isInvestorBrowserPath()) || isInvestorBrowserPath();

  sessionRedirectInProgress = true;
  clearLocalAuthState();
  window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));

  if (investorScoped) {
    if (window.location.pathname !== '/investor/login') {
      window.location.replace('/investor/login?reason=session_expired');
    }
    return;
  }

  if (window.location.pathname !== '/login') {
    window.location.replace('/login?reason=session_expired');
  }
};

let sessionRedirectInProgress = false;
let refreshInProgress: Promise<boolean> | null = null;
let sessionRestoreInProgress = false;

/** True while auth/restoreSession is in flight — RTK queries should not cascade refresh. */
export const isSessionRestoreInProgress = () => sessionRestoreInProgress;

export const beginSessionRestore = () => {
  sessionRestoreInProgress = true;
};

export const endSessionRestore = () => {
  sessionRestoreInProgress = false;
};

/** Single-flight refresh — avoids rotating the refresh token twice (React StrictMode / parallel 401s). */
export const refreshSession = async (): Promise<boolean> => {
  if (!refreshInProgress) {
    refreshInProgress = fetch(apiUrl('/auth/refresh'), {
      method: 'POST',
      credentials: 'include',
    })
      .then(response => response.ok)
      .catch(() => false)
      .finally(() => {
        refreshInProgress = null;
      });
  }

  return refreshInProgress;
};

export const resetSessionRedirectGuard = () => {
  sessionRedirectInProgress = false;
};

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true,
});

apiClient.interceptors.response.use(
  response => response,
  async error => {
    if (error?.response?.status === 400) {
      error.serverValidation = parseServerValidation(error.response.data);
    }
    const requestUrl = String(error?.config?.url || '');
    const requestConfig = error?.config as (typeof error.config & { _authRetry?: boolean }) | undefined;
    if (
      error?.response?.status === 401
      && typeof window !== 'undefined'
      && requestConfig
      && !requestConfig._authRetry
      && !isAuthRedirectExcluded(requestUrl)
      && !isSessionRestoreInProgress()
    ) {
      // Investor session paths have no refresh endpoint — a 401 means re-login.
      if (!isInvestorApiPath(normalizeApiPath(requestUrl))) {
        const refreshed = await refreshSession();
        if (refreshed) {
          requestConfig._authRetry = true;
          return apiClient.request(requestConfig);
        }
      }
      redirectToLoginForExpiredSession(requestUrl);
    }
    return Promise.reject(error);
  },
);

export const apiFetch = async (pathOrUrl: string, init: ApiFetchInit = {}) => {
  const { skipAuthRedirect, ...fetchInit } = init;
  const url = isResolvedApiUrl(pathOrUrl) ? pathOrUrl : apiUrl(pathOrUrl);

  const response = await fetch(url, {
    ...fetchInit,
    credentials: fetchInit.credentials ?? 'include',
  });

  if (response.status === 401 && !skipAuthRedirect && !isAuthRedirectExcluded(pathOrUrl) && typeof window !== 'undefined') {
    if (isSessionRestoreInProgress()) {
      return response;
    }
    // Investor session paths have no refresh endpoint — a 401 means re-login.
    if (!isInvestorApiPath(normalizeApiPath(pathOrUrl))) {
      const refreshed = await refreshSession();
      if (refreshed) {
        return fetch(url, {
          ...fetchInit,
          credentials: fetchInit.credentials ?? 'include',
        });
      }
    }

    redirectToLoginForExpiredSession(pathOrUrl);
  }

  return response;
};

// ─── Persona-linking: Step-1 "Send to Investor" gate (R1/R2) ──────────────────

/** Distributor Step-1 basic identity sent for investor approval. `payloadJson` is a
 *  JSON.stringify of the wizard form collected so far. */
export interface SendToInvestorRequest {
  fullName: string;
  pan: string;
  email: string;
  mobileNumber: string;
  dateOfBirth: string;
  payloadJson: string;
}

/** Backend response for a successful send-to-investor: the investor record is created
 *  with `distributor_id` NOT yet linked and an approval email is dispatched. */
export interface SendToInvestorResponse {
  status: 'PENDING_INVESTOR_APPROVAL';
  message: string;
  investorId: string;
  approvalToken: string;
}

/**
 * POST /investors/send-to-investor — creates a pending investor row keyed on PAN and
 * emails the investor an approval link. The distributor wizard stays locked at Step 1
 * until the investor approves. Throws an Error (with a human-readable message and any
 * mapped field errors attached) when the server rejects the request.
 */
export const sendToInvestor = async (
  body: SendToInvestorRequest,
): Promise<SendToInvestorResponse> => {
  const response = await apiFetch('/investors/send-to-investor', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const validation = await readServerValidation(response);
    const error = new Error(
      buildValidationSummary(validation)
        || validation.payload?.message
        || `Server error: ${response.status}`,
    ) as Error & { fieldErrors?: Record<string, string>; status?: number };
    error.fieldErrors = validation.fieldErrors;
    error.status = response.status;
    throw error;
  }

  return (await response.json()) as SendToInvestorResponse;
};
