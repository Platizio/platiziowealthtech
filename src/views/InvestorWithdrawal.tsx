import { useCallback, useEffect, useState } from 'react';
import {
  Wallet, Loader2, AlertCircle, AlertTriangle, ShieldCheck, Send,
  CheckCircle2, RefreshCw, Info,
} from 'lucide-react';
import EmptyState from '../components/EmptyState';
import TransactionApprovalPanel, {
  type ApprovalChallenge,
} from '../components/TransactionApprovalPanel';
import { apiFetch } from '../config/api';

// TODO(user): supply final self-withdrawal compliance warning copy
const WITHDRAWAL_COMPLIANCE_WARNING = '[Compliance to provide the self-withdrawal warning text.]';

/**
 * Per-folio holding disclosure (FR-RED). The backend surfaces a
 * {@code dataQuality} of OK | STALE | UNAVAILABLE; we render exactly what it
 * gives and NEVER fabricate a number when data is stale/unavailable
 * (FR-HLD-005) — no fallback to order amount.
 */
interface Holding {
  folio?: string;
  folioNumber?: string;
  schemeName?: string;
  scheme?: string;
  productSchemeId?: string;
  orderId?: string;
  availableUnits?: number | null;
  blockedUnits?: number | null;
  latestNav?: number | null;
  navAsOf?: string | null;
  currentValue?: number | null;
  payoutBankMasked?: string | null;
  dataQuality?: string;   // OK | STALE | UNAVAILABLE
}

type RedeemMode = 'amount' | 'units' | '';

interface WithdrawalForm {
  mode: RedeemMode;
  value: string;
  fullRedemption: boolean;
}

const emptyForm: WithdrawalForm = { mode: '', value: '', fullRedemption: false };

const normalizeQuality = (q?: string) => String(q || 'OK').trim().toUpperCase();

const formatCurrency = (value?: number | null) =>
  typeof value === 'number' && Number.isFinite(value)
    ? `₹${value.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`
    : null;

const formatUnits = (value?: number | null) =>
  typeof value === 'number' && Number.isFinite(value)
    ? value.toLocaleString('en-IN', { maximumFractionDigits: 4 })
    : null;

const formatDate = (value?: string | null) => {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
};

const folioKey = (h: Holding) => h.folio || h.folioNumber || h.orderId || h.productSchemeId || '';
const folioLabel = (h: Holding) => h.folio || h.folioNumber || '—';
const schemeLabel = (h: Holding) => h.schemeName || h.scheme || 'Scheme name unavailable';

/** Disclosure row — shows the value, or the data-quality caveat, but never a made-up number. */
function Disclosure({
  label,
  value,
  quality,
}: {
  label: string;
  value: string | null;
  quality: string;
}) {
  const unavailable = value == null;
  return (
    <div>
      <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">{label}</p>
      {unavailable ? (
        <p className="mt-0.5 text-sm font-medium text-slate-400">
          {quality === 'UNAVAILABLE' ? 'Unavailable' : quality === 'STALE' ? 'Not current' : '—'}
        </p>
      ) : (
        <p className="mt-0.5 text-sm font-medium text-slate-700">{value}</p>
      )}
    </div>
  );
}

/**
 * F: investor self-service withdrawal. Loads {@code GET /investor/holdings};
 * per folio shows FR-RED disclosures (folio, scheme, available/blocked units,
 * latest NAV + as-of, current value, masked payout bank), honouring
 * {@code dataQuality=STALE/UNAVAILABLE} (never a fabricated number). NO folio /
 * scheme is preselected; "Redeem by amount" vs "by units" radios are BOTH
 * unselected; full redemption is explicit. Two CTAs:
 *   - "Request to distributor" → POST /investor/withdrawals/request-to-distributor (no 2FA, draft)
 *   - "Self-withdraw" → POST /investor/withdrawals/self → {challengeId} → drives
 *     {@link TransactionApprovalPanel} for the 2FA, then a real redemption submit.
 * A prominent compliance warning (placeholder constant) renders ABOVE the
 * self-withdraw CTA.
 */
