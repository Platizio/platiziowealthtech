import { apiFetch } from '../config/api';

export type RedeemResult = { ok: boolean; message: string };

// Order statuses that represent a settled holding the investor actually owns and
// can therefore redeem (sell). Draft / failed / pending orders are not redeemable.
const REDEEMABLE_STATUSES = new Set(['SUCCESSFUL', 'COMPLETED']);
// SIPs are managed (paused / cancelled) from the SIP Dashboard, so only one-time
// purchase holdings are redeemable here.
const REDEEMABLE_TYPES = new Set(['PURCHASE', 'LUMPSUM_PURCHASE']);

/** A holding is redeemable when it is a completed one-time purchase (not a SIP). */
export const isRedeemableHolding = (orderStatus?: string, transactionType?: string): boolean =>
  REDEEMABLE_STATUSES.has(String(orderStatus || '').toUpperCase()) &&
  REDEEMABLE_TYPES.has(String(transactionType || '').toUpperCase());

/**
 * POST /api/v1/orders/{orderId}/redemption with the normalized error messaging shared by every
 * redemption surface (Redemptions page, Investor redeem, Portfolio rows, Transaction detail).
 * Never throws — always resolves to { ok, message }.
 */
export const submitRedemption = async (orderId: string): Promise<RedeemResult> => {
  try {
    const response = await apiFetch(`/orders/${orderId}/redemption`, { method: 'POST' });
    if (response.ok) {
      return { ok: true, message: 'Redemption submitted. Track its progress under Transactions.' };
    }
    const body = await response.json().catch(() => null);
    if (response.status === 502 || response.status === 503) {
      return {
        ok: false,
        message:
          'Cybrilla is temporarily unavailable. Your request was not submitted — please try again in a few minutes.',
      };
    }
    const message = body?.message || `Redemption failed (HTTP ${response.status}).`;
    if (/mf investment account|investor profile|occupation/i.test(message)) {
      return {
        ok: false,
        message: `${message} Redemption uses the same investor FP profile setup as purchases — restart the backend if you recently deployed a fix, then retry.`,
      };
    }
    if (/fintech primitives purchase id|externalorderid|demo-only/i.test(message)) {
      return {
        ok: false,
        message:
          'This holding was not purchased through live Cybrilla POA (demo-only order). Place a real purchase first, then redeem that order.',
      };
    }
    return { ok: false, message };
  } catch (err) {
    return { ok: false, message: err instanceof Error ? err.message : 'Redemption failed. Please try again.' };
  }
};
