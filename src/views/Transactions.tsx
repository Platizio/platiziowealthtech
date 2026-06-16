import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { useNavigate } from 'react-router-dom';
import { Search, Filter, ChevronLeft, CheckCircle2, Clock, XCircle, AlertCircle, RefreshCw, ArrowDown, ArrowUp, ArrowUpDown, Inbox, Pencil, TrendingDown } from 'lucide-react';
import { apiFetch } from '../config/api';
import InvestorActionLink from '../components/InvestorActionLink';
import Pagination from '../components/Pagination';
import { getPageContent, getPageMeta } from '../utils/pagination';
import { formatDateTime } from '../utils/formatDate';
import { buildInvestorActionUrl, formatOrderStatusLabel, normalizeOrderStatus } from '../utils/investorAction';
import { isPersistedSchemeId } from '../utils/productSchemeKey';
import { isSipCancellable } from '../utils/sipCancel';
import { isRedeemableHolding, submitRedemption } from '../utils/redeemOrder';
import type { TransactionListItem } from '../types/order';
import EmptyState from '../components/EmptyState';

type StatusKey = 'Successful' | 'Processing' | 'Submitted' | 'Payment Pending' | 'Pending Investor Action' | 'Failed' | 'Retry Available' | 'Draft' | 'Created' | 'SUCCESSFUL' | 'COMPLETED' | 'FAILED' | 'PENDING_PAYMENT' | 'DRAFT' | 'Cancelled' | 'CANCELLED';

const statusConfig: Record<StatusKey, { color: string; icon: React.ReactNode; label: string }> = {
  Successful: { color: 'bg-green-50 text-green-700', icon: <CheckCircle2 className="w-3.5 h-3.5" />, label: 'Successful' },
  SUCCESSFUL: { color: 'bg-green-50 text-green-700', icon: <CheckCircle2 className="w-3.5 h-3.5" />, label: 'Successful' },
  COMPLETED: { color: 'bg-green-50 text-green-700', icon: <CheckCircle2 className="w-3.5 h-3.5" />, label: 'Completed' },
  Processing: { color: 'bg-blue-50 text-blue-700', icon: <RefreshCw className="w-3.5 h-3.5 animate-spin" />, label: 'Processing' },
  Submitted: { color: 'bg-indigo-50 text-indigo-700', icon: <Clock className="w-3.5 h-3.5" />, label: 'Submitted' },
  'Payment Pending': { color: 'bg-orange-50 text-orange-700', icon: <Clock className="w-3.5 h-3.5" />, label: 'Payment Pending' },
  PENDING_PAYMENT: { color: 'bg-orange-50 text-orange-700', icon: <Clock className="w-3.5 h-3.5" />, label: 'Payment Pending' },
  'Pending Investor Action': { color: 'bg-amber-50 text-amber-700', icon: <AlertCircle className="w-3.5 h-3.5" />, label: 'Pending Investor Action' },
  Failed: { color: 'bg-red-50 text-red-700', icon: <XCircle className="w-3.5 h-3.5" />, label: 'Failed' },
  FAILED: { color: 'bg-red-50 text-red-700', icon: <XCircle className="w-3.5 h-3.5" />, label: 'Failed' },
  Cancelled: { color: 'bg-slate-100 text-slate-600', icon: <XCircle className="w-3.5 h-3.5" />, label: 'Cancelled' },
  CANCELLED: { color: 'bg-slate-100 text-slate-600', icon: <XCircle className="w-3.5 h-3.5" />, label: 'Cancelled' },
  'Retry Available': { color: 'bg-purple-50 text-purple-700', icon: <RefreshCw className="w-3.5 h-3.5" />, label: 'Retry Available' },
  Draft: { color: 'bg-slate-100 text-slate-600', icon: <Clock className="w-3.5 h-3.5" />, label: 'Draft' },
  DRAFT: { color: 'bg-slate-100 text-slate-600', icon: <Clock className="w-3.5 h-3.5" />, label: 'Draft' },
  Created: { color: 'bg-slate-100 text-slate-600', icon: <Clock className="w-3.5 h-3.5" />, label: 'Created' },
};

const ORDER_STATUS_STEPS = ['Pending', 'Processing', 'Completed'];

