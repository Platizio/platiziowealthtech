import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { motion } from 'motion/react';
import { ArrowLeft, TrendingDown, AlertCircle, CheckCircle2, Layers, RefreshCw, Info, X } from 'lucide-react';
import { apiFetch } from '../config/api';
import { fetchRedemptions, syncRedemptions, latestRedemptionStatus, redemptionStatusMeta, submitRedemption } from '../utils/redeemOrder';
import { prettySipFrequency } from '../utils/sipDisplay';

/**
 * Distributor-facing redemption screen (REQ #9).
 *
 * Loads the SAME redeemable-holdings source the investor withdrawal flow uses —
 * the backend {@code HoldingResponse} from
 * {@code GET /dashboard/distributor/{distributorId}/investors/{investorId}/holdings}
 * (PortfolioService#getInvestorHoldingsForDistributor). Each row shows the
 * Folio number, Scheme name and Available (NET redeemable) units, and redemption
 * is gated on availableUnits > 0 so a fully-redeemed / blocked holding cannot be
 * redeemed again. Valuation honours the backend dataQuality contract (never a
 * fabricated number).
 */

interface Holding {
  orderId: string;
  folio?: string | null;
  schemeName?: string | null;
  amcName?: string | null;
  availableUnits?: number | null;
  blockedUnits?: number | null;
  latestNav?: number | null;
  navAsOf?: string | null;
  currentValue?: number | null;
  dataQuality?: string; // OK | STALE | UNAVAILABLE
  redeemable?: boolean;
  // Present when the source order was a SIP (optional — older BE rows omit them).
  sipName?: string | null;
  sipNumber?: string | null;
  sipFrequency?: string | null;
  // Client-side redemption tracking (per source order).
  alreadyRedeemed: boolean;
  redemptionStatus?: string;
}

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
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
};

