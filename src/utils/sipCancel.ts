import { normalizeOrderStatus } from './investorAction';

const CANCELLABLE_SIP_STATUSES = new Set([
  'ACTIVE',
  'PROCESSING',
  'SUBMITTED',
  'PAYMENT_PENDING',
  'PENDING_INVESTOR_ACTION',
  'CREATED',
  'RETRY_AVAILABLE',
  'SUCCESSFUL',
]);

export const isSipCancellable = (orderStatus?: string) =>
  CANCELLABLE_SIP_STATUSES.has(normalizeOrderStatus(orderStatus));