const getOrderStatusStepIndex = (status?: string) => {
  const normalized = String(status || '').trim().toUpperCase().replace(/[\s-]+/g, '_');

  if (['FAILED', 'CANCELLED'].includes(normalized)) return -1;
  if (['COMPLETED', 'SUCCESSFUL', 'SUCCESS'].includes(normalized)) return 2;
  if (['PROCESSING', 'SUBMITTED', 'IN_PROGRESS', 'APPROVED', 'ACCEPTED', 'PAYMENT_PENDING', 'PENDING_PAYMENT'].includes(normalized)) return 1;
  return 0;
};

function StatusTimeline({ steps, current }: { steps: string[]; current?: string }) {
  const currentIndex = getOrderStatusStepIndex(current);
  const failed = currentIndex < 0;

  return (
    <div className="space-y-0">
      {failed ? (
        <div className="flex gap-3">
          <div className="flex h-6 w-6 items-center justify-center rounded-full bg-red-500">
            <XCircle className="h-3.5 w-3.5 text-white" />
          </div>
          <div>
            <p className="text-sm font-medium text-white">Failed</p>
            <p className="mt-0.5 text-[10px] text-red-300">Current status</p>
          </div>
        </div>
      ) : (
        <>
      {steps.map((step, index) => {
        const isCompleted = index < currentIndex;
        const isCurrent = index === currentIndex;
        const isFuture = index > currentIndex;

        return (
          <div key={step} className="flex gap-3">
            <div className="flex flex-col items-center">
              <div className="relative flex h-6 w-6 items-center justify-center">
                {isCurrent && (
                  <span className="absolute inline-flex h-6 w-6 animate-ping rounded-full bg-blue-300/40" />
                )}
                <div
                  className={`relative z-10 flex h-5 w-5 items-center justify-center rounded-full ${
                    isCompleted
                      ? 'bg-green-500'
                      : isCurrent
                        ? 'border-2 border-blue-300 bg-[#0B1B3E]'
                        : 'border border-white/20 bg-white/10'
                  }`}
                >
                  {isCompleted && <CheckCircle2 className="h-3.5 w-3.5 text-white" />}
                  {isCurrent && <span className="h-2 w-2 rounded-full bg-blue-200" />}
                  {isFuture && <span className="h-1.5 w-1.5 rounded-full bg-white/30" />}
                </div>
              </div>
              {index < steps.length - 1 && (
                <div className={`mt-1 h-10 w-0.5 ${isCompleted ? 'bg-green-500' : 'bg-white/10'}`} />
              )}
            </div>
            <div className="pb-10">
              <p className={`text-sm font-medium leading-tight ${isCompleted || isCurrent ? 'text-white' : 'text-white/40'}`}>
                {step}
              </p>
              <p className="mt-0.5 text-[10px] text-blue-300">
                {isCompleted ? 'Completed' : isCurrent ? 'Current status' : 'Pending'}
              </p>
            </div>
          </div>
        );
      })}
        </>
      )}
    </div>
  );
}

