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

/** Shared, friendly mapping of a failed redemption HTTP response to a message. */
const mapRedemptionError = (status: number, body: any): RedeemResult => {
  if (status === 502 || status === 503) {
    return {
      ok: false,
      message:
        'Cybrilla is temporarily unavailable. Your request was not submitted — please try again in a few minutes.',
    };
  }
  const message = body?.message || `Redemption failed (HTTP ${status}).`;
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
};

/**
 * Distributor-initiated redemption with 2FA (shared by the Redemptions page, Investor
 * redeem, Portfolio rows and Transaction detail). Two steps, no money moves until the
 * investor approves:
 *   1. POST /orders/{orderId}/redemption           → creates the redemption DRAFT
 *   2. POST /orders/redemptions/{draftId}/request-approval → creates the REDEMPTION 2FA
 *      challenge the investor authorizes (OTP) in their portal; that approval submits it.
 * Never throws — always resolves to { ok, message }.
 */
export const submitRedemption = async (orderId: string): Promise<RedeemResult> => {
  try {
    const draftRes = await apiFetch(`/orders/${orderId}/redemption`, { method: 'POST' });
    const draft = await draftRes.json().catch(() => null);
    if (!draftRes.ok) {
      return mapRedemptionError(draftRes.status, draft);
    }
    const redemptionId = (draft as { id?: string } | null)?.id;
    if (!redemptionId) {
      return { ok: false, message: 'Could not create the redemption draft. Please try again.' };
    }
    const apprRes = await apiFetch(`/orders/redemptions/${redemptionId}/request-approval`, { method: 'POST' });
    const appr = await apprRes.json().catch(() => null);
    if (!apprRes.ok) {
      return mapRedemptionError(apprRes.status, appr);
    }
    const dest = (appr as { maskedDestination?: string } | null)?.maskedDestination;
    return {
      ok: true,
      message: `Redemption approval requested. The investor authorizes it with a one-time passcode in their portal${dest ? ` (a code can be sent to ${dest})` : ''}.`,
    };
  } catch (err) {
    return { ok: false, message: err instanceof Error ? err.message : 'Redemption failed. Please try again.' };
  }
};

export type RedemptionRecord = {
  id: string;
  redemptionStatus?: string;
  externalRedemptionId?: string;
  bankCreditReference?: string;
  failureReason?: string;
  amount?: number;
  units?: number;
};

/** GET /orders/{id}/redemptions — redemption records (with FP-tracked status) for a holding. */
export const fetchRedemptions = async (orderId: string): Promise<RedemptionRecord[]> => {
  try {
    const res = await apiFetch(`/orders/${orderId}/redemptions`);
    if (!res.ok) return [];
    const body = await res.json().catch(() => []);
    return Array.isArray(body) ? body : [];
  } catch {
    return [];
  }
};

/** POST /orders/{id}/redemptions/sync — reconcile redemption records against Fintech Primitives. */
export const syncRedemptions = async (orderId: string): Promise<RedemptionRecord[]> => {
  try {
    const res = await apiFetch(`/orders/${orderId}/redemptions/sync`, { method: 'POST' });
    if (!res.ok) return [];
    const body = await res.json().catch(() => []);
    return Array.isArray(body) ? body : [];
  } catch {
    return [];
  }
};

// Most-advanced status wins when a holding has more than one redemption record.
const STATUS_PRECEDENCE = [
  'FAILED', 'BANK_CREDIT_COMPLETED', 'SUCCESSFUL', 'BANK_CREDIT_PENDING',
  'PROCESSING', 'SUBMITTED', 'PENDING_INVESTOR_ACTION', 'CREATED',
];

export const latestRedemptionStatus = (records: RedemptionRecord[]): string | undefined => {
  if (!records || records.length === 0) return undefined;
  return records
    .map(r => String(r.redemptionStatus || '').toUpperCase())
    .filter(Boolean)
    .sort((a, b) => STATUS_PRECEDENCE.indexOf(a) - STATUS_PRECEDENCE.indexOf(b))[0];
};

/** Display label + Tailwind classes for a redemption status badge. */
export const redemptionStatusMeta = (status?: string): { label: string; cls: string } => {
  switch (String(status || '').toUpperCase()) {
    case 'SUCCESSFUL':
    case 'BANK_CREDIT_COMPLETED':
      return { label: 'Redeemed', cls: 'bg-green-50 text-green-700 border border-green-200' };
    case 'BANK_CREDIT_PENDING':
      return { label: 'Bank credit pending', cls: 'bg-emerald-50 text-emerald-700 border border-emerald-200' };
    case 'PROCESSING':
      return { label: 'Processing', cls: 'bg-blue-50 text-blue-700 border border-blue-200' };
    case 'SUBMITTED':
      return { label: 'Submitted', cls: 'bg-indigo-50 text-indigo-700 border border-indigo-200' };
    case 'CREATED':
    case 'PENDING_INVESTOR_ACTION':
      return { label: 'Created', cls: 'bg-slate-50 text-slate-600 border border-slate-200' };
    case 'FAILED':
      return { label: 'Failed', cls: 'bg-red-50 text-red-700 border border-red-200' };
    default:
      return { label: status ? String(status) : '—', cls: 'bg-slate-50 text-slate-600 border border-slate-200' };
  }
};
