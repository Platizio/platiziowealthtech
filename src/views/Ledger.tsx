import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Info, CheckCircle2, ChevronLeft, ChevronRight, X, Search, RefreshCw, AlertCircle } from 'lucide-react';
import { apiFetch } from '../config/api';

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
  const [page, setPage]                 = useState(1);
  const [investModal, setInvestModal]   = useState<any | null>(null);
  const refreshInFlightRef = useRef(false);

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
    const lastSync = Number(window.localStorage.getItem(LIVE_SCHEME_SYNC_KEY) || 0);
    return !lastSync || Date.now() - lastSync >= LIVE_SCHEME_SYNC_INTERVAL_MS;
  };

  const fetchSchemes = async (isRefresh = false) => {
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
  }, []);

  useEffect(() => {
    setPage(1);
  }, [assetFilter, categoryFilter, typeFilter, search]);

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
    const searchTerm = search.trim().toLowerCase();
    const matchSearch   = !searchTerm
      || name.toLowerCase().includes(searchTerm)
      || amc.toLowerCase().includes(searchTerm)
      || code.toLowerCase().includes(searchTerm);
    const matchAsset    = assetFilter    === 'All' || getAssetClass(s) === assetFilter;
    const matchCategory = categoryFilter === 'All' || s.category   === categoryFilter;
    const matchType     = typeFilter      === 'All' || s.productType === typeFilter;
    return matchSearch && matchAsset && matchCategory && matchType;
  });

  const filtersActive = assetFilter !== 'All' || categoryFilter !== 'All' || typeFilter !== 'All' || search.trim() !== '';
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
          <button
            onClick={() => fetchSchemes(true)}
            disabled={refreshing}
            className="flex items-center gap-2 px-3 py-2 text-xs font-semibold bg-white border border-slate-200 text-slate-600 rounded-lg hover:bg-slate-50 transition-colors disabled:opacity-50"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} />
            {refreshing ? 'Syncing...' : 'Sync from Cybrilla'}
          </button>
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
              : 'No cached products found. Sync once from Cybrilla to populate the catalog.'}
          </p>
          <button
            onClick={() => (filtersActive ? clearFilters() : fetchSchemes(true))}
            disabled={!filtersActive && refreshing}
            className="mt-5 flex items-center gap-2 px-4 py-2 text-sm font-semibold bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors disabled:opacity-50"
          >
            {filtersActive ? (
              <X className="w-4 h-4" />
            ) : (
              <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
            )}
            {filtersActive ? 'Clear filters' : refreshing ? 'Syncing...' : 'Sync from Cybrilla'}
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
                <div className="mt-auto pb-12">
                  <p className="text-[10px] uppercase font-bold text-slate-400 mb-1">Scheme Code</p>
                  <p className="font-mono text-xs font-medium text-slate-600">{scheme.externalSchemeCode || '—'}</p>
                </div>

                {/* CTA — slides up on hover */}
                <div className="absolute -bottom-16 left-0 right-0 p-4 transition-transform duration-300 group-hover:-translate-y-16">
                  <button
                    onClick={() => setInvestModal(scheme)}
                    className="w-full py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-lg hover:bg-[#1A3066] transition-colors"
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

      {/* Transaction modal */}
      <AnimatePresence>
        {investModal && (
          <TransactionModal fund={investModal} userData={userData} onClose={() => setInvestModal(null)} />
        )}
      </AnimatePresence>
    </motion.div>
  );
}

// ─── Transaction Modal ────────────────────────────────────────────────────────
function TransactionModal({ fund, userData, onClose }: { fund: any; userData?: any; onClose: () => void }) {
  const [step, setStep] = useState(1);
  const [type, setType] = useState('SIP');
  const [investorQuery, setInvestorQuery] = useState('');
  const [investorResults, setInvestorResults] = useState<any[]>([]);
  const [selectedInvestor, setSelectedInvestor] = useState<any | null>(null);
  const [investorLoading, setInvestorLoading] = useState(false);

  useEffect(() => {
    let cancelled = false;

    const searchInvestors = async () => {
      const query = investorQuery.trim();
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

    const timer = window.setTimeout(searchInvestors, 300);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [investorQuery, userData]);

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
