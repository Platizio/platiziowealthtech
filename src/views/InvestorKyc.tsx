import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  ShieldCheck, ShieldAlert, ShieldQuestion, Loader2, AlertCircle,
  BadgeCheck, FileCheck2, ExternalLink, RefreshCw, IdCard, Gauge,
  Clock, Ban, CheckCircle2, HelpCircle, ArrowRight, PenLine, Fingerprint,
  PartyPopper,
} from 'lucide-react';
import { apiFetch } from '../config/api';

/**
 * Investor-only KYC journey — the heart of the demo.
 *
 * COMPLIANCE: this page is investor-session only. The investor alone runs
 * pre-verification, PAN verification, KYC submission, DigiLocker (Aadhaar) and
 * eSign. It NEVER calls a distributor /api/v1/investors/** endpoint.
 *
 * It drives the server-side KYC state machine end-to-end through a single
 * "Continue KYC" CTA that POSTs /investor/kyc/advance and re-reads status:
 *   pre-verify → create KYC request → Aadhaar/DigiLocker → eSign → complete.
 *
 * Endpoints (base /api/v1/investor/kyc):
 *   GET  /status                       → KycFlowStatusResponse (nextAction enum)
 *   POST /readiness                    → KycReadinessDecision (action / canInvest / actionable)
 *   POST /advance                      → KycFlowAdvanceResponse {status, stepResult, message}
 *   POST /pan-verify                   → Cybrilla KRA compliance check on my PAN
 *   POST /identity-documents           → start DigiLocker (Aadhaar) → redirect URL
 *   POST /identity-documents/refresh   → poll DigiLocker after the callback
 *   POST /esign/start                  → start eSign → redirect URL  (NEW)
 *   POST /esign/refresh                → poll eSign after the callback (NEW)
 *
 * SANDBOX: kyc_forms returns 403 ("Partner not allowed"). SUBMIT_NEW_KYC and
 * MODIFY_KYC therefore BOTH route through /advance (the DigiLocker chain) — only
 * the CTA label differs. No kyc_forms endpoint is ever called.
 */

// ── server NextAction enum (KycFlowStatusResponse.nextAction) ────────────────
type NextAction =
  | 'RUN_PRE_VERIFICATION' | 'CREATE_KYC_REQUEST'
  | 'START_AADHAAR' | 'REFRESH_AADHAAR'
  | 'START_ESIGN' | 'REFRESH_ESIGN'
  | 'WAIT' | 'COMPLETE';

// ── Defensive shapes (aligned to the backend DTOs, alias-tolerant) ───────────
interface KycInvestor {
  pan?: string | null;
  fullName?: string | null;
  kycStatus?: string | null;
}
interface KycStatusPayload {
  investor?: KycInvestor;
  stage?: string | null;
  nextAction?: NextAction | string | null;
  message?: string | null;
  aadhaarRedirectUrl?: string | null;
  esignRedirectUrl?: string | null;
  fieldsNeeded?: string[] | null;
}
interface KycDecision {
  state?: string | null;
  status?: string | null;
  alreadyKycCompliant?: boolean;
  freshKycRequired?: boolean;
  message?: string | null;
}
interface PanVerifyPayload {
  investor?: KycInvestor;
  kyc?: KycDecision;
}
interface DigiLockerPayload {
  identityDocumentId?: string | null;
  fetchStatus?: string | null;
  fetchReason?: string | null;
  redirectUrl?: string | null;
  fetchComplete?: boolean;
}
interface EsignPayload {
  esignId?: string | null;
  status?: string | null;
  redirectUrl?: string | null;
  completed?: boolean;
}
// POST /advance → KycFlowAdvanceResponse { status, stepResult, message }
interface KycAdvancePayload {
  status?: KycStatusPayload | null;
  stepResult?: unknown;
  message?: string | null;
}

// POST /investor/kyc/readiness → KycReadinessDecision (flat).
type KycReadinessAction =
  | 'PROCEED' | 'SUBMIT_NEW_KYC' | 'MODIFY_KYC'
  | 'WAIT' | 'BLOCKED' | 'RETRY' | 'MANUAL_REVIEW';
