import type { OrderStatus } from '../types/order';
import { BACKEND_ORIGIN } from '../config/api';

export const normalizeOrderStatus = (status?: string) =>
  String(status || '')
    .trim()
    .toUpperCase()
    .replace(/[\s-]+/g, '_');

export const formatOrderStatusLabel = (status?: string) => {
  const normalized = normalizeOrderStatus(status);
  const labels: Record<string, string> = {
    PENDING_INVESTOR_ACTION: 'Pending Investor Action',
    PAYMENT_PENDING: 'Payment Pending',
    RETRY_AVAILABLE: 'Retry Available',
    SUCCESSFUL: 'Successful',
    PROCESSING: 'Processing',
    SUBMITTED: 'Submitted',
    FAILED: 'Failed',
    CANCELLED: 'Cancelled',
    ACTIVE: 'Active',
    COMPLETED: 'Completed',
    DRAFT: 'Draft',
    CREATED: 'Created',
  };
  return labels[normalized] || status || 'Processing';
};

export const isAwaitingInvestorAction = (status?: string) =>
  normalizeOrderStatus(status) === 'PENDING_INVESTOR_ACTION';

export const isPaymentPending = (status?: string) =>
  normalizeOrderStatus(status) === 'PAYMENT_PENDING';

export const isPaymentRedirectUrl = (url?: string) =>
  Boolean(url && /^https?:\/\//i.test(url.trim()));

/**
 * Builds a browser-openable URL for investor confirm/payment pages served by Spring Boot.
 * Relative paths like `/investor-actions/{token}` are prefixed with the backend origin.
 */
export const buildInvestorActionUrl = (investorActionUrl?: string | null): string | null => {
  const raw = String(investorActionUrl || '').trim();
  if (!raw) return null;
  if (/^https?:\/\//i.test(raw)) return raw;
  const path = raw.startsWith('/') ? raw : `/${raw}`;
  return `${BACKEND_ORIGIN}${path}`;
};

export const orderNeedsInvestorLink = (orderStatus?: OrderStatus | string, investorActionUrl?: string) => {
  const status = normalizeOrderStatus(orderStatus);
  if (isPaymentRedirectUrl(investorActionUrl)) return true;
  return status === 'PENDING_INVESTOR_ACTION' || status === 'PAYMENT_PENDING' || status === 'RETRY_AVAILABLE';
};