export default function InvestorRedeem({ userData: _userData }: { userData?: any }) {
  const { investorId = '' } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const investorFromState = (location.state as any)?.investor;

  const [investorName, setInvestorName] = useState<string>(investorFromState?.fullName || '');
  const [holdings, setHoldings] = useState<Holding[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [redeemingId, setRedeemingId] = useState('');
  const [syncingId, setSyncingId] = useState('');
  const [actionError, setActionError] = useState('');
  const [actionMessage, setActionMessage] = useState('');
  // Holding awaiting explicit confirmation before the redemption request is submitted.
  const [confirmHolding, setConfirmHolding] = useState<Holding | null>(null);

  const loadHoldings = async () => {
    if (!investorId) return;
    setLoading(true);
    setLoadError('');
    try {
      // The holding rows carry the distributor scope via the investor; resolve the
      // distributor id from navigation state, falling back to the investor record.
      let distributorId: string | undefined =
        investorFromState?.distributorId || investorFromState?.raw?.distributorId;
      if (!investorName || !distributorId) {
        const investorRes = await apiFetch(`/investors/${investorId}`);
        if (investorRes.ok) {
          const investor = await investorRes.json().catch(() => null);
          if (investor) {
            if (!investorName && investor.fullName) setInvestorName(investor.fullName);
            distributorId = distributorId || investor.distributorId;
          }
        }
      }
      if (!distributorId) {
        throw new Error('Could not resolve this investor’s distributor to load holdings.');
      }

      const holdingsRes = await apiFetch(
        `/dashboard/distributor/${distributorId}/investors/${investorId}/holdings`,
      );
      if (!holdingsRes.ok) {
        throw new Error(`Could not load this investor's holdings (HTTP ${holdingsRes.status}).`);
      }
      const payload = await holdingsRes.json().catch(() => []);
      const rows: any[] = Array.isArray(payload)
        ? payload
        : Array.isArray(payload?.content)
          ? payload.content
          : [];

      // Mark holdings that already have a redemption in flight/done so we don't
      // submit a duplicate redemption against the same order.
      const withRedemptionFlags = await Promise.all(
        rows.map(async (h: any) => {
          let alreadyRedeemed = false;
          let redemptionStatus: string | undefined;
          try {
            const records = await fetchRedemptions(h.orderId);
            alreadyRedeemed = records.length > 0;
            redemptionStatus = latestRedemptionStatus(records);
          } catch {
            // Non-fatal — treat as not redeemed; the backend still guards duplicates.
          }
          return {
            orderId: h.orderId,
            folio: h.folio,
            schemeName: h.schemeName,
            amcName: h.amcName,
            availableUnits: h.availableUnits != null ? Number(h.availableUnits) : null,
            blockedUnits: h.blockedUnits != null ? Number(h.blockedUnits) : null,
            latestNav: h.latestNav != null ? Number(h.latestNav) : null,
            navAsOf: h.navAsOf,
            currentValue: h.currentValue != null ? Number(h.currentValue) : null,
            dataQuality: h.dataQuality,
            redeemable: Boolean(h.redeemable),
            sipName: h?.sipName ?? null,
            sipNumber: h?.sipNumber ?? null,
            sipFrequency: h?.sipFrequency ?? null,
            alreadyRedeemed,
            redemptionStatus,
          } as Holding;
        }),
      );

      setHoldings(withRedemptionFlags);
    } catch (err) {
      console.error('Failed to load redeemable holdings', err);
      setLoadError(err instanceof Error ? err.message : 'Unable to load holdings.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadHoldings();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [investorId]);

  // A holding can be redeemed only when the backend says it is redeemable AND there
  // are NET available units left to redeem (REQ #9 — gate on availableUnits).
  const canRedeem = (h: Holding) =>
    h.redeemable !== false
    && typeof h.availableUnits === 'number'
    && Number.isFinite(h.availableUnits)
    && h.availableUnits > 0;

  // Opens the confirmation modal — nothing is submitted until the user confirms.
  const requestRedeem = (holding: Holding) => {
    if (holding.alreadyRedeemed || redeemingId) return;
    if (!canRedeem(holding)) {
      setActionError('This holding has no available units to redeem.');
      return;
    }
    setActionError('');
    setActionMessage('');
    setConfirmHolding(holding);
  };

  const redeem = async (holding: Holding) => {
    if (holding.alreadyRedeemed || redeemingId) return;
    if (!canRedeem(holding)) {
      setActionError('This holding has no available units to redeem.');
      return;
    }
    const schemeName = holding.schemeName || 'this holding';
    setRedeemingId(holding.orderId);
    setActionError('');
    setActionMessage('');
    // Distributor redemption now requires investor 2FA: this creates the redemption
    // draft and requests the investor's OTP approval — no units are sold until the
    // investor authorizes it with a one-time passcode in their portal.
    const result = await submitRedemption(holding.orderId);
    if (result.ok) {
      setHoldings(prev => prev.map(h => (h.orderId === holding.orderId
        ? { ...h, alreadyRedeemed: true, redemptionStatus: 'PENDING_INVESTOR_ACTION' }
        : h)));
      setActionMessage(`${result.message} — ${schemeName}.`);
    } else {
      setActionError(result.message);
    }
    setRedeemingId('');
  };

  const syncStatus = async (holding: Holding) => {
    if (syncingId) return;
    setSyncingId(holding.orderId);
    try {
      const records = await syncRedemptions(holding.orderId);
      const status = latestRedemptionStatus(records);
      setHoldings(prev =>
        prev.map(h =>
          h.orderId === holding.orderId ? { ...h, redemptionStatus: status, alreadyRedeemed: records.length > 0 } : h,
        ),
      );
    } finally {
      setSyncingId('');
    }
  };

  const totalCurrentValue = useMemo(
    () => holdings.reduce((sum, h) => sum + (typeof h.currentValue === 'number' ? h.currentValue : 0), 0),
    [holdings],
  );

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 space-y-6">
      <button
        onClick={() => navigate('/distributor/investors')}
        className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
      >
        <ArrowLeft className="w-4 h-4" /> Back to Investors
      </button>

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Redeem Holdings</h1>
          <p className="text-slate-500 text-sm mt-1">
            {investorName ? `Sell mutual-fund holdings for ${investorName}` : 'Sell an investor’s mutual-fund holdings'} via Platizio.
          </p>
        </div>
        <button
          onClick={loadHoldings}
          disabled={loading}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors disabled:opacity-50"
        >
          <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} /> Refresh
        </button>
      </div>

      <div className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
        <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
        <span>
          Redemption here is a <strong>full</strong> redemption of the available units in the selected holding. It requires the investor to authorize it with a one-time passcode in their portal (2FA). SIPs are paused or cancelled from the
          {' '}<button onClick={() => navigate('/distributor/sip-dashboard')} className="underline font-semibold">SIP Dashboard</button>.
        </span>
      </div>

      {actionMessage && (
        <div className="flex items-start gap-2 rounded-xl border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700">
          <CheckCircle2 className="w-4 h-4 mt-0.5 flex-shrink-0" />
          <span>{actionMessage}</span>
        </div>
      )}
      {actionError && (
        <div className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h2 className="font-semibold text-slate-800">
            Redeemable Holdings <span className="ml-2 text-xs font-normal text-slate-400">({holdings.length})</span>
          </h2>
          {holdings.length > 0 && totalCurrentValue > 0 && (
            <span className="text-xs font-medium text-slate-500">Total current value: {formatCurrency(totalCurrentValue)}</span>
          )}
        </div>

        {loading ? (
          <div className="p-16 text-center text-slate-500">
            <div className="animate-spin w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
            Loading holdings...
          </div>
        ) : loadError ? (
          <div className="py-16 text-center">
            <AlertCircle className="w-7 h-7 text-red-400 mx-auto mb-2" />
            <p className="text-sm font-semibold text-slate-700">Couldn&rsquo;t load holdings</p>
            <p className="text-xs text-slate-400 mt-1">{loadError}</p>
          </div>
        ) : holdings.length === 0 ? (
          <div className="py-16 text-center">
            <Layers className="w-7 h-7 text-slate-300 mx-auto mb-2" />
            <p className="text-sm font-semibold text-slate-600">No redeemable holdings</p>
            <p className="text-xs text-slate-400 mt-1">
              This investor has no completed purchase orders to redeem yet.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                <tr>
                  <th className="px-6 py-4">Scheme</th>
                  <th className="px-6 py-4">Folio number</th>
                  <th className="px-6 py-4 text-right">Available units</th>
                  <th className="px-6 py-4 text-right">Available amount</th>
                  <th className="px-6 py-4">NAV as of</th>
                  <th className="px-6 py-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {holdings.map(h => {
                  const quality = normalizeQuality(h.dataQuality);
                  const availableUnitsLabel = formatUnits(h.availableUnits);
                  const currentValueLabel = formatCurrency(h.currentValue);
                  const hasBlocked = typeof h.blockedUnits === 'number' && h.blockedUnits > 0;
                  return (
                    <tr key={h.orderId} className="hover:bg-slate-50 transition-colors">
                      <td className="px-6 py-4">
                        <div className="font-semibold text-slate-800 max-w-[260px] truncate">
                          {h.schemeName || 'Scheme name unavailable'}
                        </div>
                        {h.amcName && h.amcName !== '—' && (
                          <div className="text-xs text-slate-400 mt-0.5">{h.amcName}</div>
                        )}
                      </td>
                      <td className="px-6 py-4 text-sm font-mono text-slate-600">{h.folio || '—'}</td>
                      <td className="px-6 py-4 text-sm text-right font-mono font-semibold text-slate-800">
                        {availableUnitsLabel ?? (quality === 'UNAVAILABLE' ? 'Unavailable' : '—')}
                        {hasBlocked && (
                          <div className="text-[10px] font-normal text-amber-600 mt-0.5">
                            {formatUnits(h.blockedUnits)} units blocked
                          </div>
                        )}
                      </td>
                      <td className="px-6 py-4 text-sm text-right font-mono text-slate-600">
                        {currentValueLabel ?? (quality === 'UNAVAILABLE' ? 'Unavailable' : quality === 'STALE' ? 'Not current' : '—')}
                      </td>
                      <td className="px-6 py-4 text-sm text-slate-600">{formatDate(h.navAsOf)}</td>
                      <td className="px-6 py-4 text-right">
                        {h.alreadyRedeemed ? (
                          <div className="inline-flex items-center gap-2">
                            <span className={`inline-flex items-center gap-1 text-[11px] font-semibold px-2 py-0.5 rounded-full ${redemptionStatusMeta(h.redemptionStatus).cls}`}>
                              {h.redemptionStatus === 'SUCCESSFUL' || h.redemptionStatus === 'BANK_CREDIT_COMPLETED' ? (
                                <CheckCircle2 className="w-3 h-3" />
                              ) : null}
                              {redemptionStatusMeta(h.redemptionStatus).label}
                            </span>
                            <button
                              onClick={() => syncStatus(h)}
                              disabled={syncingId === h.orderId}
                              title="Refresh redemption status from Cybrilla"
                              className="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-semibold text-slate-500 border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-40"
                            >
                              <RefreshCw className={`w-3 h-3 ${syncingId === h.orderId ? 'animate-spin' : ''}`} /> Sync
                            </button>
                          </div>
                        ) : canRedeem(h) ? (
                          <button
                            onClick={() => requestRedeem(h)}
                            disabled={redeemingId === h.orderId}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-amber-700 border border-amber-200 rounded-lg hover:bg-amber-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                          >
                            <TrendingDown className="w-3.5 h-3.5" />
                            {redeemingId === h.orderId ? 'Redeeming...' : 'Redeem'}
                          </button>
                        ) : (
                          <span className="inline-flex items-center gap-1 text-[11px] font-medium text-slate-400">
                            <Info className="w-3 h-3" /> No units available
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Redemption confirmation modal — mirrors the InvestorNominees opt-out modal style. */}
      {confirmHolding && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl">
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-slate-400">Confirm</p>
                <p className="text-sm font-semibold text-slate-800">Redeem this holding?</p>
              </div>
              <button onClick={() => setConfirmHolding(null)} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-100">
                <X className="h-4 w-4" />
              </button>
            </div>

            <dl className="mt-4 space-y-2 rounded-xl border border-slate-100 bg-slate-50 px-4 py-3 text-xs">
              <div className="flex justify-between gap-3">
                <dt className="text-slate-400">Scheme</dt>
                <dd className="text-right font-semibold text-slate-800">
                  {confirmHolding.schemeName || 'Scheme name unavailable'}
                  {confirmHolding.amcName && confirmHolding.amcName !== '—' && (
                    <span className="block text-[11px] font-normal text-slate-500">{confirmHolding.amcName}</span>
                  )}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-slate-400">Folio number</dt>
                <dd className="font-mono text-slate-700">{confirmHolding.folio || '—'}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-slate-400">Units being redeemed</dt>
                <dd className="font-mono font-semibold text-slate-800">{formatUnits(confirmHolding.availableUnits) ?? '—'}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-slate-400">Available amount</dt>
                <dd className="font-mono text-slate-700">{formatCurrency(confirmHolding.currentValue) ?? '—'}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-slate-400">NAV as of</dt>
                <dd className="text-slate-700">{formatDate(confirmHolding.navAsOf)}</dd>
              </div>
              {confirmHolding?.sipName && (
                <div className="flex justify-between gap-3">
                  <dt className="text-slate-400">SIP name</dt>
                  <dd className="text-slate-700">{confirmHolding.sipName}</dd>
                </div>
              )}
              {confirmHolding?.sipNumber && (
                <div className="flex justify-between gap-3">
                  <dt className="text-slate-400">SIP number</dt>
                  <dd className="font-mono text-slate-700">{confirmHolding.sipNumber}</dd>
                </div>
              )}
              {confirmHolding?.sipFrequency && (
                <div className="flex justify-between gap-3">
                  <dt className="text-slate-400">SIP frequency</dt>
                  <dd className="text-slate-700">{prettySipFrequency(confirmHolding.sipFrequency)}</dd>
                </div>
              )}
            </dl>

            <div className="mt-4 flex items-start gap-2.5 rounded-xl border border-amber-100 bg-amber-50 px-3 py-3">
              <AlertCircle className="mt-0.5 h-4 w-4 flex-shrink-0 text-amber-600" />
              <p className="text-xs text-amber-800">
                This submits a <strong>full</strong> redemption of the available units. The investor must
                authorize it with a one-time passcode in their portal before any units are sold.
              </p>
            </div>

            <div className="mt-5 flex gap-3">
              <button
                onClick={() => setConfirmHolding(null)}
                className="flex-1 rounded-xl border border-slate-200 py-2.5 text-sm font-semibold text-slate-600 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  const holding = confirmHolding;
                  setConfirmHolding(null);
                  if (holding) void redeem(holding);
                }}
                className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-[#0B1B3E] py-2.5 text-sm font-semibold text-white hover:bg-[#1A3066]"
              >
                <TrendingDown className="h-4 w-4" /> Confirm redemption request
              </button>
            </div>
          </div>
        </div>
      )}
    </motion.div>
  );
}
