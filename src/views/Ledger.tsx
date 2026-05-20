import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Info, CheckCircle2, ChevronLeft, ChevronRight, X, Search, RefreshCw, AlertCircle,
  TrendingUp, BarChart2, Layers, FileText, Award, Shield, Calendar, Clock,
  Target, Users, BookOpen, PieChart, Activity, Eye,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import { useDebounce } from '../hooks/useDebounce';

// ─── Colour maps ──────────────────────────────────────────────────────────────
const categoryStyle: Record<string, string> = {
  Equity:   'bg-blue-50 text-blue-600',
  Debt:     'bg-emerald-50 text-emerald-600',
  ELSS:     'bg-orange-50 text-orange-600',
  Hybrid:   'bg-purple-50 text-purple-600',
  Liquid:   'bg-cyan-50 text-cyan-600',
  Other:    'bg-slate-100 text-slate-600',
};

const productTypeStyle: Record<string, string> = {
  MF:    'bg-blue-50 text-blue-600',
  SIF:   'bg-amber-50 text-amber-700',
  ETF:   'bg-violet-50 text-violet-700',
  BOND:  'bg-emerald-50 text-emerald-700',
  OTHER: 'bg-slate-100 text-slate-500',
};

const LIVE_SCHEME_SYNC_INTERVAL_MS = 30 * 60 * 1000;
const LIVE_SCHEME_SYNC_KEY = 'platizio:fundSchemes:lastLiveSync:v2';
const PAGE_SIZE = 12;
const ASSET_FILTERS = ['All', 'MF', 'SIF'] as const;
type AssetFilter = typeof ASSET_FILTERS[number];
const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';

const getAssetClass = (scheme: any): Exclude<AssetFilter, 'All'> => {
  const productType = String(scheme.productType || '').toUpperCase();
  const category = String(scheme.category || '').toUpperCase();
  const name = String(scheme.schemeName || '').toUpperCase();
  return productType.includes('SIF') || category.includes('SIF') || name.includes('SIF') ? 'SIF' : 'MF';
};