const TYPE_FILTERS = ['All', 'SIP', 'Lumpsum', 'Redemption', 'Switch'];
const STATUS_FILTERS = ['All', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'];
type SortDirection = 'ASC' | 'DESC';
type SortField = 'investorId' | 'productSchemeId' | 'transactionType' | 'amount' | 'orderStatus' | 'createdAt';

const matchesStatusFilter = (status: string, statusFilter: string) => {
  if (statusFilter === 'All') return true;
  const normalized = normalizeOrderStatus(status);

  if (statusFilter === 'PENDING') {
    return ['DRAFT', 'CREATED', 'PENDING', 'PENDING_INVESTOR_ACTION', 'PAYMENT_PENDING', 'PENDING_PAYMENT'].includes(normalized);
  }
  if (statusFilter === 'PROCESSING') {
    return ['PROCESSING', 'SUBMITTED', 'IN_PROGRESS'].includes(normalized);
  }
  if (statusFilter === 'COMPLETED') {
    return ['COMPLETED', 'SUCCESSFUL', 'SUCCESS'].includes(normalized);
  }
  return normalized === statusFilter;
};

const getComparableValue = (tx: any, sortField: SortField) => {
  if (sortField === 'createdAt') return new Date(tx.rawCreatedAt || 0).getTime();
  if (sortField === 'amount') return Number(tx.rawAmount || 0);
  if (sortField === 'investorId') return String(tx.investor || '').toLowerCase();
  if (sortField === 'productSchemeId') return String(tx.fund || '').toLowerCase();
  if (sortField === 'transactionType') return String(tx.type || '').toLowerCase();
  return normalizeOrderStatus(tx.status);
};

export default function Transactions({ userData }: { userData?: any }) {
  const navigate = useNavigate();
  const [selected, setSelected] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState('All');
  const [statusFilter, setStatusFilter] = useState('All');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [sortField, setSortField] = useState<SortField>('createdAt');
  const [sortDirection, setSortDirection] = useState<SortDirection>('DESC');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [transactions, setTransactions] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const handleFilterChange = (setter: (value: string) => void, value: string) => {
    setter(value);
    setPage(0);
  };

  const handleSort = (field: SortField) => {
    setSortField(prevField => {
      if (prevField === field) {
        setSortDirection(prevDirection => prevDirection === 'ASC' ? 'DESC' : 'ASC');
        return prevField;
      }

      setSortDirection('ASC');
      return field;
    });
    setPage(0);
  };

  const renderSortIcon = (field: SortField) => {
    if (sortField !== field) return <ArrowUpDown className="h-3.5 w-3.5 text-slate-300" />;
    return sortDirection === 'ASC'
      ? <ArrowUp className="h-3.5 w-3.5 text-[#0B1B3E]" />
      : <ArrowDown className="h-3.5 w-3.5 text-[#0B1B3E]" />;
  };

  const SortableHeader = ({
    field,
    children,
    align = 'left',
  }: {
    field: SortField;
    children: React.ReactNode;
    align?: 'left' | 'right';
  }) => (
    <th className={`px-6 py-4 ${align === 'right' ? 'text-right' : ''}`}>
      <button
        type="button"
        onClick={() => handleSort(field)}
        className={`inline-flex items-center gap-1.5 hover:text-[#0B1B3E] transition-colors ${align === 'right' ? 'justify-end' : ''}`}
      >
        {children}
        {renderSortIcon(field)}
      </button>
    </th>
  );

  useEffect(() => {
    if (!userData?.id) return;
    
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };

    const fetchData = async () => {
      try {
        setLoading(true);
        const params = new URLSearchParams({
          sortBy: sortField,
          direction: sortDirection,
          page: String(page),
          size: String(size),
        });

        if (statusFilter !== 'All') params.set('status', statusFilter);
        if (fromDate) params.set('from', fromDate);
        if (toDate) params.set('to', toDate);

        // Fetch orders, investors, and schemes to map data correctly
        const ordersRequest = await apiFetch(`/orders?${params.toString()}`, { headers });
        const ordersRes = ordersRequest.ok
          ? ordersRequest
          : await apiFetch(`/orders/by-distributor/${userData.id}`, { headers });

        const [investorsRes, schemesRes] = await Promise.all([
          apiFetch(`/investors/by-distributor/${userData.id}`, { headers }),
          apiFetch('/products/schemes?local=true&size=1000', { headers })
        ]);

        const ordersPayload = ordersRes.ok ? await ordersRes.json() : [];
        const orders = getPageContent(ordersPayload);
        const meta = getPageMeta(ordersPayload, orders.length);
        const investors = investorsRes.ok ? await investorsRes.json() : [];
        const schemesPayload = schemesRes.ok ? await schemesRes.json() : [];
        const schemes = getPageContent(schemesPayload);

        const investorMap = new Map(investors.map((i: any) => [i.id, i]));
        const schemeMap = new Map(schemes.map((s: any) => [s.id, s]));

        const formatted: TransactionListItem[] = orders.map((o: any) => {
          const inv = investorMap.get(o.investorId) as any;
          const scm = schemeMap.get(o.productSchemeId) as any;
          // Fund-name precedence (mirrors backend ProductSchemeOrderSupport.displayName):
          //   1. o.productSchemeName — the snapshot the backend writes onto every order at
          //      creation time. It is always correct and immune to the /products/schemes
          //      page-size cap (server caps size to 100), which is what previously made the
          //      scheme-map lookup miss and render "Unknown fund" + a false "Payment Failed".
          //   2. the live scheme map — only a fallback for legacy orders placed before the
          //      snapshot columns existed.
          const snapshotName = typeof o.productSchemeName === 'string' ? o.productSchemeName.trim() : '';
          const mappedName = scm && typeof scm.schemeName === 'string' ? scm.schemeName.trim() : '';
          const resolvedFund = snapshotName || mappedName;
          const schemeKnown = Boolean(resolvedFund) || Boolean(scm && isPersistedSchemeId(o.productSchemeId));
          const rawStatus = !schemeKnown && normalizeOrderStatus(o.orderStatus) !== 'CANCELLED'
            ? 'FAILED'
            : (o.orderStatus || 'Draft');
          const displayStatus = !schemeKnown ? 'Payment Failed' : formatOrderStatusLabel(rawStatus);
          return {
            id: o.id,
            investorId: o.investorId,
            productSchemeId: o.productSchemeId,
            investor: inv ? inv.fullName || 'Unknown Investor' : 'Unknown Investor',
            fund: resolvedFund || 'Unknown fund',
            type: o.transactionType || 'Lumpsum',
            amount: o.amount ? `₹${o.amount.toLocaleString()}` : '—',
            rawAmount: o.amount || 0,
            status: displayStatus,
            rawOrderStatus: rawStatus,
            schemeKnown,
            date: formatDateTime(o.createdAt || new Date().toISOString()),
            rawCreatedAt: o.createdAt || new Date().toISOString(),
            pan: inv?.pan || '—',
            mandate: o.paymentMode || o.mandateMode || '—',
            investorActionUrl: o.investorActionUrl,
            failureReason: o.failureReason,
          };
        });

        setTransactions(formatted);
        setTotalPages(meta.totalPages);
        setTotalElements(meta.totalElements);
      } catch (err) {
        console.error('Failed to fetch transactions', err);
      } finally {
        setLoading(false);
      }
    };

    fetchData();
  }, [userData, sortField, sortDirection, statusFilter, fromDate, toDate, page, size, refreshKey]);

  const filtered = transactions
    .filter(t => {
      const matchSearch = t.investor.toLowerCase().includes(search.toLowerCase()) ||
        t.fund.toLowerCase().includes(search.toLowerCase());
      const typeLabel = t.type.toLowerCase() === 'lumpsum' ? 'Lumpsum' : t.type;
      const matchType = typeFilter === 'All' || typeLabel === typeFilter;
      const matchStatus = matchesStatusFilter(t.status, statusFilter);
      const createdAt = new Date(t.rawCreatedAt);
      const matchFrom = !fromDate || createdAt >= new Date(`${fromDate}T00:00:00`);
      const matchTo = !toDate || createdAt <= new Date(`${toDate}T23:59:59`);
      return matchSearch && matchType && matchStatus && matchFrom && matchTo;
    })
    .sort((a, b) => {
      const aValue = getComparableValue(a, sortField);
      const bValue = getComparableValue(b, sortField);
      if (aValue < bValue) return sortDirection === 'ASC' ? -1 : 1;
      if (aValue > bValue) return sortDirection === 'ASC' ? 1 : -1;
      return 0;
    });

  if (selected !== null) {
    const tx = transactions.find(t => t.id === selected);
    if (tx) return (
      <TransactionDetail
        tx={tx}
        onBack={() => setSelected(null)}
        onOrderCancelled={() => {
          setSelected(null);
          setRefreshKey(k => k + 1);
        }}
        onCreateNewOrder={() => navigate('/distributor/ledger')}
      />
    );
  }

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 h-full flex flex-col">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Transactions</h1>
          <p className="text-slate-500 text-sm mt-1">Track all orders, SIPs and redemptions</p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => navigate('/distributor/ledger')}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors"
          >
            + New Transaction
          </button>
        </div>
      </div>

      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 flex flex-col flex-1 overflow-hidden">
        <div className="p-4 border-b border-slate-100 flex flex-wrap gap-3">
          <div className="relative flex-1 min-w-[200px] max-w-md">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text"
              value={search}
              onChange={e => handleFilterChange(setSearch, e.target.value)}
              placeholder="Search by investor or fund..."
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-slate-400" />
            <select
              value={statusFilter}
              onChange={e => handleFilterChange(setStatusFilter, e.target.value)}
              className="h-10 rounded-lg border border-slate-200 bg-slate-50 px-3 text-xs font-semibold text-slate-600 outline-none transition-all focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-100"
            >
              {STATUS_FILTERS.map(status => (
                <option key={status} value={status}>{status}</option>
              ))}
            </select>
          </div>
          <div className="flex items-center gap-2">
            <input
              type="date"
              value={fromDate}
              onChange={e => handleFilterChange(setFromDate, e.target.value)}
              className="h-10 rounded-lg border border-slate-200 bg-slate-50 px-3 text-xs font-semibold text-slate-600 outline-none transition-all focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-100"
              aria-label="Filter orders from date"
            />
            <span className="text-xs font-semibold text-slate-400">to</span>
            <input
              type="date"
              value={toDate}
              onChange={e => handleFilterChange(setToDate, e.target.value)}
              className="h-10 rounded-lg border border-slate-200 bg-slate-50 px-3 text-xs font-semibold text-slate-600 outline-none transition-all focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-100"
              aria-label="Filter orders to date"
            />
          </div>
          <div className="flex gap-2">
            {TYPE_FILTERS.map(f => (
              <button
                key={f}
                onClick={() => handleFilterChange(setTypeFilter, f)}
                className={`px-3 py-2 text-xs font-semibold rounded-lg border transition-colors ${typeFilter === f ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-slate-50 border-slate-200 text-slate-600 hover:bg-slate-100'}`}
              >
                {f}
              </button>
            ))}
          </div>
        </div>

        <div className="flex-1 overflow-auto">
          {loading ? (
            <div className="p-8 text-center text-slate-500">Loading transactions...</div>
          ) : filtered.length === 0 ? (
            <EmptyState
              icon={Inbox}
              title="No transactions found"
              subtitle="Try adjusting your filters or date range"
            />
          ) : (
            <table className="w-full text-left">
              <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 sticky top-0 z-10 font-semibold">
                <tr>
                  <SortableHeader field="investorId">Investor</SortableHeader>
                  <SortableHeader field="productSchemeId">Fund</SortableHeader>
                  <SortableHeader field="transactionType">Type</SortableHeader>
                  <SortableHeader field="amount">Amount</SortableHeader>
                  <SortableHeader field="orderStatus">Status</SortableHeader>
                  <SortableHeader field="createdAt" align="right">Date</SortableHeader>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {filtered.map(tx => {
                  const s = statusConfig[tx.status as StatusKey] ?? statusConfig.Draft;
                  return (
                    <tr key={tx.id} onClick={() => setSelected(tx.id)} className="group hover:bg-slate-50 transition-colors cursor-pointer">
                      <td className="px-6 py-4">
                        <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{tx.investor}</div>
                        <div className="text-xs text-slate-400 font-mono mt-1">TXN-{tx.id.substring(0, 6)}</div>
                      </td>
                      <td className="px-6 py-4 text-sm text-slate-600 max-w-[200px] truncate">{tx.fund}</td>
                      <td className="px-6 py-4">
                        <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${
                          tx.type.toUpperCase() === 'SIP' ? 'bg-blue-50 text-blue-700' :
                          tx.type.toUpperCase() === 'LUMPSUM' ? 'bg-purple-50 text-purple-700' :
                          tx.type.toUpperCase() === 'REDEMPTION' ? 'bg-amber-50 text-amber-700' :
                          'bg-slate-100 text-slate-600'
                        }`}>
                          {tx.type}
                        </span>
                      </td>
                      <td className="px-6 py-4 font-mono font-medium text-slate-700">{tx.amount}</td>
                      <td className="px-6 py-4">
                        <span className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-md w-fit ${s.color}`}>
                          {s.icon} {s.label}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-xs text-slate-500 text-right">{tx.date}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
        <Pagination
          page={page}
          size={size}
          totalPages={totalPages}
          totalElements={totalElements}
          onPageChange={setPage}
          onSizeChange={nextSize => { setSize(nextSize); setPage(0); }}
        />
      </div>
    </motion.div>
  );
}

function TransactionDetail({
  tx,
  onBack,
  onOrderCancelled,
  onCreateNewOrder,
}: {
  tx: TransactionListItem;
  onBack: () => void;
  onOrderCancelled?: () => void;
  onCreateNewOrder?: () => void;
}) {
  const [liveTx, setLiveTx] = useState(tx);
  const s = statusConfig[liveTx.status as StatusKey] ?? statusConfig[liveTx.rawOrderStatus as StatusKey] ?? statusConfig.Draft;
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState('');
  const [linkCopied, setLinkCopied] = useState(false);
  // Edit an active SIP in place via FP "Update a Purchase Plan"
  // (PATCH /orders/{id}/sip → PATCH /v2/mf_purchase_plans). Mirrors the SIP Dashboard modal.
  const [editingSip, setEditingSip] = useState(false);
  const [editAmount, setEditAmount] = useState('');
  const [editDay, setEditDay] = useState('');
  const [savingEdit, setSavingEdit] = useState(false);
  const [editError, setEditError] = useState('');
  // Redeem a completed one-time purchase (POST /orders/{id}/redemption).
  const [redeeming, setRedeeming] = useState(false);
  const [redeemed, setRedeemed] = useState(false);
  const [redeemMsg, setRedeemMsg] = useState('');
  const [redeemError, setRedeemError] = useState('');
  const isSipOrder = String(liveTx.type || '').toUpperCase() === 'SIP';
  const actionUrl = buildInvestorActionUrl(liveTx.investorActionUrl);
  const awaitingAction =
    normalizeOrderStatus(liveTx.rawOrderStatus) === 'PENDING_INVESTOR_ACTION'
    || normalizeOrderStatus(liveTx.rawOrderStatus) === 'PAYMENT_PENDING';

  useEffect(() => {
    setLiveTx(tx);
  }, [tx]);

  useEffect(() => {
    const normalized = normalizeOrderStatus(liveTx.rawOrderStatus);
    const shouldPoll = ['PENDING_INVESTOR_ACTION', 'PAYMENT_PENDING', 'PROCESSING', 'CREATED'].includes(normalized);
    if (!shouldPoll) return;

    let cancelled = false;
    const refresh = async () => {
      try {
        const response = await apiFetch(`/orders/${liveTx.id}`);
        if (!response.ok || cancelled) return;
        const order = await response.json();
        setLiveTx(prev => ({
          ...prev,
          rawOrderStatus: order.orderStatus || prev.rawOrderStatus,
          status: formatOrderStatusLabel(order.orderStatus),
          failureReason: order.failureReason ?? prev.failureReason,
          investorActionUrl: order.investorActionUrl ?? prev.investorActionUrl,
        }));
      } catch {
        // ignore transient poll errors
      }
    };

    refresh();
    const timer = window.setInterval(refresh, 5000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [liveTx.id, liveTx.rawOrderStatus]);

  const copyInvestorLink = async () => {
    if (!actionUrl) return;
    try {
      await navigator.clipboard.writeText(actionUrl);
      setLinkCopied(true);
      window.setTimeout(() => setLinkCopied(false), 2000);
    } catch {
      window.prompt('Copy this link for the investor:', actionUrl);
    }
  };

  const cancelSip = async () => {
    setCancelling(true);
    setCancelError('');
    try {
      const response = await apiFetch(`/orders/${liveTx.id}/cancel`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cancellationCode: 'invest_later' }),
      });
      if (!response.ok) {
        const errorBody = await response.json().catch(() => null);
        throw new Error(errorBody?.message || `Cancel SIP failed (${response.status})`);
      }
      onOrderCancelled?.();
    } catch (err: any) {
      setCancelError(err?.message || 'Cancel SIP failed. Please try again.');
    } finally {
      setCancelling(false);
      setConfirmCancel(false);
    }
  };

  const openEditSip = () => {
    setEditError('');
    const numeric = Number(String(liveTx.amount).replace(/[^0-9.]/g, ''));
    setEditAmount(Number.isFinite(numeric) && numeric > 0 ? String(numeric) : '');
    setEditDay('');
    setEditingSip(true);
  };

  const submitEditSip = async () => {
    const amountNum = editAmount.trim() === '' ? undefined : Number(editAmount);
    const dayNum = editDay.trim() === '' ? undefined : Number(editDay);
    if (amountNum === undefined && dayNum === undefined) {
      setEditError('Enter a new amount and/or installment day.');
      return;
    }
    if (amountNum !== undefined && (!Number.isFinite(amountNum) || amountNum <= 0)) {
      setEditError('Amount must be greater than 0.');
      return;
    }
    if (dayNum !== undefined && (!Number.isInteger(dayNum) || dayNum < 1 || dayNum > 28)) {
      setEditError('Installment day must be a whole number between 1 and 28.');
      return;
    }
    setSavingEdit(true);
    setEditError('');
    try {
      const body: Record<string, unknown> = {};
      if (amountNum !== undefined) body.amount = amountNum;
      if (dayNum !== undefined) body.installmentDay = dayNum;
      const response = await apiFetch(`/orders/${liveTx.id}/sip`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        const errorBody = await response.json().catch(() => null);
        throw new Error(errorBody?.message || `Edit SIP failed (${response.status})`);
      }
      if (amountNum !== undefined) {
        setLiveTx(prev => ({ ...prev, amount: `₹${amountNum.toLocaleString('en-IN')}`, rawAmount: amountNum }));
      }
      setEditingSip(false);
    } catch (err: any) {
      setEditError(err?.message || 'Failed to update SIP. Please try again.');
    } finally {
      setSavingEdit(false);
    }
  };

  const redeemHolding = async () => {
    if (redeeming || redeemed) return;
    if (!window.confirm(`Redeem the full holding in "${liveTx.fund}" (${liveTx.amount})? This sells the units back to the AMC.`)) {
      return;
    }
    setRedeeming(true);
    setRedeemMsg('');
    setRedeemError('');
    const result = await submitRedemption(liveTx.id);
    if (result.ok) {
      setRedeemed(true);
      setRedeemMsg(`Redemption submitted for "${liveTx.fund}". Track its progress under Transactions.`);
    } else {
      setRedeemError(result.message);
    }
    setRedeeming(false);
  };

  return (
    <motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} className="p-8 max-w-5xl">
      {confirmCancel && (
        <ConfirmModal
          title="Cancel SIP?"
          message="This calls Fintech Primitives POST /v2/mf_purchase_plans/{id}/cancel and records the SIP as Cancelled (Cybrilla keeps cancelled plans on record)."
          confirmLabel={cancelling ? 'Cancelling...' : 'Cancel SIP'}
          disabled={cancelling}
          onCancel={() => setConfirmCancel(false)}
          onConfirm={cancelSip}
        />
      )}
      {editingSip && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
          <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl">
            <h2 className="text-lg font-semibold text-slate-800">Edit SIP</h2>
            <p className="mt-1 text-sm text-slate-500">{liveTx.investor} · {liveTx.fund}</p>
            <p className="mt-2 text-xs text-slate-400">
              Updates the active plan with Fintech Primitives (PATCH /v2/mf_purchase_plans). Changes apply to the
              remaining installments and must be made at least 2 days before the next installment.
            </p>
            {editError && (
              <div className="mt-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{editError}</div>
            )}
            <div className="mt-5 space-y-4">
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-slate-500 mb-1">New amount (₹)</label>
                <input
                  type="number" min="1" inputMode="numeric"
                  value={editAmount}
                  onChange={e => setEditAmount(e.target.value)}
                  placeholder="Leave blank to keep current amount"
                  className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-100"
                />
              </div>
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-slate-500 mb-1">Installment day (1–28)</label>
                <input
                  type="number" min="1" max="28" inputMode="numeric"
                  value={editDay}
                  onChange={e => setEditDay(e.target.value)}
                  placeholder="Leave blank to keep current day"
                  className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm outline-none focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-100"
                />
              </div>
            </div>
            <div className="mt-6 flex justify-end gap-3">
              <button
                onClick={() => setEditingSip(false)}
                disabled={savingEdit}
                className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={submitEditSip}
                disabled={savingEdit}
                className="rounded-lg bg-[#0B1B3E] px-4 py-2 text-sm font-semibold text-white hover:bg-[#1A3066] disabled:opacity-50"
              >
                {savingEdit ? 'Saving...' : 'Save changes'}
              </button>
            </div>
          </div>
        </div>
      )}
      <button onClick={onBack} className="flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-slate-800 mb-6 transition-colors">
        <ChevronLeft className="w-4 h-4" /> Back to Transactions
      </button>

      <div className="flex justify-between items-start mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">{tx.investor}</h1>
          <div className="flex items-center gap-3 mt-2">
            <span className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-md ${s.color}`}>
              {s.icon} {s.label}
            </span>
            <span className="text-slate-400 text-xs font-mono">TXN-{tx.id.substring(0, 6)}</span>
          </div>
        </div>
        <div className="flex gap-2">
        {isSipOrder && isSipCancellable(liveTx.rawOrderStatus) && (
          <button
            onClick={openEditSip}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg hover:bg-slate-50 transition-colors"
          >
            <Pencil className="w-4 h-4" /> Edit SIP
          </button>
        )}
        {isSipOrder && isSipCancellable(liveTx.rawOrderStatus) && (
          <button
            onClick={() => setConfirmCancel(true)}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-red-600 text-white rounded-lg hover:bg-red-700 transition-colors"
          >
            <XCircle className="w-4 h-4" /> Cancel SIP
          </button>
        )}
        {normalizeOrderStatus(liveTx.rawOrderStatus) === 'RETRY_AVAILABLE' && (
          <button
            type="button"
            onClick={onCreateNewOrder}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors"
          >
            <RefreshCw className="w-4 h-4" /> Place New Order
          </button>
        )}
        {!isSipOrder && !redeemed && isRedeemableHolding(liveTx.rawOrderStatus, liveTx.type) && (
          <button
            type="button"
            onClick={redeemHolding}
            disabled={redeeming}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-amber-700 border border-amber-200 bg-white rounded-lg hover:bg-amber-50 transition-colors disabled:opacity-50"
          >
            <TrendingDown className="w-4 h-4" /> {redeeming ? 'Redeeming…' : 'Redeem'}
          </button>
        )}
        {redeemed && (
          <span className="flex items-center gap-1.5 text-sm font-semibold text-slate-400 px-2">
            <CheckCircle2 className="w-4 h-4" /> Redemption submitted
          </span>
        )}
        </div>
      </div>
      {cancelError && <div className="mb-5 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700">{cancelError}</div>}
      {redeemMsg && <div className="mb-5 flex items-start gap-2 rounded-xl border border-green-200 bg-green-50 px-4 py-3 text-sm font-medium text-green-700"><CheckCircle2 className="w-4 h-4 mt-0.5 flex-shrink-0" />{redeemMsg}</div>}
      {redeemError && <div className="mb-5 flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm font-medium text-red-700"><AlertCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />{redeemError}</div>}

      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 space-y-6">
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <h2 className="font-semibold text-slate-800 mb-5">Order Details</h2>
            <div className="grid grid-cols-2 gap-5">
              {[
                { label: 'Fund', value: tx.fund },
                { label: 'Transaction Type', value: tx.type },
                { label: 'Amount', value: tx.amount },
                { label: 'Mandate / Payment Mode', value: tx.mandate },
                { label: 'Initiated', value: tx.date },
                { label: 'Investor PAN', value: tx.pan },
              ].map(({ label, value }) => (
                <div key={label}>
                  <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{label}</p>
                  <p className="text-sm font-medium text-slate-700">{value}</p>
                </div>
              ))}
            </div>
          </div>

          {awaitingAction && (
            <div className="space-y-4">
              <InvestorActionLink
                orderId={liveTx.id}
                orderStatus={liveTx.rawOrderStatus}
                investorActionUrl={liveTx.investorActionUrl}
              />
              {actionUrl && (
                <button
                  type="button"
                  onClick={copyInvestorLink}
                  className="inline-flex items-center gap-2 rounded-lg border border-amber-200 bg-white px-4 py-2 text-xs font-semibold text-amber-800 transition-colors hover:bg-amber-50"
                >
                  {linkCopied ? (
                    <>
                      <CheckCircle2 className="h-3.5 w-3.5" /> Link copied
                    </>
                  ) : (
                    <>
                      <RefreshCw className="h-3.5 w-3.5" /> Copy investor link again
                    </>
                  )}
                </button>
              )}
            </div>
          )}

          {(normalizeOrderStatus(liveTx.rawOrderStatus) === 'FAILED') && (
            <div className="bg-red-50 rounded-2xl p-5 border border-red-200 flex gap-4">
              <XCircle className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" />
              <div>
                <p className="text-sm font-semibold text-red-800">Order failed</p>
                <p className="text-xs text-red-700 mt-1">
                  {liveTx.failureReason || 'The transaction could not be processed. Review investor readiness and place a new order.'}
                </p>
                <button
                  type="button"
                  onClick={onCreateNewOrder}
                  className="mt-3 px-4 py-2 bg-red-600 text-white text-xs font-semibold rounded-lg hover:bg-red-700 transition-colors"
                >
                  Create New Order
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Timeline */}
        <div className="bg-[#0B1B3E] rounded-2xl p-6 text-white h-fit">
          <h2 className="font-semibold mb-6">Order Status</h2>
          <StatusTimeline steps={ORDER_STATUS_STEPS} current={liveTx.rawOrderStatus} />
        </div>
      </div>
    </motion.div>
  );
}

function ConfirmModal({
  title,
  message,
  confirmLabel,
  disabled,
  onCancel,
  onConfirm,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  disabled?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl">
        <h2 className="text-lg font-semibold text-slate-800">{title}</h2>
        <p className="mt-2 text-sm text-slate-500">{message}</p>
        <div className="mt-6 flex justify-end gap-3">
          <button
            onClick={onCancel}
            disabled={disabled}
            className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-50"
          >
            Keep SIP
          </button>
          <button
            onClick={onConfirm}
            disabled={disabled}
            className="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700 disabled:opacity-50"
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
