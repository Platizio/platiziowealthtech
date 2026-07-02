import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'motion/react';
import { TrendingDown, AlertCircle, CheckCircle2, Layers, RefreshCw, Search } from 'lucide-react';
import { apiFetch } from '../config/api';
import { fetchRedemptions, syncRedemptions, latestRedemptionStatus, redemptionStatusMeta, submitRedemption } from '../utils/redeemOrder';

// Order statuses that represent a settled holding the investor actually owns and
// can therefore redeem (sell). Draft / failed / pending orders are not redeemable.
const REDEEMABLE_STATUSES = new Set(['SUCCESSFUL', 'COMPLETED']);
// SIPs are managed (paused / cancelled) from the SIP Dashboard, so the redemption
// surface only lists one-time purchase holdings.
const REDEEMABLE_TYPES = new Set(['PURCHASE', 'LUMPSUM_PURCHASE']);

interface RedeemableHolding {
  id: string;
  investorId: string;
  investorName: string;
  fund: string;
  amc: string;
  type: string;
  amount: number;
  units?: number;
  status: string;
  createdAt?: string;
  alreadyRedeemed: boolean;
  redemptionStatus?: string;
}

const formatCurrency = (value?: number) =>
  typeof value === 'number' && Number.isFinite(value)
    ? `₹${value.toLocaleString('en-IN', { maximumFractionDigits: 2 })}`
    : '—';

const formatDate = (value?: string) => {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime())
    ? '—'
    : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
};

/**
 * Distributor-wide redemption surface. Lists every redeemable (completed, one-time
 * purchase) holding across the distributor's investors and submits a full redemption
 * against the chosen holding via POST /api/v1/orders/{orderId}/redemption.
 *
 * Fund names come from the snapshot the backend writes onto each order at creation
 * time (o.productSchemeName / o.productSchemeAmcName), so this view never needs the
 * bulk /products/schemes lookup and is immune to the scheme page-size cap that caused
 * "Unknown fund" elsewhere.
 */