export default function InvestorWithdrawal() {
  const [holdings, setHoldings] = useState<Holding[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  // No folio preselected.
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [form, setForm] = useState<WithdrawalForm>(emptyForm);

  const [submitting, setSubmitting] = useState<'distributor' | 'self' | null>(null);
  const [actionError, setActionError] = useState('');
  const [requestedToDistributor, setRequestedToDistributor] = useState(false);

  // When self-withdraw returns a challengeId, route the user into the 2FA panel.
  const [challenge, setChallenge] = useState<ApprovalChallenge | null>(null);

  const loadHoldings = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const res = await apiFetch('/investor/holdings');
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Unable to load your holdings.');
      }
      const list: Holding[] = Array.isArray(data)
        ? data
        : Array.isArray((data as { content?: Holding[]; holdings?: Holding[] } | null)?.content)
          ? (data as { content: Holding[] }).content
          : Array.isArray((data as { holdings?: Holding[] } | null)?.holdings)
            ? (data as { holdings: Holding[] }).holdings
            : [];
      setHoldings(list);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Unable to load your holdings.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadHoldings();
  }, [loadHoldings]);

  const selectFolio = (key: string) => {
    setSelectedKey((prev) => (prev === key ? null : key));
    setForm(emptyForm);
    setActionError('');
    setRequestedToDistributor(false);
  };

  const selected = holdings.find((h) => folioKey(h) === selectedKey) || null;

  const buildPayload = () => {
    if (!selected) return null;
    const base: Record<string, unknown> = {};
    if (selected.orderId) base.orderId = selected.orderId;
    if (selected.folio || selected.folioNumber) base.folio = selected.folio || selected.folioNumber;
    if (form.fullRedemption) {
      base.mode = 'FULL';
    } else {
      base.mode = form.mode === 'amount' ? 'AMOUNT' : 'UNITS';
      base.value = Number(form.value);
    }
    return base;
  };

  const validate = (): string | null => {
    if (!selected) return 'Select a folio to withdraw from.';
    if (!form.fullRedemption) {
      if (!form.mode) return 'Choose to redeem by amount or by units.';
      const n = Number(form.value);
      if (!Number.isFinite(n) || n <= 0) return 'Enter a valid amount or number of units to redeem.';
    }
    return null;
  };

  const requestToDistributor = async () => {
    const err = validate();
    if (err) { setActionError(err); return; }
    setSubmitting('distributor');
    setActionError('');
    try {
      const res = await apiFetch('/investor/withdrawals/request-to-distributor', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildPayload()),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Could not send your request. Please try again.');
      }
      setRequestedToDistributor(true);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not send your request. Please try again.');
    } finally {
      setSubmitting(null);
    }
  };

  const selfWithdraw = async () => {
    const err = validate();
    if (err) { setActionError(err); return; }
    setSubmitting('self');
    setActionError('');
    try {
      const res = await apiFetch('/investor/withdrawals/self', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildPayload()),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data as { message?: string } | null)?.message || 'Could not start the self-withdrawal. Please try again.');
      }
      const challengeId =
        (data as { challengeId?: string; id?: string } | null)?.challengeId ||
        (data as { id?: string } | null)?.id;
      if (!challengeId) {
        throw new Error('The withdrawal could not be authorized. Please try again.');
      }
      // Hand off to the 2FA panel using the returned challenge (or a stub the
      // panel will refine via the approve/otp responses).
      setChallenge({
        id: challengeId,
        transactionType: 'REDEMPTION',
        status: 'PENDING',
        ...(data && typeof data === 'object' ? (data as Partial<ApprovalChallenge>) : {}),
      });
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Could not start the self-withdrawal. Please try again.');
    } finally {
      setSubmitting(null);
    }
  };

  // ── 2FA panel (after self-withdraw) ─────────────────────────────────────────
  if (challenge) {
    return (
      <div className="mx-auto max-w-2xl p-8">
        <div className="mb-6">
          <h1 className="text-2xl font-semibold text-slate-800">Authorize your withdrawal</h1>
          <p className="mt-1 text-sm text-slate-500">
            Approve this redemption with a one-time passcode to submit it.
          </p>
        </div>
        <TransactionApprovalPanel
          challenge={challenge}
          onBack={() => { setChallenge(null); void loadHoldings(); }}
          onChallengeUpdated={(c) => setChallenge(c)}
          onApproved={() => void loadHoldings()}
        />
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <div className="text-center">
          <Loader2 className="mx-auto h-8 w-8 animate-spin text-blue-600" />
          <p className="mt-3 text-sm font-medium text-slate-600">Loading your holdings…</p>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl p-8">
      <div className="mb-6 flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-800">Withdraw funds</h1>
          <p className="mt-1 text-sm text-slate-500">
            Redeem your mutual-fund holdings. Choose a folio, then request your distributor to process it or self-withdraw with a one-time passcode.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void loadHoldings()}
          className="inline-flex flex-shrink-0 items-center gap-2 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50"
        >
          <RefreshCw className="h-3.5 w-3.5" /> Refresh
        </button>
      </div>

      {loadError && (
        <div className="mb-6 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
          <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
          <div>
            <p className="text-sm text-red-700">{loadError}</p>
            <button onClick={() => void loadHoldings()} className="mt-2 text-xs font-semibold text-red-600 hover:underline">
              Try again
            </button>
          </div>
        </div>
      )}

      {holdings.length === 0 && !loadError ? (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <EmptyState
            icon={Wallet}
            title="No holdings to withdraw"
            subtitle="You don't have any mutual-fund holdings available to redeem yet. Completed purchases will appear here."
          />
        </div>
      ) : (
        <div className="space-y-4">
          {holdings.map((h) => {
            const key = folioKey(h);
            const quality = normalizeQuality(h.dataQuality);
            const isSelected = selectedKey === key;
            const stale = quality === 'STALE' || quality === 'UNAVAILABLE';
            return (
              <div
                key={key || schemeLabel(h)}
                className={`rounded-2xl border bg-white transition-colors ${isSelected ? 'border-[#0B1B3E] ring-1 ring-[#0B1B3E]/10' : 'border-slate-200'}`}
              >
                <button
                  type="button"
                  onClick={() => selectFolio(key)}
                  className="flex w-full items-start gap-3 p-5 text-left"
                >
                  <input
                    type="radio"
                    name="folio"
                    checked={isSelected}
                    onChange={() => selectFolio(key)}
                    className="mt-1 h-4 w-4 border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                  />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-slate-800">{schemeLabel(h)}</p>
                    <p className="mt-0.5 text-xs text-slate-400">Folio: {folioLabel(h)}</p>
                  </div>
                </button>

                <div className="border-t border-slate-100 px-5 py-4">
                  {stale && (
                    <div className="mb-3 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2">
                      <Info className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-amber-600" />
                      <p className="text-[11px] leading-relaxed text-amber-800">
                        {quality === 'UNAVAILABLE'
                          ? 'Some holding data is currently unavailable. Figures shown as "Unavailable" cannot be confirmed right now.'
                          : 'Some holding data may not be current. Figures shown as "Not current" are awaiting the latest sync.'}
                      </p>
                    </div>
                  )}
                  <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
                    <Disclosure label="Available units" value={formatUnits(h.availableUnits)} quality={quality} />
                    <Disclosure label="Blocked units" value={formatUnits(h.blockedUnits)} quality={quality} />
                    <Disclosure label="Latest NAV" value={formatCurrency(h.latestNav)} quality={quality} />
                    <Disclosure label="NAV as of" value={formatDate(h.navAsOf)} quality={quality} />
                    <Disclosure label="Current value" value={formatCurrency(h.currentValue)} quality={quality} />
                    <Disclosure label="Payout bank" value={h.payoutBankMasked || null} quality={quality} />
                  </div>
                </div>

                {isSelected && (
                  <div className="border-t border-slate-100 bg-slate-50/60 px-5 py-5">
                    {/* Redeem mode — both radios start UNSELECTED */}
                    <p className="mb-2 text-xs font-bold uppercase tracking-wider text-slate-500">How much to redeem</p>
                    <div className="flex flex-wrap gap-4">
                      <label className="flex cursor-pointer items-center gap-2">
                        <input
                          type="radio"
                          name="redeem-mode"
                          checked={form.mode === 'amount' && !form.fullRedemption}
                          onChange={() => setForm({ mode: 'amount', value: '', fullRedemption: false })}
                          className="h-4 w-4 border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                        />
                        <span className="text-sm text-slate-700">Redeem by amount</span>
                      </label>
                      <label className="flex cursor-pointer items-center gap-2">
                        <input
                          type="radio"
                          name="redeem-mode"
                          checked={form.mode === 'units' && !form.fullRedemption}
                          onChange={() => setForm({ mode: 'units', value: '', fullRedemption: false })}
                          className="h-4 w-4 border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                        />
                        <span className="text-sm text-slate-700">Redeem by units</span>
                      </label>
                    </div>

                    {!form.fullRedemption && form.mode && (
                      <div className="mt-3">
                        <input
                          type="number"
                          min="0"
                          inputMode="decimal"
                          value={form.value}
                          onChange={(e) => setForm((f) => ({ ...f, value: e.target.value }))}
                          placeholder={form.mode === 'amount' ? 'Amount in ₹' : 'Number of units'}
                          className="w-full rounded-xl border border-slate-200 bg-white px-4 py-2.5 text-sm outline-none transition-all focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                        />
                      </div>
                    )}

                    {/* Explicit full redemption */}
                    <label className="mt-4 flex cursor-pointer items-start gap-2.5 select-none">
                      <input
                        type="checkbox"
                        checked={form.fullRedemption}
                        onChange={(e) =>
                          setForm((f) => ({
                            mode: e.target.checked ? '' : f.mode,
                            value: '',
                            fullRedemption: e.target.checked,
                          }))
                        }
                        className="mt-0.5 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                      />
                      <span className="text-sm text-slate-700">Redeem the full holding (full redemption)</span>
                    </label>

                    {actionError && (
                      <div className="mt-4 flex items-start gap-2.5 rounded-xl border border-red-100 bg-red-50 px-4 py-3">
                        <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
                        <p className="text-sm text-red-700">{actionError}</p>
                      </div>
                    )}

                    {requestedToDistributor && (
                      <div className="mt-4 flex items-start gap-2.5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3">
                        <CheckCircle2 className="mt-0.5 h-4 w-4 flex-shrink-0 text-emerald-600" />
                        <p className="text-sm text-emerald-700">
                          Your withdrawal request has been sent to your distributor. They will process the redemption on your behalf.
                        </p>
                      </div>
                    )}

                    {/* CTA 1: request to distributor (no 2FA) */}
                    <button
                      type="button"
                      onClick={() => void requestToDistributor()}
                      disabled={submitting !== null}
                      className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl border border-slate-200 bg-white px-6 py-3 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {submitting === 'distributor' ? (<><Loader2 className="h-4 w-4 animate-spin" /> Sending…</>) : (<><Send className="h-4 w-4" /> Request to distributor</>)}
                    </button>

                    {/* Prominent compliance warning ABOVE the self-withdraw CTA */}
                    <div className="mt-6 flex items-start gap-3 rounded-xl border-2 border-amber-300 bg-amber-50 px-4 py-3.5">
                      <AlertTriangle className="mt-0.5 h-5 w-5 flex-shrink-0 text-amber-600" />
                      <div>
                        <p className="text-xs font-bold uppercase tracking-wide text-amber-900">Before you self-withdraw</p>
                        <p className="mt-1 text-sm leading-relaxed text-amber-900">{WITHDRAWAL_COMPLIANCE_WARNING}</p>
                      </div>
                    </div>

                    {/* CTA 2: self-withdraw (2FA) */}
                    <button
                      type="button"
                      onClick={() => void selfWithdraw()}
                      disabled={submitting !== null}
                      className="mt-3 flex w-full items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] px-6 py-3 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066] disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {submitting === 'self' ? (<><Loader2 className="h-4 w-4 animate-spin" /> Starting…</>) : (<><ShieldCheck className="h-4 w-4" /> Self-withdraw</>)}
                    </button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