interface KycReadinessPayload {
  status?: string | null;
  code?: string | null;
  action?: KycReadinessAction | string | null;
  message?: string | null;
  preVerificationId?: string | null;
  canInvest?: boolean;
  actionable?: boolean;
}

const READINESS_META: Record<string, { label: string; bg: string; text: string; ring: string; Icon: typeof Gauge }> = {
  PROCEED: { label: 'Ready to invest', bg: 'bg-emerald-50', text: 'text-emerald-700', ring: 'ring-emerald-200', Icon: CheckCircle2 },
  SUBMIT_NEW_KYC: { label: 'New KYC required', bg: 'bg-amber-50', text: 'text-amber-700', ring: 'ring-amber-200', Icon: FileCheck2 },
  MODIFY_KYC: { label: 'Update KYC required', bg: 'bg-amber-50', text: 'text-amber-700', ring: 'ring-amber-200', Icon: FileCheck2 },
  WAIT: { label: 'KYC in process', bg: 'bg-blue-50', text: 'text-blue-700', ring: 'ring-blue-200', Icon: Clock },
  BLOCKED: { label: 'KYC blocked', bg: 'bg-red-50', text: 'text-red-700', ring: 'ring-red-200', Icon: Ban },
  RETRY: { label: 'Retry needed', bg: 'bg-amber-50', text: 'text-amber-700', ring: 'ring-amber-200', Icon: RefreshCw },
  MANUAL_REVIEW: { label: 'Manual review', bg: 'bg-slate-50', text: 'text-slate-600', ring: 'ring-slate-200', Icon: HelpCircle },
};
const readinessMeta = (action?: string | null) =>
  READINESS_META[String(action || 'MANUAL_REVIEW').toUpperCase()] ?? READINESS_META.MANUAL_REVIEW;

const maskPan = (pan?: string | null): string => {
  if (!pan || pan.length < 4) return pan || '—';
  return `${pan.slice(0, 2)}${'•'.repeat(Math.max(0, pan.length - 4))}${pan.slice(-2)}`;
};

const STATUS_STYLES: Record<string, { bg: string; text: string; ring: string }> = {
  COMPLETED: { bg: 'bg-emerald-50', text: 'text-emerald-700', ring: 'ring-emerald-200' },
  IN_PROGRESS: { bg: 'bg-blue-50', text: 'text-blue-700', ring: 'ring-blue-200' },
  PENDING: { bg: 'bg-amber-50', text: 'text-amber-700', ring: 'ring-amber-200' },
  RETRY_REQUIRED: { bg: 'bg-amber-50', text: 'text-amber-700', ring: 'ring-amber-200' },
  FAILED: { bg: 'bg-red-50', text: 'text-red-700', ring: 'ring-red-200' },
  NOT_STARTED: { bg: 'bg-slate-50', text: 'text-slate-600', ring: 'ring-slate-200' },
};
const statusStyle = (s?: string | null) =>
  STATUS_STYLES[String(s || 'NOT_STARTED').toUpperCase()] ?? STATUS_STYLES.NOT_STARTED;
const prettyStatus = (s?: string | null) =>
  String(s || 'NOT_STARTED').replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, (c) => c.toUpperCase());

// ── next-action presentation ─────────────────────────────────────────────────
// All "drive the flow" actions resolve to the same /advance call; the label and
// helper copy differ so the investor sees a meaningful step.
const ADVANCEABLE = new Set<NextAction>([
  'RUN_PRE_VERIFICATION', 'CREATE_KYC_REQUEST',
  'START_AADHAAR', 'REFRESH_AADHAAR', 'START_ESIGN', 'REFRESH_ESIGN',
]);
const ADVANCE_LABEL: Record<string, string> = {
  RUN_PRE_VERIFICATION: 'Continue KYC',
  CREATE_KYC_REQUEST: 'Continue KYC',
  START_AADHAAR: 'Continue to DigiLocker',
  REFRESH_AADHAAR: 'Refresh DigiLocker status',
  START_ESIGN: 'Continue to eSign',
  REFRESH_ESIGN: 'Refresh eSign status',
};
const isAadhaarAction = (a?: string | null) => a === 'START_AADHAAR' || a === 'REFRESH_AADHAAR';
const isEsignAction = (a?: string | null) => a === 'START_ESIGN' || a === 'REFRESH_ESIGN';

