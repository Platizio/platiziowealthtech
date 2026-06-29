import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit';
import {
  apiFetch,
  beginSessionRestore,
  endSessionRestore,
  resetSessionRedirectGuard,
} from '../../config/api';
import {
  type InvestorUser,
  normalizeInvestorUser,
} from '../../types/investorAuth';
import { platizioApi } from '../api/platizioApi';
import type { RootState } from '../index';

export type InvestorAuthStatus = 'idle' | 'loading' | 'authenticated' | 'unauthenticated';

interface InvestorAuthState {
  user: InvestorUser | null;
  status: InvestorAuthStatus;
}

/** Guard the investor area only — distributor /admin and /distributor paths are untouched. */
const isInvestorPath = (pathname?: string) => {
  if (typeof window === 'undefined') return false;
  const path = pathname ?? window.location.pathname;
  return path.startsWith('/investor');
};

/** Public investor surfaces that must NOT trigger a session restore. */
const isInvestorPublicPath = (pathname?: string) => {
  if (typeof window === 'undefined') return false;
  const path = pathname ?? window.location.pathname;
  return path === '/investor/login' || path === '/investor/signup' || path === '/investor/approve';
};

/** Captured once at bundle load — survives the brief redirect before restore runs. */
const bootInvestorPath =
  typeof window !== 'undefined' &&
  isInvestorPath(window.location.pathname) &&
  !isInvestorPublicPath(window.location.pathname);

const initialState: InvestorAuthState = {
  user: null,
  // Show the session spinner on first paint for protected /investor/* paths so the
  // App fallback does not bounce to /investor/login before the cookie is read.
  status: bootInvestorPath ? 'loading' : 'idle',
};

const shouldRestoreSession = () =>
  (isInvestorPath() && !isInvestorPublicPath()) || bootInvestorPath;

/** Used by App.tsx to decide post-restore login redirect on hard refresh. */
export const shouldRestoreInvestorOnBoot = () => shouldRestoreSession();

/** React StrictMode runs effects twice in dev — dedupe so /investor/me isn't called twice. */
let restoreInvestorInFlight: Promise<InvestorUser | null> | null = null;

const loadCurrentInvestor = async (): Promise<InvestorUser | null> => {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const response = await apiFetch('/investor/me', { skipAuthRedirect: true });

      if (response.status === 401 || response.status === 403) {
        return null;
      }

      if (!response.ok) {
        if (attempt === 0) {
          await new Promise((resolve) => window.setTimeout(resolve, 400));
          continue;
        }
        throw new Error('Failed to restore investor session');
      }

      const user = await response.json();
      resetSessionRedirectGuard();
      return normalizeInvestorUser(user);
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

export const restoreInvestorSession = createAsyncThunk(
  'investorAuth/restoreSession',
  async (_, { rejectWithValue }) => {
    if (!shouldRestoreSession()) {
      return null;
    }

    if (!restoreInvestorInFlight) {
      beginSessionRestore();
      restoreInvestorInFlight = loadCurrentInvestor().finally(() => {
        restoreInvestorInFlight = null;
        endSessionRestore();
      });
    }

    try {
      return await restoreInvestorInFlight;
    } catch {
      return rejectWithValue('Failed to restore investor session');
    }
  },
);

export const investorLogout = createAsyncThunk('investorAuth/logout', async (_, { dispatch }) => {
  try {
    await apiFetch('/investor-auth/logout', { method: 'POST', skipAuthRedirect: true });
  } catch {
    // Local session is cleared even when the network call fails.
  }
  dispatch(platizioApi.util.resetApiState());
});

const investorAuthSlice = createSlice({
  name: 'investorAuth',
  initialState,
  reducers: {
    setInvestorUser(state, action: PayloadAction<InvestorUser | null>) {
      state.user = normalizeInvestorUser(action.payload);
      state.status = state.user ? 'authenticated' : 'unauthenticated';
      if (state.user) {
        resetSessionRedirectGuard();
      }
    },
    clearInvestorAuth(state) {
      state.user = null;
      state.status = 'unauthenticated';
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(restoreInvestorSession.pending, (state) => {
        if (shouldRestoreSession()) {
          state.status = 'loading';
        }
      })
      .addCase(restoreInvestorSession.fulfilled, (state, action) => {
        if (!shouldRestoreSession()) {
          state.status = 'idle';
          state.user = null;
          return;
        }
        state.user = normalizeInvestorUser(action.payload);
        state.status = state.user ? 'authenticated' : 'unauthenticated';
      })
      .addCase(restoreInvestorSession.rejected, (state) => {
        // Backend unreachable — keep prior user if any; don't force logout on transient errors.
        state.status = state.user ? 'authenticated' : 'unauthenticated';
      })
      .addCase(investorLogout.fulfilled, (state) => {
        state.user = null;
        state.status = 'unauthenticated';
      });
  },
});

export const { setInvestorUser, clearInvestorAuth } = investorAuthSlice.actions;

export const selectInvestorAuthStatus = (state: RootState) => state.investorAuth.status;
export const selectInvestorUser = (state: RootState) => state.investorAuth.user;
export const selectIsInvestorAuthLoading = (state: RootState) =>
  state.investorAuth.status === 'loading';
export const selectIsInvestorAuthenticated = (state: RootState) =>
  Boolean(state.investorAuth.user);

export default investorAuthSlice.reducer;
