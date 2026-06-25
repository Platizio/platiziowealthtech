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

// ─── Investor email-approval link (R3/R7) ─────────────────────────────────────

/** Link request lifecycle, mirrors BE `InvestorLinkRequestStatus`. */
export type InvestorLinkStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'SUPERSEDED';

/**
 * Read-only review payload for the investor email-approval link — mirrors the BE
 * record `InvestorLinkReviewResponse` (GET /investor/link/{token}). `profileDetailsJson`
 * is the exact distributor-entered Step-1 payload, passed through verbatim for the FE
 * to render; `contentSha256`/`revisionNo` bind it to the reviewed revision.
 */
export interface InvestorLinkReviewResponse {
  distributorDisplayName?: string | null;
  profileDetailsJson?: string | null;
  contentSha256?: string | null;
  revisionNo?: number | null;
  status: InvestorLinkStatus;
  /** ISO-8601 OffsetDateTime; null when the request never carried an expiry. */
  expiresAt?: string | null;
}

/** Shared shape for the three POST mutations — they all return at least `linkingStatus`. */
export interface InvestorLinkActionResponse {
  linkingStatus?: string;
  status?: string;
  [key: string]: unknown;
}

/** Thrown by the link api fns; carries the HTTP status so the page can map 403/404/410/409. */
export type InvestorLinkError = Error & { status?: number };

/**
 * Runs an investor-link request and normalizes failures into a single Error carrying the
 * HTTP status. A 401 here is handled by `apiFetch` (investor session → /investor/login),
 * so we only translate the resource-level errors (404 unknown token, 403 not-mine,
 * 409/410 expired/already-acted) into human-readable copy at the call site.
 */
const investorLinkRequest = async <T>(
  path: string,
  method: 'GET' | 'POST',
): Promise<T> => {
  const response = await apiFetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
  });
  const data = (await response.json().catch(() => null)) as
    | (T & { message?: string })
    | null;
  if (!response.ok) {
    const error = new Error(
      (data as { message?: string } | null)?.message
        || `Server error: ${response.status}`,
    ) as InvestorLinkError;
    error.status = response.status;
    throw error;
  }
  return data as T;
};

/** GET /investor/link/{token} — load the distributor-entered details for review. */
export const reviewInvestorLink = (token: string) =>
  investorLinkRequest<InvestorLinkReviewResponse>(
    `/investor/link/${encodeURIComponent(token)}`,
    'GET',
  );

/** POST /investor/link/{token}/approve — link the distributor by PAN (→ INVESTOR_APPROVED). */
export const approveInvestorLink = (token: string) =>
  investorLinkRequest<InvestorLinkActionResponse>(
    `/investor/link/${encodeURIComponent(token)}/approve`,
    'POST',
  );

/** POST /investor/link/{token}/reject — decline the link (→ REJECTED). */
export const rejectInvestorLink = (token: string) =>
  investorLinkRequest<InvestorLinkActionResponse>(
    `/investor/link/${encodeURIComponent(token)}/reject`,
    'POST',
  );

/** POST /investor/link/{token}/approve-and-skip — approve but ask the distributor to fill (→ INVESTOR_SKIPPED). */
export const approveAndSkipInvestorLink = (token: string) =>
  investorLinkRequest<InvestorLinkActionResponse>(
    `/investor/link/${encodeURIComponent(token)}/approve-and-skip`,
    'POST',
  );

// ─── Distributor skip-form fill (R10) ─────────────────────────────────────────

/** Linking lifecycle, mirrors BE `InvestorLinkingStatus`. */
export type InvestorLinkingStatus =
  | 'PENDING_INVESTOR_APPROVAL'
  | 'INVESTOR_APPROVED'
  | 'INVESTOR_FILLING'
  | 'INVESTOR_SKIPPED'
  | 'DISTRIBUTOR_FILLING'
  | 'PENDING_PROFILE_APPROVAL'
  | 'READY'
  | 'REJECTED';

/**
 * Mirrors the BE record `DistributorProfileSubmitResponse`. After a distributor fills
 * (or edits) a skipped investor's profile, the BE freezes the details into a fresh 2FA
 * `challengeId` the investor must approve, and reflects `linkingStatus` =
 * `DISTRIBUTOR_FILLING`.
 */
export interface DistributorProfileSubmitResponse {
  investorId: string;
  linkingStatus: InvestorLinkingStatus;
  challengeId: string;
}

/**
 * Posts a distributor-filled profile for an investor who approved the link but skipped the
 * form (linking_status INVESTOR_SKIPPED, or already DISTRIBUTOR_FILLING). `payloadJson` is a
 * `JSON.stringify` of the collected profile fields (dateOfBirth, address, etc.); the acting
 * distributor is resolved server-side from the JWT/cookie — never sent in the body.
 *
 * POST /api/v1/investors/{investorId}/profile/submit. A 400 means the investor is not in a
 * fillable state; a 403 means a different distributor is linked. The error carries `.status`.
 */
