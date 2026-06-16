import { createApi } from '@reduxjs/toolkit/query/react';
import { getPageContent } from '../../utils/pagination';
import { baseQueryWithReauth } from './baseQuery';

export interface DashboardAction {
  id: string;
  category: string;
  priority: string;
  investor: string;
  desc: string;
  age: string;
}

export interface OnboardingCardItem {
  id: string;
  name: string;
  detail: string;
  days: string;
  status: string;
}

export interface OnboardingPipeline {
  kycPending: OnboardingCardItem[];
  bankPending: OnboardingCardItem[];
  readyToInvest: OnboardingCardItem[];
}

export const platizioApi = createApi({
  reducerPath: 'platizioApi',
  baseQuery: baseQueryWithReauth,
  tagTypes: ['User', 'Investor', 'Order', 'Scheme', 'Lead', 'Notification', 'Dashboard'],
  endpoints: (build) => ({
    getCurrentUser: build.query<Record<string, unknown>, void>({
      query: () => '/auth/me',
      providesTags: ['User'],
    }),
    getOrdersByDistributor: build.query<unknown[], string>({
      query: (distributorId) => `/orders/by-distributor/${distributorId}`,
      providesTags: (_result, _error, distributorId) => [
        { type: 'Order', id: `distributor-${distributorId}` },
      ],
    }),
    getInvestorsByDistributor: build.query<unknown[], string>({
      query: (distributorId) => `/investors/by-distributor/${distributorId}`,
      providesTags: (_result, _error, distributorId) => [
        { type: 'Investor', id: `distributor-${distributorId}` },
      ],
    }),
    getSchemes: build.query<unknown[], void>({
      query: () => '/products/schemes',
      transformResponse: (response: unknown) => getPageContent(response),
      providesTags: ['Scheme'],
    }),
    getLeadsByDistributor: build.query<unknown[], string>({
      query: (distributorId) => `/leads/distributor/${distributorId}`,
      providesTags: (_result, _error, distributorId) => [
        { type: 'Lead', id: distributorId },
      ],
    }),
    getDashboardOnboarding: build.query<OnboardingPipeline, string>({
      query: (distributorId) => `/dashboard/distributor/${distributorId}/onboarding`,
      providesTags: (_result, _error, distributorId) => [
        { type: 'Dashboard', id: `onboarding-${distributorId}` },
      ],
    }),
    getDashboardActions: build.query<DashboardAction[], string>({
      query: (distributorId) => `/dashboard/distributor/${distributorId}/actions`,
      providesTags: (_result, _error, distributorId) => [
        { type: 'Dashboard', id: `actions-${distributorId}` },
      ],
    }),
    getNotificationsByDistributor: build.query<unknown[], string>({
      query: (distributorId) => `/notifications/distributor/${distributorId}`,
      providesTags: (_result, _error, distributorId) => [
        { type: 'Notification', id: distributorId },
      ],
    }),
  }),
});

export const {
  useGetCurrentUserQuery,
  useGetOrdersByDistributorQuery,
  useGetInvestorsByDistributorQuery,
  useGetSchemesQuery,
  useGetLeadsByDistributorQuery,
  useGetDashboardOnboardingQuery,
  useGetDashboardActionsQuery,
  useGetNotificationsByDistributorQuery,
} = platizioApi;
