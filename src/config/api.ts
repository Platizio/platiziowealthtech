const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api/v1';

export const API_BASE_URL = configuredBaseUrl.replace(/\/$/, '');

export const apiUrl = (path: string) => {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${API_BASE_URL}${normalizedPath}`;
};

const isResolvedApiUrl = (value: string) =>
  /^https?:\/\//i.test(value) || value.startsWith(API_BASE_URL);

export const apiFetch = (pathOrUrl: string, init: RequestInit = {}) => {
  const url = isResolvedApiUrl(pathOrUrl) ? pathOrUrl : apiUrl(pathOrUrl);

  return fetch(url, {
    ...init,
    credentials: init.credentials ?? 'include',
  });
};