async function postJson<T>(path: string): Promise<T> {
  const res = await apiFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    skipAuthRedirect: true,
  });
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    throw new Error((body as { message?: string } | null)?.message || `Request failed (${res.status}).`);
  }
  return body as T;
}

/** Pull a redirect URL out of an /advance stepResult (Aadhaar or eSign step). */
function redirectUrlFromStepResult(stepResult: unknown): string | null {
  if (!stepResult || typeof stepResult !== 'object') return null;
  const r = stepResult as Record<string, unknown>;
  const direct = r.redirectUrl ?? r.aadhaarRedirectUrl ?? r.esignRedirectUrl;
  if (typeof direct === 'string' && direct) return direct;
  return null;
}

export default function InvestorKyc() {
  const [status, setStatus] = useState<KycStatusPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [verifying, setVerifying] = useState(false);
  const [panResult, setPanResult] = useState<KycDecision | null>(null);
  const [actionError, setActionError] = useState('');

  const [advancing, setAdvancing] = useState(false);

  const [digiLocker, setDigiLocker] = useState<DigiLockerPayload | null>(null);
  const [digiBusy, setDigiBusy] = useState(false);

  const [esign, setEsign] = useState<EsignPayload | null>(null);
  const [esignBusy, setEsignBusy] = useState(false);

  const [readiness, setReadiness] = useState<KycReadinessPayload | null>(null);
  const [readinessBusy, setReadinessBusy] = useState(false);
  const [readinessError, setReadinessError] = useState('');

  const loadStatus = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor/kyc/status');
      const body = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((body as { message?: string } | null)?.message || `Unable to load your KYC status (${res.status}).`);
      }
      setStatus((body as KycStatusPayload) || {});
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unable to load your KYC status.');
      setStatus(null);
    } finally {
      setLoading(false);
    }
  }, []);

  // Run the readiness check and route on the returned action (best-effort).
  const evaluateReadiness = useCallback(async () => {
    setReadinessBusy(true);
    setReadinessError('');
    try {
      const payload = await postJson<KycReadinessPayload>('/investor/kyc/readiness');
      setReadiness(payload ?? {});
    } catch (e) {
      setReadinessError(e instanceof Error ? e.message : 'Could not evaluate your KYC readiness.');
    } finally {
      setReadinessBusy(false);
    }
  }, []);

  useEffect(() => {
    void loadStatus();
  }, [loadStatus]);

  // Evaluate readiness once the initial status has loaded (best-effort; non-fatal).
  useEffect(() => {
    if (!loading && !error) {
      void evaluateReadiness();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, error]);

  // ── primary driver: advance the server state machine one step ──────────────
  // pre-verify → create KYC request → Aadhaar/DigiLocker → eSign. The response
  // carries the fresh status inline; we apply it immediately and open any
  // DigiLocker/eSign redirect URL the step returned, then re-read readiness.
  const advanceKyc = useCallback(async () => {
    setAdvancing(true);
    setActionError('');
    try {
      const payload = await postJson<KycAdvancePayload>('/investor/kyc/advance');
      if (payload?.status) {
        setStatus(payload.status);
      } else {
        await loadStatus();
      }
      const url =
        redirectUrlFromStepResult(payload?.stepResult) ||
        payload?.status?.aadhaarRedirectUrl ||
        payload?.status?.esignRedirectUrl ||
        null;
      if (url) {
        window.open(url, '_blank', 'noopener,noreferrer');
      }
      await evaluateReadiness();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not advance your KYC.');
    } finally {
      setAdvancing(false);
    }
  }, [evaluateReadiness, loadStatus]);

  const verifyPan = async () => {
    setVerifying(true);
    setActionError('');
    setPanResult(null);
    try {
      const payload = await postJson<PanVerifyPayload>('/investor/kyc/pan-verify');
      setPanResult(payload.kyc ?? {});
      await loadStatus();
      await evaluateReadiness();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'PAN verification failed.');
    } finally {
      setVerifying(false);
    }
  };

  const startDigiLocker = async () => {
    setDigiBusy(true);
    setActionError('');
    try {
      const payload = await postJson<DigiLockerPayload>('/investor/kyc/identity-documents');
      setDigiLocker(payload);
      if (payload.redirectUrl) {
        window.open(payload.redirectUrl, '_blank', 'noopener,noreferrer');
      }
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not start DigiLocker verification.');
    } finally {
      setDigiBusy(false);
    }
  };

  const refreshDigiLocker = async () => {
    setDigiBusy(true);
    setActionError('');
    try {
      const payload = await postJson<DigiLockerPayload>('/investor/kyc/identity-documents/refresh');
      setDigiLocker(payload);
      await loadStatus();
      await evaluateReadiness();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not refresh DigiLocker status.');
    } finally {
      setDigiBusy(false);
    }
  };

  const startEsign = async () => {
    setEsignBusy(true);
    setActionError('');
    try {
      const payload = await postJson<EsignPayload>('/investor/kyc/esign/start');
      setEsign(payload);
      if (payload.redirectUrl) {
        window.open(payload.redirectUrl, '_blank', 'noopener,noreferrer');
      }
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not start eSign.');
    } finally {
      setEsignBusy(false);
    }
  };

  const refreshEsign = async () => {
    setEsignBusy(true);
    setActionError('');
    try {
      const payload = await postJson<EsignPayload>('/investor/kyc/esign/refresh');
      setEsign(payload);
      await loadStatus();
      await evaluateReadiness();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not refresh eSign status.');
    } finally {
      setEsignBusy(false);
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading your KYC status…</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="rounded-2xl border border-red-200 bg-red-50 p-6 text-red-700">
          <div className="flex items-start gap-2.5">
            <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
            <div>
              <p className="font-semibold">KYC status unavailable</p>
              <p className="mt-1 text-sm">{error}</p>
              <button onClick={() => void loadStatus()} className="mt-4 text-sm font-semibold text-red-600 hover:underline">
                Try again
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const investor = status?.investor ?? {};
  const kycStatus = investor.kycStatus;
  const style = statusStyle(kycStatus);
  const nextAction = String(status?.nextAction || '').toUpperCase() as NextAction;
  const readinessAction = String(readiness?.action || '').toUpperCase();

  // Terminal SUCCESS: server says COMPLETE, or readiness clears the investor to invest.
  const isComplete =
    nextAction === 'COMPLETE' ||
    panResult?.alreadyKycCompliant === true ||
    String(kycStatus).toUpperCase() === 'COMPLETED';
  const canInvest = isComplete || readiness?.canInvest === true || readinessAction === 'PROCEED';

  // Which step the single CTA / step-card should surface.
  const canAdvance = ADVANCEABLE.has(nextAction);
  const showAadhaarCard = isAadhaarAction(nextAction) || !!digiLocker;
  const showEsignCard = isEsignAction(nextAction) || !!esign;
  const isWaiting = nextAction === 'WAIT';

  // ── Terminal success state ─────────────────────────────────────────────────
  if (canInvest) {
    return (
      <div className="mx-auto max-w-2xl space-y-6 p-8">
        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-semibold text-slate-800">KYC verification</h1>
            <p className="mt-1 text-sm text-slate-500">You're verified and ready to transact.</p>
          </div>
          <button
            onClick={() => void loadStatus()}
            className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
          >
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </button>
        </div>

        <div className="rounded-2xl border border-emerald-100 bg-white p-8 text-center shadow-sm">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-600">
            <PartyPopper className="h-7 w-7" />
          </div>
          <h2 className="mt-4 text-lg font-semibold text-slate-800">KYC complete</h2>
          <p className="mt-1.5 text-sm text-slate-600">
            {readiness?.message || status?.message || 'Your KYC is verified. You can start investing now.'}
          </p>
          <div className="mt-3 flex items-center justify-center">
            <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold text-emerald-700 ring-1 ring-emerald-200">
              <BadgeCheck className="h-3.5 w-3.5" /> KYC compliant
            </span>
          </div>
          <Link
            to="/investor/invest"
            className="mt-6 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066]"
          >
            Start investing <ArrowRight className="h-4 w-4" />
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-8">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">KYC verification</h1>
          <p className="mt-1 text-sm text-slate-500">Verify your PAN and complete KYC to transact.</p>
        </div>
        <button
          onClick={() => void loadStatus()}
          className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" /> Refresh
        </button>
      </div>

      {/* Current status card */}
      <div className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm">
        <div className="flex items-center gap-3">
          <div className={`flex h-11 w-11 items-center justify-center rounded-xl ${style.bg} ${style.text}`}>
            {kycStatus ? <ShieldAlert className="h-6 w-6" /> : <ShieldQuestion className="h-6 w-6" />}
          </div>
          <div>
            <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">KYC status</p>
            <span className={`mt-0.5 inline-flex items-center rounded-full px-2.5 py-0.5 text-sm font-semibold ring-1 ${style.bg} ${style.text} ${style.ring}`}>
              {prettyStatus(kycStatus)}
            </span>
          </div>
        </div>
        {status?.message && <p className="mt-4 text-sm text-slate-600">{status.message}</p>}
      </div>

      {/* KYC readiness: route on the backend-derived action. */}
      <div className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            <Gauge className="h-4 w-4 text-slate-500" />
            <h2 className="text-sm font-semibold text-slate-700">KYC readiness</h2>
          </div>
          <button
            onClick={() => void evaluateReadiness()}
            disabled={readinessBusy}
            className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {readinessBusy
              ? (<><Loader2 className="h-3.5 w-3.5 animate-spin" /> Checking…</>)
              : (<><RefreshCw className="h-3.5 w-3.5" /> Re-check</>)}
          </button>
        </div>

        {readinessError && (
          <div className="flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
            <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
            <p className="text-sm text-red-700">{readinessError}</p>
          </div>
        )}

        {!readinessError && readinessBusy && !readiness && (
          <div className="flex items-center gap-2 text-sm text-slate-500">
            <Loader2 className="h-4 w-4 animate-spin" /> Evaluating your KYC readiness…
          </div>
        )}

        {!readinessError && readiness && (() => {
          const action = readinessAction || 'MANUAL_REVIEW';
          const meta = readinessMeta(action);
          const Icon = meta.Icon;
          // SUBMIT_NEW_KYC / MODIFY_KYC are the actionable readiness states; both route
          // through /advance (DigiLocker chain — never kyc_forms). Only the label differs.
          // RETRY/WAIT/BLOCKED/MANUAL_REVIEW are not actionable here — use the "Re-check" button.
          const startsKyc = action === 'SUBMIT_NEW_KYC' || action === 'MODIFY_KYC';
          const actionable = readiness.actionable === true && startsKyc;
          const ctaLabel = action === 'MODIFY_KYC'
            ? 'Update KYC'
            : action === 'SUBMIT_NEW_KYC'
              ? 'Start KYC'
              : 'Continue KYC';
          return (
            <div>
              <div className="flex items-start gap-3">
                <div className={`flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl ${meta.bg} ${meta.text}`}>
                  <Icon className="h-6 w-6" />
                </div>
                <div className="min-w-0">
                  <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-sm font-semibold ring-1 ${meta.bg} ${meta.text} ${meta.ring}`}>
                    {meta.label}
                  </span>
                  {readiness.message && <p className="mt-2 text-sm text-slate-600">{readiness.message}</p>}
                  {readiness.code && (
                    <p className="mt-1 text-xs text-slate-400">
                      Readiness: {readiness.status || '—'}{readiness.code ? ` (${readiness.code})` : ''}
                    </p>
                  )}
                </div>
              </div>

              {/* Actionable readiness → drive the state machine through /advance. */}
              {actionable && (
                <button
                  onClick={() => void advanceKyc()}
                  disabled={advancing}
                  className="mt-4 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60"
                >
                  {advancing
                    ? (<><Loader2 className="h-4 w-4 animate-spin" /> Working…</>)
                    : (<>{ctaLabel} <ArrowRight className="h-4 w-4" /></>)}
                </button>
              )}
              {/* WAIT / BLOCKED / MANUAL_REVIEW → message only (no CTA). */}
            </div>
          );
        })()}
      </div>

      {actionError && (
        <div className="flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
          <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
          <p className="text-sm text-red-700">{actionError}</p>
        </div>
      )}

      {/* PRIMARY DRIVER — one "Continue KYC" CTA that advances the state machine.
          Suppressed when a dedicated provider card (DigiLocker/eSign) owns the current
          step, so the investor never sees two buttons that start the same provider step. */}
      {canAdvance && !showAadhaarCard && !showEsignCard && (
        <div className="rounded-2xl border border-blue-100 bg-blue-50/40 p-6 shadow-sm">
          <div className="mb-3 flex items-center gap-2">
            <ShieldCheck className="h-4 w-4 text-blue-600" />
            <h2 className="text-sm font-semibold text-slate-700">Next step</h2>
          </div>
          <p className="mb-4 text-sm text-slate-600">
            {status?.message || 'Continue your KYC. We will run the next step automatically.'}
          </p>
          <button
            onClick={() => void advanceKyc()}
            disabled={advancing}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60"
          >
            {advancing
              ? (<><Loader2 className="h-4 w-4 animate-spin" /> Working…</>)
              : (<>{ADVANCE_LABEL[nextAction] || 'Continue KYC'} <ArrowRight className="h-4 w-4" /></>)}
          </button>
        </div>
      )}

      {/* WAIT — provider is processing; surface message, no CTA. */}
      {isWaiting && (
        <div className="rounded-2xl border border-blue-100 bg-white p-6 shadow-sm">
          <div className="flex items-start gap-3">
            <div className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-700">
              <Clock className="h-6 w-6" />
            </div>
            <div>
              <p className="font-semibold text-slate-800">KYC in process</p>
              <p className="mt-1 text-sm text-slate-600">
                {status?.message || 'Your KYC is submitted and is being finalized. Check back shortly.'}
              </p>
              {status?.fieldsNeeded && status.fieldsNeeded.length > 0 && (
                <p className="mt-2 text-xs text-slate-400">Additional details requested: {status.fieldsNeeded.join(', ')}</p>
              )}
            </div>
          </div>
        </div>
      )}

      {/* A2 — PAN verification */}
      <div className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm">
        <div className="mb-4 flex items-center gap-2">
          <IdCard className="h-4 w-4 text-slate-500" />
          <h2 className="text-sm font-semibold text-slate-700">PAN verification</h2>
        </div>
        <div className="mb-5 flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3">
          <span className="text-xs font-bold uppercase tracking-wider text-slate-400">Your PAN</span>
          <span className="font-mono text-sm font-semibold text-slate-700">{maskPan(investor.pan)}</span>
        </div>

        {panResult && (
          <div
            className={`mb-5 flex items-start gap-2.5 rounded-xl border px-4 py-3 ${
              panResult.alreadyKycCompliant
                ? 'border-emerald-100 bg-emerald-50'
                : panResult.freshKycRequired
                  ? 'border-amber-100 bg-amber-50'
                  : 'border-slate-100 bg-slate-50'
            }`}
          >
            {panResult.alreadyKycCompliant ? (
              <BadgeCheck className="mt-0.5 h-4 w-4 flex-shrink-0 text-emerald-600" />
            ) : (
              <FileCheck2 className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600" />
            )}
            <div className="text-sm">
              <p className="font-semibold text-slate-800">
                {panResult.alreadyKycCompliant
                  ? 'Your PAN is already KYC compliant'
                  : panResult.freshKycRequired
                    ? 'Fresh KYC required'
                    : 'PAN verification result'}
              </p>
              {panResult.message && <p className="mt-0.5 text-slate-600">{panResult.message}</p>}
              {panResult.state && <p className="mt-1 text-xs text-slate-400">State: {panResult.state}</p>}
            </div>
          </div>
        )}

        <button
          onClick={() => void verifyPan()}
          disabled={verifying || !investor.pan}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60"
        >
          {verifying ? (<><Loader2 className="h-4 w-4 animate-spin" /> Verifying PAN…</>) : (<><ShieldCheck className="h-4 w-4" /> Verify PAN</>)}
        </button>
      </div>

      {/* A3 — DigiLocker (Aadhaar). Surfaced when nextAction is START/REFRESH_AADHAAR. */}
      {showAadhaarCard && (
        <div className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm">
          <div className="mb-4 flex items-center gap-2">
            <Fingerprint className="h-4 w-4 text-slate-500" />
            <h2 className="text-sm font-semibold text-slate-700">DigiLocker identity verification</h2>
          </div>
          <p className="mb-5 text-sm text-slate-600">
            Complete your KYC by fetching your Aadhaar through DigiLocker. You'll be redirected to DigiLocker in a new
            tab; once done, return here and refresh.
          </p>

          {digiLocker?.fetchStatus && (
            <div className="mb-5 flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3 text-sm">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-400">Fetch status</span>
              <span className={`font-semibold ${digiLocker.fetchComplete ? 'text-emerald-700' : 'text-slate-700'}`}>
                {prettyStatus(digiLocker.fetchStatus)}
              </span>
            </div>
          )}

          <div className="flex flex-col gap-3 sm:flex-row">
            <button
              onClick={() => void startDigiLocker()}
              disabled={digiBusy}
              className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60"
            >
              {digiBusy ? (<><Loader2 className="h-4 w-4 animate-spin" /> Working…</>) : (<><ExternalLink className="h-4 w-4" /> Start DigiLocker</>)}
            </button>
            {digiLocker && (
              <button
                onClick={() => void refreshDigiLocker()}
                disabled={digiBusy}
                className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-slate-200 py-3 text-sm font-semibold text-slate-700 transition-all hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <RefreshCw className="h-4 w-4" /> I've completed it — refresh
              </button>
            )}
          </div>
        </div>
      )}

      {/* A3 — eSign. Surfaced when nextAction is START/REFRESH_ESIGN. */}
      {showEsignCard && (
        <div className="rounded-2xl border border-slate-100 bg-white p-6 shadow-sm">
          <div className="mb-4 flex items-center gap-2">
            <PenLine className="h-4 w-4 text-slate-500" />
            <h2 className="text-sm font-semibold text-slate-700">eSign your KYC</h2>
          </div>
          <p className="mb-5 text-sm text-slate-600">
            Digitally sign your KYC application via Aadhaar eSign. You'll be redirected to the eSign provider in a new
            tab; once done, return here and refresh.
          </p>

          {esign?.status && (
            <div className="mb-5 flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3 text-sm">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-400">eSign status</span>
              <span className={`font-semibold ${esign.completed ? 'text-emerald-700' : 'text-slate-700'}`}>
                {prettyStatus(esign.status)}
              </span>
            </div>
          )}

          <div className="flex flex-col gap-3 sm:flex-row">
            <button
              onClick={() => void startEsign()}
              disabled={esignBusy}
              className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white transition-all hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-60"
            >
              {esignBusy ? (<><Loader2 className="h-4 w-4 animate-spin" /> Working…</>) : (<><ExternalLink className="h-4 w-4" /> Start eSign</>)}
            </button>
            {esign && (
              <button
                onClick={() => void refreshEsign()}
                disabled={esignBusy}
                className="flex flex-1 items-center justify-center gap-2 rounded-xl border border-slate-200 py-3 text-sm font-semibold text-slate-700 transition-all hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60"
              >
                <RefreshCw className="h-4 w-4" /> I've completed it — refresh
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
