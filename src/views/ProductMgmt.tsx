import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Search, Filter, Edit2, Trash2, X, ChevronDown, Lock, Rocket } from 'lucide-react';

interface Product {
  id: number;
  name: string;
  assetClass: 'MF' | 'SIF';
  category: string;
  return1y: string;
  minInvest: string;
  visibility: string;
  riskLevel: string;
  status: 'Active' | 'Inactive';
  amc: string;
}

const products: Product[] = [
  { id: 1, name: 'HDFC Large & Mid Cap Fund',   assetClass: 'MF',  category: 'Equity',   return1y: '+28.4%', minInvest: '₹100',       visibility: 'All Tiers',      riskLevel: 'Very High', status: 'Active',   amc: 'HDFC AMC'       },
  { id: 2, name: 'Parag Parikh Flexi Cap Fund', assetClass: 'MF',  category: 'Equity',   return1y: '+32.1%', minInvest: '₹1,000',     visibility: 'All Tiers',      riskLevel: 'Very High', status: 'Active',   amc: 'PPFAS AMC'      },
  { id: 3, name: 'ICICI Prudential Bluechip',   assetClass: 'MF',  category: 'Equity',   return1y: '+21.5%', minInvest: '₹100',       visibility: 'All Tiers',      riskLevel: 'High',      status: 'Active',   amc: 'ICICI Pru AMC'  },
  { id: 4, name: 'SBI Liquid Fund',             assetClass: 'MF',  category: 'Debt',     return1y: '+6.8%',  minInvest: '₹500',       visibility: 'All Tiers',      riskLevel: 'Low',       status: 'Active',   amc: 'SBI AMC'        },
  { id: 5, name: 'Quant Small Cap Fund',        assetClass: 'MF',  category: 'Equity',   return1y: '+45.2%', minInvest: '₹5,000',     visibility: 'Gold & Above',   riskLevel: 'Very High', status: 'Active',   amc: 'Quant AMC'      },
  { id: 6, name: 'Kotak Corporate Bond Fund',   assetClass: 'MF',  category: 'Debt',     return1y: '+7.2%',  minInvest: '₹500',       visibility: 'All Tiers',      riskLevel: 'Moderate',  status: 'Active',   amc: 'Kotak AMC'      },
  { id: 7, name: 'DSP ELSS Tax Saver Fund',     assetClass: 'MF',  category: 'ELSS',     return1y: '+24.8%', minInvest: '₹500',       visibility: 'All Tiers',      riskLevel: 'High',      status: 'Active',   amc: 'DSP AMC'        },
  { id: 8, name: 'Mirae Asset Hybrid Equity',   assetClass: 'MF',  category: 'Hybrid',   return1y: '+18.3%', minInvest: '₹1,000',     visibility: 'Silver & Above', riskLevel: 'High',      status: 'Active',   amc: 'Mirae AMC'      },
  { id: 9, name: 'Apex SIF Growth Fund',        assetClass: 'SIF', category: 'Strategic',return1y: '+18.5%', minInvest: '₹10,00,000', visibility: 'Platinum Only',  riskLevel: 'High',      status: 'Active',   amc: 'Apex Wealth'    },
  { id:10, name: 'Apex SIF Income Fund',        assetClass: 'SIF', category: 'Strategic',return1y: '+12.2%', minInvest: '₹5,00,000',  visibility: 'Gold & Above',   riskLevel: 'Moderate',  status: 'Active',   amc: 'Apex Wealth'    },
];

const futureClasses = [
  { code: 'PMS',       full: 'Portfolio Management Service', minInvest: '₹50L+',         eta: 'Q3 2025', grad: 'from-purple-50 to-violet-50', border: 'border-violet-200', accent: 'text-violet-700', dot: 'bg-violet-400' },
  { code: 'AIF',       full: 'Alternative Investment Fund',  minInvest: '₹1 Cr+',         eta: 'Q4 2025', grad: 'from-blue-50 to-sky-50',      border: 'border-blue-200',   accent: 'text-blue-700',   dot: 'bg-blue-400'   },
  { code: 'NPS',       full: 'National Pension System',      minInvest: '₹500/month',      eta: 'Q1 2026', grad: 'from-green-50 to-emerald-50', border: 'border-green-200',  accent: 'text-green-700',  dot: 'bg-green-400'  },
  { code: 'Bonds',     full: 'Corporate & Govt Bonds',       minInvest: '₹10,000+',        eta: 'Q1 2026', grad: 'from-amber-50 to-yellow-50',  border: 'border-amber-200',  accent: 'text-amber-700',  dot: 'bg-amber-400'  },
  { code: 'Insurance', full: 'Term & ULIP Products',         minInvest: 'Varies',          eta: 'Q2 2026', grad: 'from-rose-50 to-pink-50',    border: 'border-rose-200',   accent: 'text-rose-700',   dot: 'bg-rose-400'   },
];