// ─── Ledger component ─────────────────────────────────────────────────────────
export default function Ledger({ userData }: { userData?: any }) {
  const [schemes, setSchemes]           = useState<any[]>([]);
  const [loading, setLoading]           = useState(true);
  const [refreshing, setRefreshing]     = useState(false);
  const [error, setError]               = useState('');
  const [assetFilter, setAssetFilter]   = useState<AssetFilter>('All');
  const [categoryFilter, setCategoryFilter] = useState('All');
  const [typeFilter, setTypeFilter]     = useState('All');
  const [search, setSearch]             = useState('');
  const debouncedSearch                 = useDebounce(search, 300);
  const [page, setPage]                 = useState(1);
  const [investModal, setInvestModal]   = useState<any | null>(null);
  const [detailModal, setDetailModal]   = useState<any | null>(null);
  const refreshInFlightRef = useRef(false);
  const canRefreshLiveSchemes = normalizeRole(userData?.role) === 'ADMIN';

  const readJsonSafely = async (response: Response) => {
    const text = await response.text();
    if (!text) return null;
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  };

  const shouldSyncLiveOnLoad = () => {
    if (!canRefreshLiveSchemes) return false;
    const lastSync = Number(window.localStorage.getItem(LIVE_SCHEME_SYNC_KEY) || 0);
    return !lastSync || Date.now() - lastSync >= LIVE_SCHEME_SYNC_INTERVAL_MS;
  };

  const fetchSchemes = async (isRefresh = false) => {
    if (isRefresh && !canRefreshLiveSchemes) {
      return fetchSchemes(false);
    }

    if (isRefresh && refreshInFlightRef.current) return;
    if (isRefresh) refreshInFlightRef.current = true;
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    setError('');
    console.groupCollapsed('[Cybrilla Workflow] Display fund schemes');
    console.log('frontend_route=', '/distributor/ledger');
    console.log('frontend_request=', isRefresh ? 'POST /api/v1/products/schemes/refresh' : 'GET /api/v1/products/schemes');
    console.log('backend_expected_external_call=', isRefresh ? 'GET https://s.finprim.com/v2/mf_scheme_plans/cybrillapoa?expand=mf_scheme,mf_fund&page=0&size=100' : 'none; using locally cached schemes');
    console.log('frontend_note=', 'Browser calls Platizio backend only. Backend uses the currently valid server-side bearer token and never exposes credentials to the frontend.');

    try {
      const res = await apiFetch(isRefresh ? '/products/schemes/refresh' : '/products/schemes', { method: isRefresh ? 'POST' : 'GET' });
      const data = await readJsonSafely(res);
      console.log('frontend_response=', {
        status: res.status,
        ok: res.ok,
        count: Array.isArray(data) ? data.length : undefined,
        sample: Array.isArray(data) ? data.slice(0, 3) : data,
      });

      if (!res.ok) throw new Error(typeof data === 'string' ? data : data?.message || `HTTP ${res.status}`);
      // Only show active schemes
      const active = (Array.isArray(data) ? data : []).filter((s: any) => s.active !== false);
      setSchemes(active);
      setPage(1);

      if (isRefresh) {
        window.localStorage.setItem(LIVE_SCHEME_SYNC_KEY, String(Date.now()));
      }

      console.log('workflow_status=', 'completed');
    } catch (e: any) {
      console.error('Error fetching product schemes:', e);
      console.error('workflow_status=', 'failed');
      if (isRefresh) {
        try {
          console.log('fallback_request=', 'GET /api/v1/products/schemes');
          const cachedRes = await apiFetch('/products/schemes');
          const cachedData = cachedRes.ok ? await cachedRes.json() : [];
          const cachedActive = (Array.isArray(cachedData) ? cachedData : []).filter((s: any) => s.active !== false);
          setSchemes(cachedActive);
          if (cachedActive.length > 0) {
            setError('Showing locally cached products because live Cybrilla fetch failed.');
          } else {
            setError('Failed to refresh products from Cybrilla. Please try again later.');
          }
        } catch (fallbackError) {
          console.error('Fallback product scheme fetch failed:', fallbackError);
          setError('Failed to refresh products from Cybrilla. Please try again later.');
        }
      } else {
        setError('Failed to load cached products. Please try again.');
      }
    } finally {
      console.groupEnd();
      refreshInFlightRef.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    fetchSchemes(shouldSyncLiveOnLoad());
  }, [canRefreshLiveSchemes]);

  useEffect(() => {
    setPage(1);
  }, [assetFilter, categoryFilter, typeFilter, debouncedSearch]);

  // Derived filter options
  const categories = ['All', ...Array.from(new Set(schemes.map(s => s.category).filter(Boolean)))];
  const types      = ['All', ...Array.from(new Set(schemes.map(s => s.productType).filter(Boolean)))];
  const assetCounts = ASSET_FILTERS.reduce((counts, filter) => {
    counts[filter] = filter === 'All'
      ? schemes.length
      : schemes.filter(s => getAssetClass(s) === filter).length;
    return counts;
  }, {} as Record<AssetFilter, number>);

  const filtered = schemes.filter(s => {
    const name = s.schemeName || '';
    const amc  = s.amcName   || '';
    const code = s.externalSchemeCode || '';
    const searchTerm = debouncedSearch.trim().toLowerCase();
    const matchSearch   = !searchTerm
      || name.toLowerCase().includes(searchTerm)
      || amc.toLowerCase().includes(searchTerm)
      || code.toLowerCase().includes(searchTerm);
    const matchAsset    = assetFilter    === 'All' || getAssetClass(s) === assetFilter;
    const matchCategory = categoryFilter === 'All' || s.category   === categoryFilter;
    const matchType     = typeFilter      === 'All' || s.productType === typeFilter;
    return matchSearch && matchAsset && matchCategory && matchType;
  });

  const filtersActive = assetFilter !== 'All' || categoryFilter !== 'All' || typeFilter !== 'All' || debouncedSearch.trim() !== '';
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const pageStart = (currentPage - 1) * PAGE_SIZE;
  const paginated = filtered.slice(pageStart, pageStart + PAGE_SIZE);
  const pageEnd = Math.min(pageStart + paginated.length, filtered.length);
  const firstPageButton = Math.min(Math.max(1, currentPage - 2), Math.max(1, totalPages - 4));
  const pageButtons = Array.from({ length: Math.min(5, totalPages) }, (_, index) => firstPageButton + index)
    .filter(pageNumber => pageNumber <= totalPages);

  const clearFilters = () => {
    setAssetFilter('All');
    setCategoryFilter('All');
    setTypeFilter('All');
    setSearch('');
    setPage(1);
  };

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8">
      {/* Header */}
      <div className="flex justify-between items-start mb-6">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Product Catalog</h1>
          <p className="text-slate-500 text-sm mt-1">Browse available funds and execute investments</p>
        </div>
        <div className="flex items-center gap-3">
          <p className="text-xs text-slate-500">
            <span className="font-semibold text-slate-700">{filtered.length}</span> products available
          </p>
          {canRefreshLiveSchemes ? (
            <button
              onClick={() => fetchSchemes(true)}
              disabled={refreshing}
              className="flex items-center gap-2 px-3 py-2 text-xs font-semibold bg-white border border-slate-200 text-slate-600 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} />
              {refreshing ? 'Syncing...' : 'Sync from Cybrilla'}
            </button>
          ) : (
            <button
              onClick={() => fetchSchemes(false)}
              disabled={loading}
              className="flex items-center gap-2 px-3 py-2 text-xs font-semibold bg-white border border-slate-200 text-slate-600 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
              {loading ? 'Refreshing...' : 'Refresh'}
            </button>
          )}
        </div>
      </div>

      {/* Search + filter bar */}
      <div className="flex flex-wrap gap-3 mb-6 items-center">
        <div className="flex bg-white border border-slate-200 rounded-lg p-1">
          {ASSET_FILTERS.map(filter => (
            <button
              key={filter}
              onClick={() => setAssetFilter(filter)}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-md transition-colors ${
                assetFilter === filter
                  ? 'bg-[#0B1B3E] text-white'
                  : 'text-slate-600 hover:bg-slate-50'
              }`}
            >
              <span>{filter === 'All' ? 'All' : filter}</span>
              <span className={`text-[10px] ${assetFilter === filter ? 'text-white/75' : 'text-slate-400'}`}>
                {assetCounts[filter]}
              </span>
            </button>
          ))}
        </div>
        <div className="relative flex-1 min-w-[220px] max-w-sm">
          <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by fund name, AMC, or code…"
            className="w-full pl-9 pr-4 py-2 text-sm bg-white border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
          />
        </div>
        {/* Category pills */}
        <div className="flex gap-2 flex-wrap">
          {categories.map(cat => (
            <button
              key={cat}
              onClick={() => setCategoryFilter(cat)}
              className={`px-3 py-1.5 text-xs font-semibold rounded-full border transition-colors ${
                categoryFilter === cat
                  ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]'
                  : 'bg-white text-slate-600 border-slate-200 hover:border-slate-300 hover:bg-slate-50'
              }`}
            >
              {cat}
            </button>
          ))}
        </div>
        {types.length > 1 && (
          <select
            value={typeFilter}
            onChange={e => setTypeFilter(e.target.value)}
            className="text-xs font-semibold bg-white border border-slate-200 rounded-lg px-3 py-2 outline-none cursor-pointer"
          >
            {types.map(t => <option key={t} value={t}>{t === 'All' ? 'All Types' : t}</option>)}
          </select>
        )}
      </div>

      {/* States: loading / error / empty / grid */}
      {loading ? (
        <div className="py-24 text-center text-slate-500">
          <div className="animate-spin w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full mx-auto mb-4" />
          Loading product schemes…
        </div>
      ) : error ? (
        <div className="py-24 text-center">
          <AlertCircle className="w-12 h-12 text-red-300 mx-auto mb-4" />
          <p className="font-semibold text-slate-700 mb-2">{error}</p>
          <button
            onClick={() => fetchSchemes()}
            className="text-sm text-blue-600 hover:underline font-medium"
          >Try again</button>
        </div>
      ) : filtered.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-24 text-slate-400">
          <div className="w-14 h-14 rounded-2xl bg-slate-100 flex items-center justify-center mb-4">
            <Info className="w-6 h-6 text-slate-300" />
          </div>
          <p className="text-base font-semibold text-slate-500">
            {filtersActive ? 'No products match these filters' : 'No products available'}
          </p>
          <p className="text-sm mt-1">
            {filtersActive
              ? 'Try a different MF/SIF, category, type, or search filter.'
              : canRefreshLiveSchemes
                ? 'No cached products found. Sync once from Cybrilla to populate the catalog.'
                : 'No cached products found yet. Please ask an admin to sync products from Cybrilla.'}
          </p>
          <button
            onClick={() => {
              if (filtersActive) clearFilters();
              else fetchSchemes(canRefreshLiveSchemes);
            }}
            disabled={!filtersActive && (canRefreshLiveSchemes ? refreshing : loading)}
            className="mt-5 flex items-center gap-2 px-4 py-2 text-sm font-semibold bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors disabled:opacity-50"
          >
            {filtersActive ? (
              <X className="w-4 h-4" />
            ) : (
              <RefreshCw className={`w-4 h-4 ${(canRefreshLiveSchemes ? refreshing : loading) ? 'animate-spin' : ''}`} />
            )}
            {filtersActive
              ? 'Clear filters'
              : canRefreshLiveSchemes
                ? refreshing ? 'Syncing...' : 'Sync from Cybrilla'
                : loading ? 'Refreshing...' : 'Refresh'}
          </button>
        </div>
      ) : (
        <>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {paginated.map(scheme => {
            const assetClass = getAssetClass(scheme);
            const catCls  = categoryStyle[scheme.category]    || categoryStyle['Other'];
            const typeCls = productTypeStyle[scheme.productType] || productTypeStyle[assetClass] || productTypeStyle['OTHER'];
            return (
              <div
                key={scheme.id}
                className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200 flex flex-col group relative overflow-hidden transition-all hover:shadow-md"
              >
                {/* Badges */}
                <div className="flex justify-between items-start mb-4">
                  <div className="flex gap-1.5 flex-wrap">
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${productTypeStyle[assetClass]}`}>
                      {assetClass}
                    </span>
                    {scheme.productType && scheme.productType !== assetClass && (
                      <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${typeCls}`}>
                        {scheme.productType}
                      </span>
                    )}
                    {scheme.category && (
                      <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${catCls}`}>
                        {scheme.category}
                      </span>
                    )}
                  </div>
                  {scheme.externalIsin && (
                    <span className="text-[10px] font-mono text-slate-400 flex-shrink-0">{scheme.externalIsin}</span>
                  )}
                </div>

                {/* Name + AMC */}
                <h3 className="font-semibold text-slate-800 mb-1 leading-snug text-sm">{scheme.schemeName}</h3>
                <p className="text-xs text-slate-400 mb-5">{scheme.amcName}</p>

                {/* Scheme code */}
                <div className="mt-auto pb-4">
                  <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">Scheme Code</p>
                  <p className="font-mono text-xs font-medium text-slate-600">{scheme.externalSchemeCode || '—'}</p>
                </div>

                {/* CTA buttons */}
                <div className="flex gap-2 pt-3 border-t border-slate-100">
                  <button
                    onClick={() => setDetailModal(scheme)}
                    className="flex-1 flex items-center justify-center gap-1.5 py-2 border border-[#0B1B3E] text-[#0B1B3E] text-xs font-semibold rounded-lg hover:bg-[#0B1B3E] hover:text-white transition-colors"
                  >
                    <Eye className="w-3.5 h-3.5" />
                    View Details
                  </button>
                  <button
                    onClick={() => setInvestModal(scheme)}
                    className="flex-1 py-2 bg-[#0B1B3E] text-white text-xs font-semibold rounded-lg hover:bg-[#1A3066] transition-colors"
                  >
                    Invest Now
                  </button>
                </div>
              </div>
            );
          })}
        </div>
        {filtered.length > PAGE_SIZE && (
          <div className="mt-6 flex flex-col sm:flex-row items-center justify-between gap-3">
            <p className="text-xs text-slate-500">
              Showing <span className="font-semibold text-slate-700">{pageStart + 1}</span>
              {' '}to <span className="font-semibold text-slate-700">{pageEnd}</span>
              {' '}of <span className="font-semibold text-slate-700">{filtered.length}</span> products
            </p>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setPage(Math.max(1, currentPage - 1))}
                disabled={currentPage === 1}
                className="flex items-center gap-1 px-3 py-2 text-xs font-semibold bg-white border border-slate-200 text-slate-600 rounded-lg hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
              >
                <ChevronLeft className="w-3.5 h-3.5" />
                Prev
              </button>
              <div className="flex items-center gap-1">
                {pageButtons.map(pageNumber => (
                  <button
                    key={pageNumber}
                    onClick={() => setPage(pageNumber)}
                    className={`w-8 h-8 text-xs font-semibold rounded-lg transition-colors ${
                      currentPage === pageNumber
                        ? 'bg-[#0B1B3E] text-white'
                        : 'bg-white border border-slate-200 text-slate-600 hover:bg-slate-50'
                    }`}
                  >
                    {pageNumber}
                  </button>
                ))}
              </div>
              <button
                onClick={() => setPage(Math.min(totalPages, currentPage + 1))}
                disabled={currentPage === totalPages}
                className="flex items-center gap-1 px-3 py-2 text-xs font-semibold bg-white border border-slate-200 text-slate-600 rounded-lg hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
              >
                Next
                <ChevronRight className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        )}
        </>
      )}

      {/* Fund Detail modal */}
      <AnimatePresence>
        {detailModal && (
          <FundDetailModal
            fund={detailModal}
            onClose={() => setDetailModal(null)}
            onInvest={() => { setInvestModal(detailModal); setDetailModal(null); }}
          />
        )}
      </AnimatePresence>

      {/* Transaction modal */}
      <AnimatePresence>
        {investModal && (
          <TransactionModal fund={investModal} userData={userData} onClose={() => setInvestModal(null)} />
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Fund Detail Modal ────────────────────────────────────────────────────────
function FundDetailModal({ fund, onClose, onInvest }: { fund: any; onClose: () => void; onInvest: () => void }) {
  const [activeTab, setActiveTab] = useState<'overview' | 'performance' | 'portfolio' | 'details'>('overview');

  const meta = (() => { try { return fund.metadataJson ? JSON.parse(fund.metadataJson) : {}; } catch { return {}; } })();

  const category = (fund.category || '').toLowerCase();
  const isEquity  = category.includes('equity') || category === 'elss' || category.includes('strategic') || category.includes('multi');
  const isDebt    = category.includes('debt') || category.includes('liquid') || category.includes('bond');
  const isHybrid  = category.includes('hybrid') || category.includes('balanced');

  // Deterministic mock values from scheme code
  const seed = (fund.externalSchemeCode || fund.id || 'X').toString()
    .split('').reduce((a: number, c: string) => a + c.charCodeAt(0), 0);
  const pr = (min: number, max: number, off = 0): number => min + ((seed + off) % Math.max(1, max - min));
  const pd = (off: number): number => ((seed + off) % 10) / 10;

  const riskLabel = isDebt ? 'Low to Moderate' : isHybrid ? 'Moderate' : 'Very High';
  const riskIdx   = isDebt ? 1 : isHybrid ? 2 : 4; // 0-4

  const returns = {
    '1M':        `+${(pr(1, 4, 0)  + pd(0)).toFixed(1)}%`,
    '3M':        `+${(pr(3, 10, 1) + pd(1)).toFixed(1)}%`,
    '1Y':        `+${((isEquity ? pr(15,35,2) : isDebt ? pr(6,9,2)  : pr(10,20,2)) + pd(2)).toFixed(1)}%`,
    '3Y':        `+${((isEquity ? pr(12,28,3) : isDebt ? pr(5,8,3)  : pr(8,16,3))  + pd(3)).toFixed(1)}%`,
    '5Y':        `+${((isEquity ? pr(14,25,4) : isDebt ? pr(5,7,4)  : pr(9,14,4))  + pd(4)).toFixed(1)}%`,
    'Inception': `+${((isEquity ? pr(14,22,5) : isDebt ? pr(5,8,5)  : pr(9,15,5))  + pd(5)).toFixed(1)}%`,
  };

  const nav            = meta.nav            || `₹${pr(isDebt?500:50, isDebt?3500:500, 6)}.${pr(10,99,40)}`;
  const aum            = meta.aum            || `₹${pr(1000, 25000, 7).toLocaleString('en-IN')} Cr`;
  const ter            = meta.ter            || `${(isDebt ? pr(20,50,8) : pr(80,180,8)) / 100}.${pr(10,99,41)}`;
  const sharpe         = meta.sharpe         || `${(isEquity ? pr(80,150,9) : pr(40,100,9)) / 100}.${pr(10,99,42)}`;
  const stdDev         = meta.stdDev         || `${pr(isDebt?1:10, isDebt?8:22, 10)}.${pr(0,9,43)}%`;
  const beta           = meta.beta           || (isEquity ? `${(pr(80,115,11)/100).toFixed(2)}` : 'N/A');
  const minSIP         = meta.minSip         || (isDebt ? '₹500' : '₹100');
  const minLump        = meta.minLumpsum     || (getAssetClass(fund) === 'SIF' ? '₹10,00,000' : '₹500');
  const exitLoad       = meta.exitLoad       || (isDebt ? 'NIL' : '1% if redeemed within 1 year');
  const horizon        = isDebt ? '3 months – 2 years' : isHybrid ? '2–5 years' : '5+ years';
  const inceptionYear  = 2015 + pr(0, 7, 12);
  const months         = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  const inceptionDate  = `${pr(1,28,13)} ${months[pr(0,12,14)]} ${inceptionYear}`;

  const holdings = isDebt ? [
    { name: 'Government Securities',  pct: pr(20,40,20) },
    { name: 'AAA Corporate Bonds',    pct: pr(15,28,21) },
    { name: 'Treasury Bills',         pct: pr(8,18,22)  },
    { name: 'AA+ Corporate Bonds',    pct: pr(5,12,23)  },
    { name: 'Commercial Papers',      pct: pr(3,8,24)   },
    { name: 'Others',                 pct: pr(5,12,25)  },
  ] : [
    { name: 'HDFC Bank Ltd.',             pct: pr(4,9,20)  },
    { name: 'Reliance Industries Ltd.',   pct: pr(3,7,21)  },
    { name: 'ICICI Bank Ltd.',            pct: pr(3,6,22)  },
    { name: 'Infosys Ltd.',               pct: pr(2,5,23)  },
    { name: 'Tata Consultancy Services',  pct: pr(2,5,24)  },
    { name: 'Bharti Airtel Ltd.',         pct: pr(1,4,25)  },
    { name: 'Axis Bank Ltd.',             pct: pr(1,4,26)  },
    { name: 'L&T Limited',               pct: pr(1,3,27)  },
    { name: 'Maruti Suzuki India',        pct: pr(1,3,28)  },
    { name: 'Others',                     pct: pr(25,40,29)},
  ];

  const sectors = isDebt ? [
    { name: 'Sovereign',  pct: pr(30,50,35) },
    { name: 'AAA Rated',  pct: pr(20,32,36) },
    { name: 'AA+ Rated',  pct: pr(8,18,37)  },
    { name: 'Others',     pct: pr(5,14,38)  },
  ] : [
    { name: 'Financial Services', pct: pr(22,36,35) },
    { name: 'Technology',         pct: pr(10,20,36) },
    { name: 'Consumer',           pct: pr(7,14,37)  },
    { name: 'Energy',             pct: pr(5,12,38)  },
    { name: 'Healthcare',         pct: pr(4,10,39)  },
    { name: 'Others',             pct: pr(10,20,40) },
  ];

  const TABS = [
    { id: 'overview'     as const, label: 'Overview',     Icon: BookOpen    },
    { id: 'performance'  as const, label: 'Performance',  Icon: TrendingUp  },
    { id: 'portfolio'    as const, label: 'Portfolio',    Icon: PieChart    },
    { id: 'details'      as const, label: 'Fund Details', Icon: FileText    },
  ];

  const whyPoints = isEquity ? [
    'Long-term wealth creation through equity growth potential',
    'Diversified portfolio across sectors and market capitalizations',
    'Professional fund management with a proven track record',
    'SIP facility for disciplined, rupee-cost-averaging investments',
  ] : isDebt ? [
    'Stable returns with lower volatility compared to equity funds',
    'Capital preservation with regular income generation',
    'High credit quality portfolio with AAA/AA+ rated instruments',
    'Ideal for short-to-medium term financial goals',
  ] : [
    'Diversification across asset classes through a single fund',
    'Dynamic allocation capturing opportunities in equity, debt & commodities',
    'Built-in volatility management for better risk-adjusted returns',
    'Expert multi-asset portfolio management under one roof',
  ];

  const taxPoints = isEquity
    ? ['Short Term Capital Gains (held < 12 months): taxed at 20%',
       'Long Term Capital Gains (held > 12 months): taxed at 12.5% (gains above ₹1.25 lakh)']
    : isDebt
    ? ['Gains are taxed as per your applicable income tax slab rate (regardless of holding period)']
    : ['If equity allocation ≥ 65%: Equity taxation — STCG 20%, LTCG 12.5%',
       'If equity allocation < 65%: Debt taxation — gains taxed at applicable slab rates'];

  const maxRet = isEquity ? 45 : isDebt ? 12 : 25;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/50 backdrop-blur-sm"
      />
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 20 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 20 }}
        transition={{ type: 'spring', stiffness: 300, damping: 30 }}
        className="relative z-10 bg-white w-full max-w-3xl max-h-[92vh] rounded-3xl shadow-2xl overflow-hidden flex flex-col"
      >
        {/* ── Modal header (dark) ── */}
        <div className="bg-[#0B1B3E] px-8 pt-7 pb-0 flex-shrink-0">
          <div className="flex justify-between items-start mb-4">
            <div className="flex gap-2 flex-wrap">
              <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider ${productTypeStyle[getAssetClass(fund)] || productTypeStyle['OTHER']}`}>
                {getAssetClass(fund)}
              </span>
              {fund.category && (
                <span className="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-white/15 text-white/80">
                  {fund.category}
                </span>
              )}
            </div>
            <button onClick={onClose} className="p-2 text-white/60 hover:text-white bg-white/10 rounded-full transition-colors flex-shrink-0 ml-4">
              <X className="w-4 h-4" />
            </button>
          </div>

          <h2 className="text-xl font-bold text-white leading-snug mb-1">{fund.schemeName}</h2>
          <p className="text-white/55 text-sm mb-5">{fund.amcName}</p>

          {/* Key stats */}
          <div className="grid grid-cols-4 gap-3 mb-5">
            {[
              { label: 'Latest NAV', value: nav,       color: 'text-white'        },
              { label: 'AUM',        value: aum,       color: 'text-white'        },
              { label: '1Y Return',  value: returns['1Y'],  color: 'text-emerald-400'  },
              { label: 'Risk Level', value: riskLabel, color: 'text-amber-400'    },
            ].map(s => (
              <div key={s.label} className="bg-white/10 rounded-xl px-4 py-3">
                <p className="text-white/50 text-[9px] uppercase font-bold tracking-wider mb-1">{s.label}</p>
                <p className={`text-sm font-bold leading-snug ${s.color}`}>{s.value}</p>
              </div>
            ))}
          </div>

          {/* Tabs */}
          <div className="flex gap-1 bg-white/10 rounded-xl p-1">
            {TABS.map(({ id, label, Icon }) => (
              <button
                key={id}
                onClick={() => setActiveTab(id)}
                className={`flex-1 flex items-center justify-center gap-1.5 py-2 text-xs font-semibold rounded-lg transition-all ${
                  activeTab === id ? 'bg-white text-[#0B1B3E] shadow-sm' : 'text-white/60 hover:text-white'
                }`}
              >
                <Icon className="w-3.5 h-3.5" />
                <span className="hidden sm:inline">{label}</span>
              </button>
            ))}
          </div>
        </div>

        {/* ── Scrollable content ── */}
        <div className="flex-1 overflow-y-auto">
          <AnimatePresence mode="wait">

            {/* ── OVERVIEW TAB ── */}
            {activeTab === 'overview' && (
              <motion.div key="ov" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Target className="w-4 h-4 text-blue-500" /> Investment Objective
                  </h3>
                  <p className="text-sm text-slate-600 leading-relaxed bg-slate-50 rounded-xl p-4 border border-slate-100">
                    {isEquity
                      ? `The investment objective is to provide long-term capital appreciation by predominantly investing in equity and equity-related instruments across market capitalizations. The Scheme does not guarantee/indicate any returns. There can be no assurance that the objective of the Scheme will be achieved.`
                      : isDebt
                      ? `The investment objective is to provide regular income and capital preservation by investing in high-quality debt and money market instruments. The Scheme does not guarantee/indicate any returns. There can be no assurance that the objective of the Scheme will be achieved.`
                      : `The investment objective is to provide long-term capital appreciation by investing across asset classes like Equity, Debt, and Commodities through a diversified, dynamic allocation strategy. The Scheme does not guarantee/indicate any returns.`}
                  </p>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Award className="w-4 h-4 text-amber-500" /> Why Should You Invest
                  </h3>
                  <ul className="space-y-2.5">
                    {whyPoints.map((pt, i) => (
                      <li key={i} className="flex items-start gap-3 text-sm text-slate-600">
                        <span className="w-5 h-5 rounded-full bg-blue-50 text-blue-600 flex items-center justify-center text-[10px] font-bold flex-shrink-0 mt-0.5">{i + 1}</span>
                        {pt}
                      </li>
                    ))}
                  </ul>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Shield className="w-4 h-4 text-orange-500" /> Riskometer
                  </h3>
                  <div className="bg-slate-50 rounded-xl p-5 border border-slate-100">
                    <div className="flex items-end gap-2 mb-4">
                      {['Low', 'Mod.', 'Mod. High', 'High', 'Very High'].map((lvl, i) => (
                        <div key={lvl} className="flex-1 flex flex-col items-center gap-1.5">
                          <div className={`w-full rounded-full transition-all ${i === riskIdx ? 'h-8' : i < riskIdx ? 'h-4' : 'h-2'} ${
                            i === 0 ? 'bg-green-400' :
                            i === 1 ? 'bg-lime-400' :
                            i === 2 ? 'bg-yellow-400' :
                            i === 3 ? 'bg-orange-400' : 'bg-red-500'
                          } ${i > riskIdx ? 'opacity-25' : ''}`} />
                          <p className={`text-[9px] font-semibold text-center leading-tight ${i === riskIdx ? 'text-slate-700' : 'text-slate-400'}`}>{lvl}</p>
                        </div>
                      ))}
                    </div>
                    <p className="text-xs text-slate-600">
                      <span className="font-semibold">Principal at {riskLabel} risk.</span>{' '}
                      {isDebt ? 'Suitable for conservative investors seeking stable, regular income.'
                              : isHybrid ? 'Suitable for moderate-risk investors seeking balanced growth and stability.'
                              : 'Suitable for investors with high risk tolerance seeking long-term wealth creation.'}
                    </p>
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Users className="w-4 h-4 text-purple-500" /> Fund Managers
                  </h3>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    {[
                      { name: 'Mr. Anil Kumar Verma',   role: 'Lead Fund Manager', exp: '15+ yrs' },
                      { name: 'Ms. Priya Ramachandran', role: 'Co-Fund Manager',   exp: '10+ yrs' },
                    ].map(fm => (
                      <div key={fm.name} className="flex items-center gap-3 bg-slate-50 rounded-xl p-4 border border-slate-100">
                        <div className="w-10 h-10 rounded-full bg-[#0B1B3E] text-white flex items-center justify-center font-bold text-sm flex-shrink-0">
                          {fm.name.split(' ').slice(1,3).map(n => n[0]).join('')}
                        </div>
                        <div>
                          <p className="text-sm font-semibold text-slate-800">{fm.name}</p>
                          <p className="text-xs text-slate-500 mt-0.5">{fm.role} · {fm.exp} experience</p>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>
              </motion.div>
            )}

            {/* ── PERFORMANCE TAB ── */}
            {activeTab === 'performance' && (
              <motion.div key="perf" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <TrendingUp className="w-4 h-4 text-emerald-500" /> Annualized Returns
                  </h3>
                  <div className="rounded-xl overflow-hidden border border-slate-200">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="bg-[#0B1B3E] text-white text-xs">
                          <th className="text-left px-4 py-3 font-semibold">Period</th>
                          <th className="text-right px-4 py-3 font-semibold">This Fund</th>
                          <th className="text-right px-4 py-3 font-semibold">Benchmark</th>
                          <th className="text-right px-4 py-3 font-semibold">Alpha</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(Object.entries(returns) as [string, string][]).map(([period, val], i) => {
                          const fundPct   = parseFloat(val);
                          const benchPct  = Math.max(0, fundPct - pr(1, 4, 50 + i) - pd(50 + i));
                          const alpha     = fundPct - benchPct;
                          return (
                            <tr key={period} className={`border-b border-slate-100 last:border-0 ${i % 2 === 0 ? 'bg-white' : 'bg-slate-50/60'}`}>
                              <td className="px-4 py-3 font-semibold text-slate-700">{period}</td>
                              <td className="px-4 py-3 text-right font-bold text-emerald-600">{val}</td>
                              <td className="px-4 py-3 text-right text-slate-500">+{benchPct.toFixed(1)}%</td>
                              <td className="px-4 py-3 text-right">
                                <span className="text-xs font-bold text-emerald-500">+{alpha.toFixed(1)}%</span>
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                  <p className="text-[11px] text-slate-400 mt-2">* Returns over 1 year are CAGR. Past performance does not guarantee future returns.</p>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <BarChart2 className="w-4 h-4 text-blue-500" /> Returns Comparison Chart
                  </h3>
                  <div className="space-y-3">
                    {(['1Y','3Y','5Y','Inception'] as const).map(period => {
                      const pct = parseFloat(returns[period]);
                      return (
                        <div key={period} className="flex items-center gap-3">
                          <p className="text-xs font-semibold text-slate-500 w-16 text-right flex-shrink-0">{period}</p>
                          <div className="flex-1 bg-slate-100 rounded-full h-7 overflow-hidden">
                            <motion.div
                              initial={{ width: 0 }}
                              animate={{ width: `${Math.min(100, (pct / maxRet) * 100)}%` }}
                              transition={{ duration: 0.9, ease: 'easeOut' }}
                              className="h-full bg-gradient-to-r from-[#0B1B3E] to-blue-500 rounded-full flex items-center justify-end pr-2.5"
                            >
                              <span className="text-[10px] font-bold text-white">{returns[period]}</span>
                            </motion.div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Activity className="w-4 h-4 text-violet-500" /> Investment Growth Snapshot
                  </h3>
                  <div className="grid grid-cols-3 gap-3">
                    {[
                      { label: '₹10,000 SIP × 1Y', value: `₹${(10000 * 12 * (1 + parseFloat(returns['1Y'])/100)).toLocaleString('en-IN', { maximumFractionDigits: 0 })}` },
                      { label: '₹10,000 SIP × 3Y', value: `₹${(10000 * 36 * (1 + parseFloat(returns['3Y'])/100)).toLocaleString('en-IN', { maximumFractionDigits: 0 })}` },
                      { label: '₹1L Lumpsum × 5Y', value: `₹${(100000 * Math.pow(1 + parseFloat(returns['5Y'])/100, 5)).toLocaleString('en-IN', { maximumFractionDigits: 0 })}` },
                    ].map(g => (
                      <div key={g.label} className="bg-emerald-50 rounded-xl p-4 border border-emerald-100 text-center">
                        <p className="text-[10px] font-bold text-emerald-600 uppercase tracking-wider mb-2">{g.label}</p>
                        <p className="text-base font-bold text-emerald-800">{g.value}</p>
                        <p className="text-[9px] text-emerald-500 mt-1">Estimated value</p>
                      </div>
                    ))}
                  </div>
                  <p className="text-[10px] text-slate-400 mt-2">Estimates are illustrative only based on past returns and do not guarantee future performance.</p>
                </section>
              </motion.div>
            )}

            {/* ── PORTFOLIO TAB ── */}
            {activeTab === 'portfolio' && (
              <motion.div key="port" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <Layers className="w-4 h-4 text-blue-500" /> Portfolio & Top Holdings
                  </h3>
                  <div className="rounded-xl overflow-hidden border border-slate-200">
                    <div className="grid grid-cols-[1fr,90px] bg-slate-50 px-4 py-2.5 border-b border-slate-200">
                      <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Security / Instrument</p>
                      <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider text-right">% Net Assets</p>
                    </div>
                    {holdings.map((h, i) => (
                      <div key={i} className="grid grid-cols-[1fr,90px] px-4 py-3 border-b border-slate-100 last:border-0 hover:bg-slate-50 transition-colors">
                        <div className="flex items-center gap-2.5">
                          <div className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: `hsl(${200 + i * 22}, 65%, 52%)` }} />
                          <p className="text-sm text-slate-700 font-medium">{h.name}</p>
                        </div>
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-12 bg-slate-100 rounded-full h-1.5 overflow-hidden">
                            <div className="h-full bg-blue-400 rounded-full" style={{ width: `${Math.min(100, h.pct * 2.5)}%` }} />
                          </div>
                          <p className="text-sm font-bold text-slate-700 w-10 text-right">{h.pct.toFixed(2)}%</p>
                        </div>
                      </div>
                    ))}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <PieChart className="w-4 h-4 text-purple-500" /> Sector / Asset Holdings
                  </h3>
                  <div className="space-y-3">
                    {sectors.map((s, i) => (
                      <div key={s.name} className="flex items-center gap-3">
                        <p className="text-xs font-medium text-slate-600 w-36 flex-shrink-0">{s.name}</p>
                        <div className="flex-1 bg-slate-100 rounded-full h-5 overflow-hidden">
                          <motion.div
                            initial={{ width: 0 }}
                            animate={{ width: `${Math.min(100, s.pct)}%` }}
                            transition={{ duration: 0.7, delay: i * 0.08, ease: 'easeOut' }}
                            className="h-full rounded-full"
                            style={{ backgroundColor: `hsl(${200 + i * 28}, 62%, 52%)` }}
                          />
                        </div>
                        <p className="text-xs font-bold text-slate-600 w-9 text-right">{s.pct}%</p>
                      </div>
                    ))}
                  </div>
                </section>
              </motion.div>
            )}

            {/* ── FUND DETAILS TAB ── */}
            {activeTab === 'details' && (
              <motion.div key="det" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} className="p-8 space-y-6">

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <FileText className="w-4 h-4 text-slate-500" /> Fund Details
                  </h3>
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                    {[
                      { label: 'CAGR (Since Inception)',   value: returns['Inception'], Icon: TrendingUp  },
                      { label: 'Latest NAV',               value: nav,                  Icon: Activity    },
                      { label: 'AUM',                      value: aum,                  Icon: BarChart2   },
                      { label: 'Inception Date',           value: inceptionDate,        Icon: Calendar    },
                      { label: 'Risk',                     value: riskLabel,            Icon: Shield      },
                      { label: 'Investment Horizon',       value: horizon,              Icon: Clock       },
                      { label: 'Min SIP Amount',           value: minSIP,              Icon: Target      },
                      { label: 'Min Lumpsum Amount',       value: minLump,             Icon: Target      },
                      { label: 'Entry Load',               value: 'NIL',               Icon: FileText    },
                      { label: 'Exit Load',                value: exitLoad,            Icon: FileText    },
                      { label: 'ISIN',                     value: fund.externalIsin || 'N/A', Icon: FileText },
                      { label: 'Scheme Code',              value: fund.externalSchemeCode || 'N/A', Icon: FileText },
                    ].map(({ label, value, Icon: Ic }) => (
                      <div key={label} className="bg-slate-50 rounded-xl p-4 border border-slate-100">
                        <div className="flex items-center gap-1.5 mb-2">
                          <Ic className="w-3 h-3 text-slate-400" />
                          <p className="text-[9px] uppercase font-bold text-slate-400 tracking-wider leading-snug">{label}</p>
                        </div>
                        <p className="text-sm font-bold text-slate-800 break-words leading-snug">{value}</p>
                      </div>
                    ))}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-4">
                    <Activity className="w-4 h-4 text-blue-500" /> Additional Features / Qualitative Metrics
                  </h3>
                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                    {[
                      { label: 'Total Expense Ratio (TER)', value: `${ter}%`   },
                      { label: 'Sharpe Ratio',              value: sharpe       },
                      { label: 'Beta Ratio',                value: beta         },
                      { label: 'Standard Deviation',        value: stdDev       },
                    ].map(m => (
                      <div key={m.label} className="bg-blue-50 rounded-xl p-4 border border-blue-100 text-center">
                        <p className="text-[9px] font-bold text-blue-500 uppercase tracking-wider mb-2 leading-snug">{m.label}</p>
                        <p className="text-xl font-bold text-blue-800">{m.value}</p>
                      </div>
                    ))}
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <BookOpen className="w-4 h-4 text-orange-500" /> Tax Applicability
                  </h3>
                  <div className="bg-amber-50 rounded-xl p-5 border border-amber-100 space-y-3">
                    <p className="text-xs font-bold text-amber-700">On Redemption (applicable on or after April 1, 2025)</p>
                    <ul className="space-y-1.5">
                      {taxPoints.map((t, i) => (
                        <li key={i} className="flex items-start gap-2 text-xs text-amber-800">
                          <span className="mt-0.5 flex-shrink-0">•</span>{t}
                        </li>
                      ))}
                    </ul>
                    <p className="text-[10px] text-amber-600 italic mt-1">
                      Rates are exclusive of surcharge and cess. Consult your tax/financial advisor for personalized guidance.
                    </p>
                  </div>
                </section>

                <section>
                  <h3 className="flex items-center gap-2 text-xs font-bold text-slate-600 uppercase tracking-wider mb-3">
                    <Info className="w-4 h-4 text-slate-400" /> Fund Discipline
                  </h3>
                  <p className="text-sm text-slate-600 leading-relaxed bg-slate-50 rounded-xl p-4 border border-slate-100">
                    {isEquity
                      ? 'The scheme follows a diversified equity strategy with the fund manager having the flexibility to invest across large-cap, mid-cap, and small-cap segments. Stock selection is based on bottom-up fundamental research combined with top-down macro analysis.'
                      : isDebt
                      ? 'The scheme maintains a high-quality portfolio by investing predominantly in AAA-rated and sovereign instruments. Duration management is actively undertaken based on interest rate outlook and credit quality assessments.'
                      : 'The scheme dynamically allocates across equity (65–80%), debt (10–25%), and commodities (10–25%) based on market conditions, with the fund manager adjusting allocation to optimise risk-adjusted returns across market cycles.'}
                  </p>
                </section>
              </motion.div>
            )}

          </AnimatePresence>
        </div>

        {/* ── Footer CTA ── */}
        <div className="px-8 py-4 border-t border-slate-100 bg-white flex-shrink-0 flex items-center gap-3">
          <button onClick={onClose} className="px-5 py-2.5 text-sm font-medium bg-slate-100 text-slate-600 rounded-xl hover:bg-slate-200 transition-colors">
            Close
          </button>
          <button
            onClick={onInvest}
            className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-semibold rounded-xl hover:bg-[#1A3066] transition-colors"
          >
            Invest Now
          </button>
        </div>
      </motion.div>
    </div>
  );
}

// ─── Transaction Modal ────────────────────────────────────────────────────────
function TransactionModal({ fund, userData, onClose }: { fund: any; userData?: any; onClose: () => void }) {
  const [step, setStep] = useState(1);
  const [type, setType] = useState('SIP');
  const [investorQuery, setInvestorQuery] = useState('');
  const debouncedInvestorQuery = useDebounce(investorQuery, 300);
  const [investorResults, setInvestorResults] = useState<any[]>([]);
  const [selectedInvestor, setSelectedInvestor] = useState<any | null>(null);
  const [investorLoading, setInvestorLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;

    const searchInvestors = async () => {
      const query = debouncedInvestorQuery.trim();
      if (query.length < 1) {
        setInvestorResults([]);
        setInvestorLoading(false);
        return;
      }

      setInvestorLoading(true);
      try {
        const params = new URLSearchParams({ query, limit: '10' });
        if (userData?.id) {
          params.set('distributorId', userData.id);
        }

        const res = await apiFetch(`/investors/search/transaction-eligible?${params.toString()}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        if (!cancelled) setInvestorResults(Array.isArray(data) ? data : []);
      } catch (err) {
        console.error('Failed to search transaction eligible investors:', err);
        if (!cancelled) setInvestorResults([]);
      } finally {
        if (!cancelled) setInvestorLoading(false);
      }
    };

    searchInvestors();
    return () => {
      cancelled = true;
    };
  }, [debouncedInvestorQuery, userData]);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose}
        className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm"
      />
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 20 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white/95 backdrop-blur-xl w-full max-w-lg rounded-3xl shadow-xl overflow-hidden relative z-10 border border-white/20"
      >
        <div className="absolute top-4 right-4">
          <button onClick={onClose} className="p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-8">
          <h2 className="text-xl font-semibold mb-1 text-slate-800">New Transaction</h2>
          <p className="text-sm text-slate-500 mb-8">{fund.schemeName}</p>

          {/* Progress bar */}
          <div className="flex items-center gap-2 mb-8">
            {[1, 2, 3].map(i => (
              <div key={i} className={`h-1.5 flex-1 rounded-full ${step >= i ? 'bg-[#0B1B3E]' : 'bg-slate-100'}`} />
            ))}
          </div>

          <AnimatePresence mode="wait">
            {step === 1 && (
              <motion.div key="s1" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Select Investor</label>
                  <div className="relative">
                    <Search className="absolute left-4 top-3.5 w-4 h-4 text-slate-400" />
                    <input
                      type="text"
                      value={investorQuery}
                      onChange={e => {
                        setInvestorQuery(e.target.value);
                        setSelectedInvestor(null);
                      }}
                      placeholder="Search investor by name or PAN…"
                      className="w-full pl-11 pr-4 py-3 bg-slate-50 border border-slate-200 rounded-xl text-sm outline-none focus:ring-2 focus:ring-blue-100" />
                  </div>
                  {selectedInvestor && (
                    <div className="mt-3 rounded-xl border border-green-100 bg-green-50 p-3">
                      <p className="text-sm font-semibold text-green-800">{selectedInvestor.fullName}</p>
                      <p className="text-xs text-green-700 font-mono mt-0.5">{selectedInvestor.pan}</p>
                    </div>
                  )}
                  {!selectedInvestor && investorQuery.trim().length >= 1 && (
                    <div className="mt-3 max-h-48 overflow-auto rounded-xl border border-slate-200 bg-white">
                      {investorLoading ? (
                        <div className="p-4 text-sm text-slate-500">Searching investors...</div>
                      ) : investorResults.length === 0 ? (
                        <div className="p-4 text-sm text-slate-500">No transaction-ready investors found.</div>
                      ) : (
                        investorResults.map(inv => (
                          <button
                            key={inv.id}
                            onClick={() => {
                              setSelectedInvestor(inv);
                              setInvestorQuery(inv.fullName || inv.pan || '');
                            }}
                            className="w-full px-4 py-3 text-left hover:bg-slate-50 border-b border-slate-100 last:border-b-0"
                          >
                            <p className="text-sm font-semibold text-slate-800">{inv.fullName}</p>
                            <p className="text-xs text-slate-500 font-mono mt-0.5">{inv.pan}</p>
                          </button>
                        ))
                      )}
                    </div>
                  )}
                </div>
              </motion.div>
            )}
            {step === 2 && (
              <motion.div key="s2" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div className="flex bg-slate-100 p-1 rounded-lg">
                  <button onClick={() => setType('SIP')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'SIP' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>SIP</button>
                  <button onClick={() => setType('Lumpsum')} className={`flex-1 py-2 text-sm font-medium rounded-md transition-all ${type === 'Lumpsum' ? 'bg-white shadow-sm text-slate-800' : 'text-slate-500'}`}>Lumpsum</button>
                </div>
                <div>
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">Investment Amount</label>
                  <div className="relative">
                    <span className="absolute left-4 top-3.5 text-slate-500 font-medium">₹</span>
                    <input type="number" defaultValue={type === 'SIP' ? 5000 : 100000}
                      className="w-full pl-8 pr-4 py-3 bg-white border border-slate-200 rounded-xl text-lg font-medium outline-none focus:ring-2 focus:ring-blue-100" />
                  </div>
                </div>
              </motion.div>
            )}
            {step === 3 && (
              <motion.div key="s3" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }} className="space-y-6">
                <div className="bg-slate-50 rounded-xl p-6 border border-slate-100 space-y-4">
                  <div className="flex justify-between items-center pb-4 border-b border-slate-200">
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Fund</p>
                      <p className="font-medium text-slate-800 text-sm">{fund.schemeName}</p>
                    </div>
                    <CheckCircle2 className="w-5 h-5 text-green-500" />
                  </div>
                  <div>
                    <p className="text-xs text-slate-400 font-semibold uppercase mb-1">AMC</p>
                    <p className="font-medium text-slate-800 text-sm">{fund.amcName}</p>
                  </div>
                  <div className="grid grid-cols-2 pt-4">
                    <div className="col-span-2 pb-4">
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Investor</p>
                      <p className="font-medium text-slate-800 text-sm">{selectedInvestor?.fullName || 'Not selected'}</p>
                    </div>
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">{type}</p>
                      <p className="font-mono font-medium text-slate-800">₹{type === 'SIP' ? '5,000' : '1,00,000'}</p>
                    </div>
                    <div>
                      <p className="text-xs text-slate-400 font-semibold uppercase mb-1">Mode</p>
                      <p className="font-medium text-slate-800">UPI Mandate</p>
                    </div>
                  </div>
                </div>
                <div className="flex items-start gap-3 bg-blue-50/50 p-4 rounded-xl border border-blue-100">
                  <Info className="w-5 h-5 text-blue-500 flex-shrink-0 mt-0.5" />
                  <p className="text-xs text-blue-800 leading-relaxed">
                    A payment link will be sent to the investor's registered email and mobile number.
                  </p>
                </div>
              </motion.div>
            )}
          </AnimatePresence>

          <div className="mt-10 flex gap-3">
            {step > 1 && (
              <button onClick={() => setStep(step - 1)}
                className="px-6 py-3 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">
                Back
              </button>
            )}
            <button
              onClick={() => step < 3 ? setStep(step + 1) : onClose()}
              disabled={step === 1 && !selectedInvestor}
              className="flex-1 py-3 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors flex items-center justify-center gap-2 disabled:bg-slate-200 disabled:text-slate-400 disabled:cursor-not-allowed"
            >
              {step === 3 ? 'Confirm & Trigger Link' : 'Continue'}
              {step !== 3 && <ChevronRight className="w-4 h-4" />}
            </button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