export const submitDistributorProfile = async (
  investorId: string,
  payloadJson: string,
): Promise<DistributorProfileSubmitResponse> => {
  const response = await apiFetch(`/investors/${encodeURIComponent(investorId)}/profile/submit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payloadJson }),
  });
  const data = (await response.json().catch(() => null)) as
    | (DistributorProfileSubmitResponse & { message?: string })
    | null;
  if (!response.ok) {
    const error = new Error(
      (data as { message?: string } | null)?.message || `Server error: ${response.status}`,
    ) as Error & { status?: number };
    error.status = response.status;
    throw error;
  }
  return data as DistributorProfileSubmitResponse;
};

/**
 * Edits the still-pending distributor-filled profile (while DISTRIBUTOR_FILLING): supersedes
 * any live challenge and freezes a fresh one over the edited details.
 * PUT /api/v1/investors/{investorId}/profile.
 */
export const updateDistributorProfile = async (
  investorId: string,
  payloadJson: string,
): Promise<DistributorProfileSubmitResponse> => {
  const response = await apiFetch(`/investors/${encodeURIComponent(investorId)}/profile`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ payloadJson }),
  });
  const data = (await response.json().catch(() => null)) as
    | (DistributorProfileSubmitResponse & { message?: string })
    | null;
  if (!response.ok) {
    const error = new Error(
      (data as { message?: string } | null)?.message || `Server error: ${response.status}`,
    ) as Error & { status?: number };
    error.status = response.status;
    throw error;
  }
  return data as DistributorProfileSubmitResponse;
};

// ─── Investor profile-change approvals (skip-form 2FA, R9) ────────────────────

/**
 * Compact view of a pending distributor-filled profile-change (2FA) challenge for the
 * investor's Approvals page — mirrors the BE record {@code ProfileChangeApprovalSummaryResponse}
 * ({@code GET /investor/profile-changes}). {@code pendingProfileJson} is the immutable frozen
 * profile the investor is approving; {@code consentRenderedText} is the exact consent copy
 * stored as evidence. The OTP code is NEVER carried here — it is delivered out-of-band.
 */
export interface ProfileChangeApprovalSummary {
  challengeId: string;
  investorId?: string;
  status: string;               // PENDING | CHALLENGE_SENT | APPROVED
  pendingProfileJson?: string;
  profileChangeSha256?: string;
  consentTemplateVersion?: string;
  consentRenderedText?: string;
  maskedDestination?: string;
  channel?: string;             // EMAIL | MOBILE
  expiresAt?: string;
  createdAt?: string;
}

/** Mirrors the BE record {@code OtpRequestResponse}. {@code devCode} is local-profile only. */
export interface ProfileChangeOtpResponse {
  message?: string;
  expiresInSeconds?: number;
  resendInSeconds?: number;
  devCode?: string;
}

/** Result of approving a profile change — the change is applied and the investor → READY. */
export interface ProfileChangeApproveResponse {
  challengeId?: string;
  linkingStatus?: string;       // READY
  message?: string;
  [key: string]: unknown;
}

/** Result of rejecting a profile change — the link returns to INVESTOR_SKIPPED for a re-fill. */
export interface ProfileChangeRejectResponse {
  challengeId?: string;
  linkingStatus?: string;       // INVESTOR_SKIPPED
  [key: string]: unknown;
}

/** Thrown by the profile-change api fns; carries the HTTP status so the page can map 403/404/409/410. */
export type ProfileChangeError = Error & { status?: number };

const profileChangeRequest = async <T>(
  path: string,
  method: 'GET' | 'POST',
  body?: unknown,
): Promise<T> => {
  const response = await apiFetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  const data = (await response.json().catch(() => null)) as (T & { message?: string }) | null;
  if (!response.ok) {
    const error = new Error(
      (data as { message?: string } | null)?.message || `Server error: ${response.status}`,
    ) as ProfileChangeError;
    error.status = response.status;
    throw error;
  }
  return data as T;
};

/**
 * GET /investor/profile-changes — lists all live (PENDING | CHALLENGE_SENT | APPROVED)
 * distributor-filled profile-change challenges for my linked investor profile.
 */
export const listProfileChanges = (): Promise<ProfileChangeApprovalSummary[]> =>
  profileChangeRequest<ProfileChangeApprovalSummary[]>('/investor/profile-changes', 'GET');

/**
 * POST /investor/profile-changes/{challengeId}/request-otp — emails the one-time passcode
 * bound to this challenge (PENDING → CHALLENGE_SENT). Never returns the live code.
 */
export const requestProfileChangeOtp = (
  challengeId: string,
): Promise<ProfileChangeOtpResponse> =>
  profileChangeRequest<ProfileChangeOtpResponse>(
    `/investor/profile-changes/${encodeURIComponent(challengeId)}/request-otp`,
    'POST',
    {},
  );

/**
 * POST /investor/profile-changes/{challengeId}/approve — verifies consent + OTP (→ APPROVED)
 * then applies the frozen change (→ READY). The BE body field is {@code code}.
 */
export const approveProfileChange = (
  challengeId: string,
  otpCode: string,
  consentAccepted: boolean,
): Promise<ProfileChangeApproveResponse> =>
  profileChangeRequest<ProfileChangeApproveResponse>(
    `/investor/profile-changes/${encodeURIComponent(challengeId)}/approve`,
    'POST',
    { consentAccepted, code: otpCode },
  );

/**
 * POST /investor/profile-changes/{challengeId}/reject — declines the change (→ REJECTED) and
 * returns the link to INVESTOR_SKIPPED so the distributor can re-fill. {@code reason} is optional.
 */
export const rejectProfileChange = (
  challengeId: string,
  reason?: string,
): Promise<ProfileChangeRejectResponse> =>
  profileChangeRequest<ProfileChangeRejectResponse>(
    `/investor/profile-changes/${encodeURIComponent(challengeId)}/reject`,
    'POST',
    { reason: reason ?? null },
  );
