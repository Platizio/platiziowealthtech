import { configureStore } from '@reduxjs/toolkit';
import { platizioApi } from './api/platizioApi';
import authReducer from './slices/authSlice';
import investorAuthReducer from './slices/investorAuthSlice';

export const store = configureStore({
  reducer: {
    auth: authReducer,
    investorAuth: investorAuthReducer,
    [platizioApi.reducerPath]: platizioApi.reducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(platizioApi.middleware),
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
