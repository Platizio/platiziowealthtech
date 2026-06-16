import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import {
  apiFetch,
  beginSessionRestore,
  endSessionRestore,
  refreshSession,
  resetSessionRedirectGuard,
} from '../../config/api';
import {
  type AuthUser,
  canAccessAdmin,
  normalizeAuthUser,
} from '../../types/auth';
import { platizioApi } from '../api/platizioApi';
import type { RootState } from '../index';

export type AuthStatus = 'idle' | 'loading' | 'authenticated' | 'unauthenticated';

interface AuthState {
  user: AuthUser | null;
  status: AuthStatus;
}

const isProtectedPath = (pathname?: string) => {
  if (typeof window === 'undefined') return false;
  const path = pathname ?? window.location.pathname;
  return path.startsWith('/admin') || path.startsWith('/distributor');
};

/** Captured once at bundle load — survives the brief redirect to `/` before restore runs. */
const bootProtectedPath =
  typeof window !== 'undefined' && isProtectedPath(window.location.pathname);

const initialState: AuthState = {
  user: null,
  // Show the session spinner on first paint for /distributor/* and /admin/* so the
  // App fallback route does not Navigate to `/` before cookies are read.
  status: bootProtectedPath ? 'loading' : 'idle',
};

const shouldRestoreSession = () => isProtectedPath() || bootProtectedPath;

/** Used by App.tsx to decide post-restore login redirect on hard refresh. */
export const shouldRestoreOnBoot = () => shouldRestoreSession();

/** React StrictMode runs effects twice in dev — dedupe so refresh token is not rotated twice. */
let restoreSessionInFlight: Promise<AuthUser | null> | null = null;

const loadCurrentUser = async (): Promise<AuthUser | null> => {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      let response = await apiFetch('/auth/me', { skipAuthRedirect: true });
      if (response.status === 401) {
        const refreshed = await refreshSession();
        if (refreshed) {
          response = await apiFetch('/auth/me', { skipAuthRedirect: true });
        }
      }

      if (response.status === 401 || response.status === 403) {
        return null;
      }

      if (!response.ok) {
        if (attempt === 0) {
          await new Promise((resolve) => window.setTimeout(resolve, 400));
          continue;
        }
        throw new Error('Failed to restore session');
      }

      const user = await response.json();
      resetSessionRedirectGuard();
      return normalizeAuthUser(user);
    } catch (err) {
      if (attempt === 0) {
        await new Promise((resolve) => window.setTimeout(resolve, 400));
        continue;
      }
      throw err;
    }
  }
  return null;
};

export const restoreSession = createAsyncThunk(
  'auth/restoreSession',
  async (_, { rejectWithValue }) => {
    if (!shouldRestoreSession()) {
      return null;
    }

    if (!restoreSessionInFlight) {
      beginSessionRestore();
      restoreSessionInFlight = loadCurrentUser().finally(() => {
        restoreSessionInFlight = null;
        endSessionRestore();
      });
    }

    try {
      return await restoreSessionInFlight;
    } catch {
      return rejectWithValue('Failed to restore session');
    }
  },
);

export const logout = createAsyncThunk('auth/logout', async (_, { dispatch }) => {
  try {
    await apiFetch('/auth/logout', { method: 'POST', skipAuthRedirect: true });
  } catch {
    // Local session is cleared even when the network call fails.
  }

  dispatch(platizioApi.util.resetApiState());
  clearStoredAuthTokens();
});

const clearStoredAuthTokens = () => {
  window.sessionStorage.removeItem('authToken');
  window.sessionStorage.removeItem('userSession');
  window.localStorage.removeItem('authToken');
  window.localStorage.removeItem('userSession');
};

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    setAuthenticatedUser(state, action: PayloadAction<AuthUser | null>) {
      state.user = normalizeAuthUser(action.payload);
      state.status = action.payload ? 'authenticated' : 'unauthenticated';
      if (action.payload) {
        resetSessionRedirectGuard();
      }
    },
    clearAuth(state) {
      state.user = null;
      state.status = 'unauthenticated';
      clearStoredAuthTokens();
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(restoreSession.pending, (state) => {
        if (shouldRestoreSession()) {
          state.status = 'loading';
        }
      })
      .addCase(restoreSession.fulfilled, (state, action) => {
        if (!shouldRestoreSession()) {
          state.status = 'idle';
          state.user = null;
          return;
        }
        state.user = action.payload;
        state.status = action.payload ? 'authenticated' : 'unauthenticated';
      })
      .addCase(restoreSession.rejected, (state) => {
        // Backend unreachable — keep prior user if any; do not force logout on transient errors.
        if (!state.user) {
          state.status = 'unauthenticated';
        } else {
          state.status = 'authenticated';
        }
      })
      .addCase(logout.fulfilled, (state) => {
        state.user = null;
        state.status = 'unauthenticated';
      });
  },
});

export const { setAuthenticatedUser, clearAuth } = authSlice.actions;

export const selectAuthStatus = (state: RootState) => state.auth.status;
export const selectAuthUser = (state: RootState) => state.auth.user;
export const selectIsAuthLoading = (state: RootState) => state.auth.status === 'loading';
export const selectIsAuthenticated = (state: RootState) => Boolean(state.auth.user);
export const selectCanAccessAdmin = (state: RootState) => canAccessAdmin(state.auth.user);

export default authSlice.reducer;
