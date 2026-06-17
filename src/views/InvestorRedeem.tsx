import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { motion } from 'motion/react';
import { ArrowLeft, TrendingDown, AlertCircle, CheckCircle2, Layers, RefreshCw } from 'lucide-react';
import { apiFetch } from '../config/api';
import { getPageContent } from '../utils/pagination';
import { fetchRedemptions, syncRedemptions, latestRedemptionStatus, redemptionStatusMeta } from '../utils/redeemOrder';

// Order statuses that represent a settled holding the investor actually owns
// and can therefore redeem (sell). Draft / failed / pending orders are not
// redeemable.
const REDEEMABLE_STATUSES = new Set(['SUCCESSFUL', 'COMPLETED']);
// SIPs are managed (paused / cancelled) from the SIP Dashboard, so the redeem
// screen only surfaces one-time purchase holdings.
const REDEEMABLE_TYPES = new Set(['PURCHASE', 'LUMPSUM_PURCHASE']);

interface Holding {
  id: string;
  fund: string;
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

  const loadHoldings = async () => {
    if (!investorId) return;
    setLoading(true);
    setLoadError('');
    try {
      const [ordersRes, schemesRes] = await Promise.all([
        apiFetch(`/orders/by-investor/${investorId}`),
        apiFetch('/products/schemes?size=200'),
      ]);

      if (!ordersRes.ok) {
        throw new Error(`Could not load this investor's orders (HTTP ${ordersRes.status}).`);
      }

      const orders = await ordersRes.json().catch(() => []);
      const schemesPayload = schemesRes.ok ? await schemesRes.json() : [];
      const schemes = getPageContent(schemesPayload);
      const schemeMap = new Map<string, any>(schemes.map((s: any) => [s.id, s]));

      const redeemable = (Array.isArray(orders) ? orders : []).filter(
        (o: any) =>
          REDEEMABLE_STATUSES.has(String(o.orderStatus || '').toUpperCase()) &&
          REDEEMABLE_TYPES.has(String(o.transactionType || '').toUpperCase()),
      );

      // Mark holdings that already have a redemption in flight/done so we don't
      // submit a duplicate redemption against the same order.
      const withRedemptionFlags = await Promise.all(
        redeemable.map(async (o: any) => {
          let alreadyRedeemed = false;
          let redemptionStatus: string | undefined;
          try {
            const records = await fetchRedemptions(o.id);
            alreadyRedeemed = records.length > 0;
            redemptionStatus = latestRedemptionStatus(records);
          } catch {
            // Non-fatal — treat as not redeemed; the backend still guards duplicates.
          }
          const scm = schemeMap.get(o.productSchemeId);
          // Prefer the fund name the backend snapshots onto the order at creation time
          // (immune to the /products/schemes page-size cap of 100 that makes the bulk
          // scheme-map lookup miss for holdings beyond the first 100 schemes). Fall back to
          // the live scheme map, then to a neutral placeholder — never block Redeem on it.
          const snapshotName = typeof o.productSchemeName === 'string' ? o.productSchemeName.trim() : '';
          const fund = snapshotName || scm?.schemeName || 'Scheme (name unavailable)';
          return {
            id: o.id,
            fund,
            type: o.transactionType || 'PURCHASE',
            amount: Number(o.amount) || 0,
            units: o.units != null ? Number(o.units) : undefined,
            status: o.orderStatus || '—',
            createdAt: o.createdAt,
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

  const redeem = async (holding: Holding) => {
    if (holding.alreadyRedeemed || redeemingId) return;
    if (!window.confirm(`Redeem the full holding in "${holding.fund}" (${formatCurrency(holding.amount)})? This sells the units back to the AMC.`)) {
      return;
    }
    setRedeemingId(holding.id);
    setActionError('');
    setActionMessage('');
    try {
      const response = await apiFetch(`/orders/${holding.id}/redemption`, { method: 'POST' });
      if (!response.ok) {
        const body = await response.json().catch(() => null);
        if (response.status === 502 || response.status === 503) {
          throw new Error('Platizio is temporarily unavailable. Your request was not submitted — please try again in a few minutes.');
        }
        const message = body?.message || `Redemption failed (HTTP ${response.status}).`;
        if (/mf investment account|investor profile|occupation/i.test(message)) {
          throw new Error(
            `${message} Redemption uses the same investor FP profile setup as purchases — restart the backend if you recently deployed a fix, then retry.`,
          );
        }
        if (/fintech primitives purchase id|externalorderid/i.test(message)) {
          throw new Error(
            'This holding was not purchased through live Platizio POA (demo-only order). Place a real purchase first, then redeem that order.',
          );
        }
        throw new Error(message);
      }
      setHoldings(prev => prev.map(h => (h.id === holding.id ? { ...h, alreadyRedeemed: true, redemptionStatus: 'SUBMITTED' } : h)));
      setActionMessage(`Redemption submitted for "${holding.fund}". Use Sync to track its status.`);
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Redemption failed. Please try again.');
    } finally {
      setRedeemingId('');
    }
  };

  const syncStatus = async (holding: Holding) => {
    if (syncingId) return;
    setSyncingId(holding.id);
    try {
      const records = await syncRedemptions(holding.id);
      const status = latestRedemptionStatus(records);
      setHoldings(prev =>
        prev.map(h =>
          h.id === holding.id ? { ...h, redemptionStatus: status, alreadyRedeemed: records.length > 0 } : h,
        ),
      );
    } finally {
      setSyncingId('');
    }
  };

  const totalInvested = useMemo(
    () => holdings.reduce((sum, h) => sum + (h.amount || 0), 0),
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
            {investorName ? `Sell mutual-fund holdings for ${investorName}` : 'Sell an investor\u2019s mutual-fund holdings'} via Platizio.
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
          Redemption here is a <strong>full</strong> redemption of the selected holding. SIPs are paused or cancelled from the
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
          {holdings.length > 0 && (
            <span className="text-xs font-medium text-slate-500">Total invested: {formatCurrency(totalInvested)}</span>
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
                  <th className="px-6 py-4">Fund</th>
                  <th className="px-6 py-4">Type</th>
                  <th className="px-6 py-4 text-right">Invested</th>
                  <th className="px-6 py-4 text-right">Units</th>
                  <th className="px-6 py-4">Purchased</th>
                  <th className="px-6 py-4 text-right">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {holdings.map(h => (
                  <tr key={h.id} className="hover:bg-slate-50 transition-colors">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-slate-800 max-w-[260px] truncate">{h.fund}</div>
                      <div className="text-xs text-slate-400 mt-0.5">{h.status}</div>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">{String(h.type).replace(/_/g, ' ')}</td>
                    <td className="px-6 py-4 text-sm text-right font-mono font-semibold text-slate-800">{formatCurrency(h.amount)}</td>
                    <td className="px-6 py-4 text-sm text-right font-mono text-slate-600">
                      {h.units != null ? h.units.toLocaleString('en-IN', { maximumFractionDigits: 3 }) : '—'}
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
