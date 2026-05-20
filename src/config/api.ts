import axios from 'axios';
import { parseServerValidation } from '../utils/serverValidation';

const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api/v1';

export const API_BASE_URL = configuredBaseUrl.replace(/\/$/, '');
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

  return ['/auth/login', '/auth/logout', '/auth/signup', '/auth/me'].some(authPath => path.startsWith(authPath));
};

const clearLocalAuthState = () => {
  window.sessionStorage.removeItem('authToken');
  window.sessionStorage.removeItem('userSession');
  window.localStorage.removeItem('authToken');
  window.localStorage.removeItem('userSession');
};

let sessionRedirectInProgress = false;
let refreshInProgress: Promise<boolean> | null = null;

const refreshSession = async () => {
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

axios.interceptors.response.use(
  response => response,
  error => {
    if (error?.response?.status === 400) {
      error.serverValidation = parseServerValidation(error.response.data);
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
    const refreshed = await refreshSession();
    if (refreshed) {
      return fetch(url, {
        ...fetchInit,
        credentials: fetchInit.credentials ?? 'include',
      });
    }

    if (!sessionRedirectInProgress) {
      sessionRedirectInProgress = true;
      clearLocalAuthState();
      window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));

      if (window.location.pathname !== '/login') {
        window.location.replace('/login?reason=session_expired');
      }
    }
  }

  return response;
};