export default function Redemptions({ userData }: { userData?: any }) {
  const navigate = useNavigate();
  const distributorId = userData?.id ? String(userData.id) : '';

  const [holdings, setHoldings] = useState<RedeemableHolding[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [redeemingId, setRedeemingId] = useState('');
  const [syncingId, setSyncingId] = useState('');
  const [actionError, setActionError] = useState('');
  const [actionMessage, setActionMessage] = useState('');
  const [search, setSearch] = useState('');

  const loadHoldings = useCallback(async () => {
    if (!distributorId) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setLoadError('');
    try {
      const [ordersRes, investorsRes] = await Promise.all([
        apiFetch(`/orders/by-distributor/${distributorId}`),
        apiFetch(`/investors/by-distributor/${distributorId}`),
      ]);

      if (!ordersRes.ok) {
        throw new Error(`Could not load orders (HTTP ${ordersRes.status}).`);
      }

      const orders = await ordersRes.json().catch(() => []);
      const investors = investorsRes.ok ? await investorsRes.json().catch(() => []) : [];
      const investorMap = new Map<string, any>(
        (Array.isArray(investors) ? investors : []).map((i: any) => [i.id, i]),
      );

      const redeemable = (Array.isArray(orders) ? orders : []).filter(
        (o: any) =>
          REDEEMABLE_STATUSES.has(String(o.orderStatus || '').toUpperCase()) &&
          REDEEMABLE_TYPES.has(String(o.transactionType || '').toUpperCase()),
      );

      // Mark holdings that already have a redemption in flight/done so we don't submit
      // a duplicate redemption against the same order (best-effort; backend also guards).
      const withFlags = await Promise.all(
        redeemable.map(async (o: any) => {
          let alreadyRedeemed = false;
          let redemptionStatus: string | undefined;
          try {
            const records = await fetchRedemptions(o.id);
            alreadyRedeemed = records.length > 0;
            redemptionStatus = latestRedemptionStatus(records);
          } catch {
            // Non-fatal — treat as not redeemed.
          }
          const inv = investorMap.get(o.investorId);
          const snapshotName =
            typeof o.productSchemeName === 'string' ? o.productSchemeName.trim() : '';
          return {
            id: o.id,
            investorId: o.investorId,
            investorName: inv?.fullName || inv?.full_name || 'Unknown Investor',
            fund: snapshotName || 'Scheme (name unavailable)',
            amc: typeof o.productSchemeAmcName === 'string' ? o.productSchemeAmcName.trim() : '',
            type: o.transactionType || 'PURCHASE',
            amount: Number(o.amount) || 0,
            units: o.units != null ? Number(o.units) : undefined,
            status: o.orderStatus || '—',
            createdAt: o.createdAt,
            alreadyRedeemed,
            redemptionStatus,
          } as RedeemableHolding;
        }),
      );

      setHoldings(withFlags);
    } catch (err) {
      console.error('Failed to load redeemable holdings', err);
      setLoadError(err instanceof Error ? err.message : 'Unable to load holdings.');
    } finally {
      setLoading(false);
    }
  }, [distributorId]);

  useEffect(() => {
    void loadHoldings();
  }, [loadHoldings]);

  const redeem = async (holding: RedeemableHolding) => {
    if (holding.alreadyRedeemed || redeemingId) return;
    setRedeemingId(holding.id);
    setActionError('');
    setActionMessage('');
    // Distributor redemption requires investor 2FA: create the draft + request the
    // investor's OTP approval (no units are sold until they authorize it in their portal).
    const result = await submitRedemption(holding.id);
    if (result.ok) {
      setHoldings(prev =>
        prev.map(h => (h.id === holding.id ? { ...h, alreadyRedeemed: true, redemptionStatus: 'PENDING_INVESTOR_ACTION' } : h)),
      );
      setActionMessage(`${result.message} — "${holding.fund}" (${holding.investorName}).`);
    } else {
      setActionError(result.message);
    }
    setRedeemingId('');
  };

  const syncStatus = async (holding: RedeemableHolding) => {
    if (syncingId) return;
    setSyncingId(holding.id);
    try {
      const records = await syncRedemptions(holding.id);
      const status = latestRedemptionStatus(records);
      setHoldings(prev =>
        prev.map(h =>
          h.id === holding.id
            ? { ...h, redemptionStatus: status, alreadyRedeemed: records.length > 0 }
            : h,
        ),
      );
    } finally {
      setSyncingId('');
    }
  };

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return holdings;
    return holdings.filter(
      h =>
        h.fund.toLowerCase().includes(q) ||
        h.amc.toLowerCase().includes(q) ||
        h.investorName.toLowerCase().includes(q),
    );
  }, [holdings, search]);

  const totalInvested = useMemo(
    () => filtered.reduce((sum, h) => sum + (h.amount || 0), 0),
    [filtered],
  );

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-8 space-y-6"
    >
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Redemptions</h1>
          <p className="text-slate-500 text-sm mt-1">
            Sell completed mutual-fund holdings back to the AMC via Cybrilla POA. Lists every
            redeemable holding across your investors.
          </p>
        </div>
        <button
          onClick={() => void loadHoldings()}
          disabled={loading}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors disabled:opacity-50"
        >
          <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} /> Refresh
        </button>
      </div>

      <div className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
        <AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />
        <span>
          Redemption here is a <strong>full</strong> redemption of the selected holding and requires the investor to authorize it with a one-time passcode (2FA) in their portal. Live available units/amount per holding are shown on each investor&rsquo;s Redeem page (click the investor name). SIPs are
          paused or cancelled from the{' '}
          <button
            onClick={() => navigate('/distributor/sip-dashboard')}
            className="underline font-semibold"
          >
            SIP Dashboard
          </button>
          .
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
        <div className="px-5 py-4 border-b border-slate-100 flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-semibold text-slate-800">
            Redeemable Holdings{' '}
            <span className="ml-2 text-xs font-normal text-slate-400">({filtered.length})</span>
          </h2>
          <div className="flex items-center gap-3">
            <div className="relative">
              <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
              <input
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder="Search fund, AMC, investor…"
                className="w-64 pl-9 pr-3 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg outline-none focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500"
              />
            </div>
            {filtered.length > 0 && (
              <span className="text-xs font-medium text-slate-500">
                Total invested: {formatCurrency(totalInvested)}
              </span>
            )}
          </div>
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
        ) : filtered.length === 0 ? (
          <div className="py-16 text-center">
            <Layers className="w-7 h-7 text-slate-300 mx-auto mb-2" />
            <p className="text-sm font-semibold text-slate-600">No redeemable holdings</p>
            <p className="text-xs text-slate-400 mt-1">
              Completed one-time purchase orders appear here once payment succeeds.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                <tr>
                  <th className="px-6 py-4">Investor</th>
                  <th className="px-6 py-4">Fund</th>
                  <th className="px-6 py-4 text-right">Invested</th>
                  <th className="px-6 py-4 text-right">Units</th>
                  <th className="px-6 py-4">Purchased</th>
                  <th className="px-6 py-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filtered.map(h => (
                  <tr key={h.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-6 py-4">
                      <button
                        onClick={() =>
                          navigate(`/distributor/investors/${h.investorId}/redeem`, {
                            state: { investor: { id: h.investorId, fullName: h.investorName } },
                          })
                        }
                        className="font-semibold text-slate-800 hover:text-blue-600 transition-colors text-left"
                      >
                        {h.investorName}
                      </button>
                    </td>
                    <td className="px-6 py-4">
                      <div className="font-medium text-slate-800 max-w-[260px] truncate">{h.fund}</div>
                      <div className="text-xs text-slate-400 mt-0.5">
                        {h.amc || String(h.type).replace(/_/g, ' ')}
                      </div>
                    </td>
                    <td className="px-6 py-4 text-sm text-right font-mono font-semibold text-slate-800">
                      {formatCurrency(h.amount)}
                    </td>
                    <td className="px-6 py-4 text-sm text-right font-mono text-slate-600">
                      {h.units != null
                        ? h.units.toLocaleString('en-IN', { maximumFractionDigits: 3 })
                        : '—'}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">{formatDate(h.createdAt)}</td>
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
                            disabled={syncingId === h.id}
                            title="Refresh redemption status from Cybrilla"
                            className="inline-flex items-center gap-1 px-2 py-1 text-[11px] font-semibold text-slate-500 border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-40"
                          >
                            <RefreshCw className={`w-3 h-3 ${syncingId === h.id ? 'animate-spin' : ''}`} /> Sync
                          </button>
                        </div>
                      ) : (
                        <button
                          onClick={() => redeem(h)}
                          disabled={redeemingId === h.id}
                          className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-amber-700 border border-amber-200 rounded-lg hover:bg-amber-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                          <TrendingDown className="w-3.5 h-3.5" />
                          {redeemingId === h.id ? 'Redeeming...' : 'Redeem'}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </motion.div>
  );
}