const visibilityConfig: Record<string, string> = {
  'All Tiers':      'bg-slate-100 text-slate-600',
  'Silver & Above': 'bg-slate-100 text-slate-700',
  'Gold & Above':   'bg-amber-50 text-amber-700',
  'Platinum Only':  'bg-violet-50 text-violet-700',
};

const riskColor: Record<string, string> = {
  Low:       'text-green-600',
  Moderate:  'text-blue-600',
  High:      'text-orange-600',
  'Very High':'text-red-600',
};

const ASSET_CLASSES = ['All', 'MF', 'SIF'];
const CATEGORIES    = ['All', 'Equity', 'Debt', 'ELSS', 'Hybrid', 'Strategic'];

export default function ProductMgmt() {
  const [search,          setSearch]          = useState('');
  const [assetFilter,     setAssetFilter]     = useState('All');
  const [categoryFilter,  setCategoryFilter]  = useState('All');
  const [showFilters,     setShowFilters]     = useState(false);
  const [showAddModal,    setShowAddModal]     = useState(false);

  const filtered = products.filter(p => {
    const matchSearch   = p.name.toLowerCase().includes(search.toLowerCase()) || p.amc.toLowerCase().includes(search.toLowerCase());
    const matchAsset    = assetFilter    === 'All' || p.assetClass === assetFilter;
    const matchCategory = categoryFilter === 'All' || p.category   === categoryFilter;
    return matchSearch && matchAsset && matchCategory;
  });

  const mfCount  = products.filter(p => p.assetClass === 'MF').length;
  const sifCount = products.filter(p => p.assetClass === 'SIF').length;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Product Management</h1>
          <p className="text-slate-500 text-sm mt-1">Manage fund listings, visibility and tier access</p>
        </div>
        <button onClick={() => setShowAddModal(true)}
          className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors">
          + Add New Product
        </button>
      </div>

      {/* Asset class banner */}
      <div className="grid grid-cols-2 gap-4">
        {[
          { label: 'Mutual Funds (MF)', count: mfCount, sub: 'Equity · Debt · ELSS · Hybrid',  active: true,  color: 'from-blue-600 to-blue-700',    filter: 'MF'  },
          { label: 'Strategic Investment Funds (SIF)', count: sifCount, sub: 'Strategic products',active: true,color: 'from-violet-600 to-violet-700', filter: 'SIF' },
        ].map(item => (
          <button
            key={item.filter}
            onClick={() => setAssetFilter(assetFilter === item.filter ? 'All' : item.filter)}
            className={`bg-gradient-to-r ${item.color} text-white rounded-2xl p-5 text-left transition-all shadow-sm hover:shadow-md ${assetFilter === item.filter ? 'ring-2 ring-offset-2 ring-blue-400' : ''}`}
          >
            <div className="flex justify-between items-start">
              <div>
                <p className="text-sm font-semibold opacity-80">{item.label}</p>
                <p className="text-3xl font-bold mt-1">{item.count} <span className="text-base font-medium opacity-70">funds</span></p>
                <p className="text-xs opacity-60 mt-1">{item.sub}</p>
              </div>
              <span className="text-[10px] font-bold bg-white/20 px-2 py-0.5 rounded-full uppercase tracking-wider">Active</span>
            </div>
          </button>
        ))}
      </div>

      {/* Table card */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="p-4 border-b border-slate-100 flex flex-wrap gap-3 items-center">
          <div className="relative flex-1 min-w-[200px] max-w-sm">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input type="text" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search by fund name or AMC..."
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none" />
          </div>
          <button onClick={() => setShowFilters(p => !p)}
            className={`flex items-center gap-2 px-4 py-2 text-sm font-medium border rounded-lg transition-colors ${showFilters ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-50'}`}>
            <Filter className="w-4 h-4" /> Filter
            <ChevronDown className={`w-3.5 h-3.5 transition-transform ${showFilters ? 'rotate-180' : ''}`} />
          </button>
        </div>

        <AnimatePresence>
          {showFilters && (
            <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
              className="overflow-hidden border-b border-slate-100">
              <div className="p-4 bg-slate-50 flex flex-wrap gap-6">
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Asset Class</p>
                  <div className="flex gap-2">
                    {ASSET_CLASSES.map(c => (
                      <button key={c} onClick={() => setAssetFilter(c)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${assetFilter === c ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {c}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Category</p>
                  <div className="flex gap-2 flex-wrap">
                    {CATEGORIES.map(c => (
                      <button key={c} onClick={() => setCategoryFilter(c)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${categoryFilter === c ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {c}
                      </button>
                    ))}
                  </div>
                </div>
                {(assetFilter !== 'All' || categoryFilter !== 'All') && (
                  <button onClick={() => { setAssetFilter('All'); setCategoryFilter('All'); }}
                    className="self-end flex items-center gap-1 text-xs text-red-500 hover:text-red-700 font-semibold">
                    <X className="w-3 h-3" /> Clear
                  </button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        <div className="overflow-auto">
          <table className="w-full text-left">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 font-semibold sticky top-0 z-10">
              <tr>
                <th className="px-6 py-4">Product Name</th>
                <th className="px-6 py-4">Asset Class</th>
                <th className="px-6 py-4">Performance (1Y)</th>
                <th className="px-6 py-4">Min. Investment</th>
                <th className="px-6 py-4">Distributor Visibility</th>
                <th className="px-6 py-4">Risk</th>
                <th className="px-6 py-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filtered.map(p => (
                <tr key={p.id} className="group hover:bg-slate-50 transition-colors">
                  <td className="px-6 py-4">
                    <div className="font-semibold text-slate-800 max-w-[220px] truncate">{p.name}</div>
                    <div className="text-xs text-slate-400 mt-0.5">{p.amc} · {p.category}</div>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`px-2.5 py-1 text-xs font-bold rounded-md ${p.assetClass === 'MF' ? 'bg-blue-50 text-blue-700' : 'bg-violet-50 text-violet-700'}`}>
                      {p.assetClass}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    <span className={`font-mono font-semibold text-sm ${parseFloat(p.return1y) > 0 ? 'text-green-600' : 'text-red-500'}`}>
                      {p.return1y}
                    </span>
                  </td>
                  <td className="px-6 py-4 font-mono text-sm text-slate-700">{p.minInvest}</td>
                  <td className="px-6 py-4">
                    <span className={`px-2.5 py-1 text-xs font-semibold rounded-md flex items-center gap-1 w-fit ${visibilityConfig[p.visibility] ?? 'bg-slate-100 text-slate-600'}`}>
                      {p.visibility !== 'All Tiers' && <Lock className="w-3 h-3" />}
                      {p.visibility}
                    </span>
                  </td>
                  <td className={`px-6 py-4 text-xs font-semibold ${riskColor[p.riskLevel] ?? 'text-slate-500'}`}>{p.riskLevel}</td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex items-center gap-2 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                      <button className="p-1.5 text-blue-500 hover:bg-blue-50 rounded-lg transition-colors"><Edit2 className="w-4 h-4" /></button>
                      <button className="p-1.5 text-red-400 hover:bg-red-50 rounded-lg transition-colors"><Trash2 className="w-4 h-4" /></button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Future-ready section */}
      <div>
        <div className="flex items-center gap-3 mb-4">
          <Rocket className="w-5 h-5 text-slate-400" />
          <div>
            <h2 className="font-semibold text-slate-800">Upcoming Asset Classes</h2>
            <p className="text-xs text-slate-500">These product categories are on our roadmap and will be added in future releases</p>
          </div>
        </div>
        <div className="grid grid-cols-5 gap-4">
          {futureClasses.map(f => (
            <div key={f.code} className={`bg-gradient-to-br ${f.grad} rounded-2xl p-5 border ${f.border} relative overflow-hidden`}>
              <div className="absolute top-3 right-3">
                <span className="text-[9px] font-bold bg-white/60 text-slate-600 px-1.5 py-0.5 rounded-full uppercase tracking-wider">
                  {f.eta}
                </span>
              </div>
              <div className={`w-8 h-8 rounded-xl bg-white/50 flex items-center justify-center mb-3`}>
                <div className={`w-3 h-3 rounded-full ${f.dot}`} />
              </div>
              <p className={`text-lg font-bold ${f.accent}`}>{f.code}</p>
              <p className="text-xs text-slate-600 mt-0.5 leading-tight">{f.full}</p>
              <p className="text-[10px] text-slate-400 mt-2">Min: {f.minInvest}</p>
              <div className="mt-3 flex items-center gap-1">
                <Lock className={`w-3 h-3 ${f.accent}`} />
                <span className={`text-[10px] font-bold ${f.accent}`}>Coming Soon</span>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Add Product Modal */}
      <AnimatePresence>
        {showAddModal && <AddProductModal onClose={() => setShowAddModal(false)} />}
      </AnimatePresence>
    </motion.div>
  );
}

function AddProductModal({ onClose }: { onClose: () => void }) {
  const [assetClass, setAssetClass] = useState<'MF' | 'SIF'>('MF');

  const mfCategories  = ['Equity', 'Debt', 'ELSS', 'Hybrid', 'Liquid'];
  const sifCategories = ['Strategic Growth', 'Strategic Income'];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <motion.div initial={{ opacity: 0, scale: 0.95, y: 20 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white w-full max-w-lg rounded-3xl shadow-xl relative z-10 overflow-hidden max-h-[90vh] overflow-y-auto">
        <button onClick={onClose} className="absolute top-4 right-4 p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors z-10">
          <X className="w-5 h-5" />
        </button>
        <div className="p-8">
          <h2 className="text-xl font-semibold text-slate-800 mb-1">Add New Product</h2>
          <p className="text-sm text-slate-500 mb-6">Add a fund to the product catalog for distributors</p>

          <div className="space-y-5">
            {/* Asset Class toggle */}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-2">Asset Class</label>
              <div className="flex bg-slate-100 p-1 rounded-xl gap-1">
                {(['MF', 'SIF'] as const).map(c => (
                  <button key={c} onClick={() => setAssetClass(c)}
                    className={`flex-1 py-2 text-sm font-semibold rounded-lg transition-all ${assetClass === c ? 'bg-white shadow-sm text-slate-900' : 'text-slate-500'}`}>
                    {c === 'MF' ? 'Mutual Fund' : 'Strategic Investment Fund (SIF)'}
                  </button>
                ))}
              </div>
              {/* Future classes hint */}
              <div className="mt-2 flex flex-wrap gap-1.5">
                {['PMS', 'AIF', 'NPS', 'Bonds', 'Insurance'].map(fc => (
                  <span key={fc} className="px-2 py-0.5 text-[10px] font-semibold bg-slate-100 text-slate-400 rounded flex items-center gap-1 cursor-not-allowed">
                    <Lock className="w-2.5 h-2.5" /> {fc}
                  </span>
                ))}
              </div>
              <p className="text-[10px] text-slate-400 mt-1">More asset classes coming soon</p>
            </div>

            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Fund Name</label>
              <input type="text" placeholder="e.g. HDFC Large & Mid Cap Fund"
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Category</label>
                <select className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                  {(assetClass === 'MF' ? mfCategories : sifCategories).map(c => <option key={c}>{c}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Risk Level</label>
                <select className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                  {['Low', 'Moderate', 'High', 'Very High'].map(r => <option key={r}>{r}</option>)}
                </select>
              </div>
            </div>

            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">AMC / Fund House</label>
              <input type="text" placeholder="e.g. HDFC AMC"
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">1Y Return (%)</label>
                <div className="relative">
                  <input type="text" placeholder="e.g. 28.4"
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
                  <span className="absolute right-3 top-2.5 text-slate-400 text-sm">%</span>
                </div>
              </div>
              <div>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Min. Investment (₹)</label>
                <div className="relative">
                  <span className="absolute left-4 top-2.5 text-slate-500 text-sm">₹</span>
                  <input type="number" placeholder="100"
                    className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-8 pr-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
                </div>
              </div>
            </div>

            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Distributor Visibility</label>
              <select className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                {['All Tiers', 'Silver & Above', 'Gold & Above', 'Platinum Only'].map(v => <option key={v}>{v}</option>)}
              </select>
              <p className="text-xs text-slate-400 mt-1">Controls which tier of distributors can see and trade this product</p>
            </div>
          </div>

          <div className="mt-8 flex gap-3">
            <button onClick={onClose} className="px-5 py-2.5 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">Cancel</button>
            <button onClick={onClose} className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors">Add Product</button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
