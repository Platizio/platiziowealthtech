import {
  fetchBaseQuery,
  type BaseQueryFn,
  type FetchArgs,
  type FetchBaseQueryError,
} from '@reduxjs/toolkit/query/react';
import {
  API_BASE_URL,
  isSessionRestoreInProgress,
  refreshSession,
  SESSION_EXPIRED_EVENT,
} from '../../config/api';

const rawBaseQuery = fetchBaseQuery({
  baseUrl: API_BASE_URL,
  credentials: 'include',
  prepareHeaders: (headers) => {
    if (!headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    return headers;
  },
});

const isAuthPath = (url: string) => url.includes('/auth/');

export const baseQueryWithReauth: BaseQueryFn<
  string | FetchArgs,
  unknown,
  FetchBaseQueryError
> = async (args, api, extraOptions) => {
  let result = await rawBaseQuery(args, api, extraOptions);

  if (result.error?.status === 401) {
    const requestUrl = typeof args === 'string' ? args : args.url;
    if (!isAuthPath(requestUrl) && !isSessionRestoreInProgress()) {
      const refreshed = await refreshSession();
      if (refreshed) {
        result = await rawBaseQuery(args, api, extraOptions);
      } else if (typeof window !== 'undefined' && !window.location.pathname.startsWith('/login')) {
        window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
      }
    }
  }

  return result;
};
