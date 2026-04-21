import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Search, Filter, Upload, UserPlus, X, CheckCircle2, Clock, XCircle, AlertCircle, ChevronDown, Download } from 'lucide-react';

interface Investor {
  id: number;
  name: string;
  distributor: string;
  productClasses: string[];
  invested: string;
  kyc: 'Verified' | 'Pending' | 'In Progress' | 'Failed';
  pan: string;
}

const investors: Investor[] = [
  { id:  1, name: 'Aditya Sharma',       distributor: 'Direct (Master)',    productClasses: ['MF', 'SIF'], invested: '₹1.2 Cr',  kyc: 'Verified',    pan: 'ABCDE1234F' },
  { id:  2, name: 'Meera Iyer',          distributor: 'Rahul Distributors', productClasses: ['MF'],        invested: '₹45 L',    kyc: 'Pending',     pan: 'FGHIJ5678K' },
  { id:  3, name: 'Rahul Verma',         distributor: 'WealthEdge Advisory',productClasses: ['MF'],        invested: '₹15 Cr',   kyc: 'Verified',    pan: 'KLMNO9012P' },
  { id:  4, name: 'Sunita Kapur',        distributor: 'Direct (Master)',    productClasses: ['MF'],        invested: '₹12 L',    kyc: 'Verified',    pan: 'QRSTU3456V' },
  { id:  5, name: 'Tech Innovations PF', distributor: 'ProFunds India',     productClasses: ['MF', 'SIF'], invested: '₹42 Cr',   kyc: 'Verified',    pan: 'WXYZ7890AB' },
  { id:  6, name: 'Priya Nair',          distributor: 'Apex Partners',      productClasses: ['MF'],        invested: '₹8 L',     kyc: 'In Progress', pan: 'CDEFG1234H' },
  { id:  7, name: 'Vikram Singh',        distributor: 'FinTree Wealth',     productClasses: ['MF'],        invested: '₹3.5 L',   kyc: 'Failed',      pan: 'IJKLM5678N' },
  { id:  8, name: 'Anjali Desai',        distributor: 'Rahul Distributors', productClasses: ['MF'],        invested: '₹22 L',    kyc: 'Verified',    pan: 'OPQRS9012T' },
  { id:  9, name: 'Mohit Gupta',         distributor: 'WealthEdge Advisory',productClasses: ['MF', 'SIF'], invested: '₹1.8 Cr',  kyc: 'Verified',    pan: 'UVWXY3456Z' },
  { id: 10, name: 'Nisha Patel',         distributor: 'Direct (Master)',    productClasses: ['MF'],        invested: '₹5 L',     kyc: 'Pending',     pan: 'ABCDE6789F' },
  { id: 11, name: 'Rajesh Kumar',        distributor: 'ProFunds India',     productClasses: ['MF'],        invested: '₹28 L',    kyc: 'Verified',    pan: 'GHIJK2345L' },
  { id: 12, name: 'Prakash Mehta',       distributor: 'MoneyGrow',          productClasses: ['MF'],        invested: '₹2.1 L',   kyc: 'Failed',      pan: 'MNOPQ6789R' },
];

const kycConfig: Record<string, { color: string; bg: string; icon: React.ReactNode }> = {
  Verified:    { color: 'text-green-700', bg: 'bg-green-50',  icon: <CheckCircle2 className="w-3.5 h-3.5" /> },
  Pending:     { color: 'text-amber-700', bg: 'bg-amber-50',  icon: <Clock        className="w-3.5 h-3.5" /> },
  'In Progress':{ color: 'text-blue-700', bg: 'bg-blue-50',   icon: <Clock        className="w-3.5 h-3.5" /> },
  Failed:      { color: 'text-red-700',   bg: 'bg-red-50',    icon: <XCircle      className="w-3.5 h-3.5" /> },
};

const DISTRIBUTORS = ['All', 'Direct (Master)', 'Rahul Distributors', 'WealthEdge Advisory', 'ProFunds India', 'Apex Partners', 'FinTree Wealth', 'MoneyGrow'];
const KYC_STATUSES = ['All', 'Verified', 'Pending', 'In Progress', 'Failed'];

export default function InvestorMgmt() {
  const [search,         setSearch]         = useState('');
  const [kycFilter,      setKycFilter]      = useState('All');
  const [distFilter,     setDistFilter]     = useState('All');
  const [showFilters,    setShowFilters]    = useState(false);
  const [addModal,       setAddModal]       = useState<'manual' | 'csv' | null>(null);

  const filtered = investors.filter(inv => {
    const matchSearch = inv.name.toLowerCase().includes(search.toLowerCase()) ||
                        inv.pan.toLowerCase().includes(search.toLowerCase()) ||
                        inv.distributor.toLowerCase().includes(search.toLowerCase());
    const matchKyc  = kycFilter  === 'All' || inv.kyc         === kycFilter;
    const matchDist = distFilter === 'All' || inv.distributor  === distFilter;
    return matchSearch && matchKyc && matchDist;
  });

  // KYC stats
  const total       = investors.length;
  const verified    = investors.filter(i => i.kyc === 'Verified').length;
  const pending     = investors.filter(i => i.kyc === 'Pending').length;
  const inProgress  = investors.filter(i => i.kyc === 'In Progress').length;
  const failed      = investors.filter(i => i.kyc === 'Failed').length;
  const verifiedPct = Math.round((verified / total) * 100);
  const actionNeeded = pending + failed;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 space-y-6">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Investor Management</h1>
          <p className="text-slate-500 text-sm mt-1">{total} investors across all distributors</p>
        </div>
        <div className="flex gap-3">
          <button onClick={() => setAddModal('csv')}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Upload className="w-4 h-4" /> Upload CSV
          </button>
          <button onClick={() => setAddModal('manual')}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors">
            <UserPlus className="w-4 h-4" /> Onboard Investor
          </button>
        </div>
      </div>

      {/* KYC Status Banner */}
      <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
        <div className="flex justify-between items-start mb-5">
          <div>
            <h2 className="font-semibold text-slate-800">KYC Completion Status</h2>
            <p className="text-xs text-slate-500 mt-0.5">
              {actionNeeded} investor{actionNeeded !== 1 ? 's' : ''} require immediate action
            </p>
          </div>
          {actionNeeded > 0 && (
            <div className="flex items-center gap-1.5 px-3 py-1.5 bg-amber-50 border border-amber-200 rounded-lg">
              <AlertCircle className="w-4 h-4 text-amber-600" />
              <span className="text-xs font-semibold text-amber-700">{actionNeeded} Action Required</span>
            </div>
          )}
        </div>

        {/* Progress bar */}
        <div className="mb-4">
          <div className="flex justify-between text-xs font-medium text-slate-600 mb-2">
            <span>{verified} Verified ({verifiedPct}%)</span>
            <span className="text-slate-400">{total} Total</span>
          </div>
          <div className="h-3 bg-slate-100 rounded-full overflow-hidden flex">
            <div className="h-full bg-green-500 transition-all" style={{ width: `${(verified / total) * 100}%` }} />
            <div className="h-full bg-blue-400 transition-all"  style={{ width: `${(inProgress / total) * 100}%` }} />
            <div className="h-full bg-amber-400 transition-all" style={{ width: `${(pending / total) * 100}%` }} />
            <div className="h-full bg-red-400 transition-all"   style={{ width: `${(failed / total) * 100}%` }} />
          </div>
        </div>

        {/* Stat pills */}
        <div className="grid grid-cols-4 gap-4">
          {[
            { label: 'Verified',     count: verified,   pct: Math.round((verified   / total) * 100), color: 'bg-green-50 border-green-200 text-green-700' },
            { label: 'In Progress',  count: inProgress, pct: Math.round((inProgress / total) * 100), color: 'bg-blue-50  border-blue-200  text-blue-700'  },
            { label: 'Pending',      count: pending,    pct: Math.round((pending    / total) * 100), color: 'bg-amber-50 border-amber-200 text-amber-700' },
            { label: 'Failed',       count: failed,     pct: Math.round((failed     / total) * 100), color: 'bg-red-50   border-red-200   text-red-700'   },
          ].map(s => (
            <div key={s.label} className={`rounded-xl p-4 border ${s.color}`}>
              <p className="text-2xl font-bold">{s.count}</p>
              <p className="text-xs font-semibold mt-0.5">{s.label}</p>
              <p className="text-xs opacity-70 mt-1">{s.pct}% of total</p>
            </div>
          ))}
        </div>
      </div>

      {/* Table card */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="p-4 border-b border-slate-100 flex flex-wrap gap-3 items-center">
          <div className="relative flex-1 min-w-[200px] max-w-sm">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input type="text" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search by name, PAN or distributor..."
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
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">KYC Status</p>
                  <div className="flex gap-2 flex-wrap">
                    {KYC_STATUSES.map(s => (
                      <button key={s} onClick={() => setKycFilter(s)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${kycFilter === s ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {s}
                      </button>
                    ))}
                  </div>
                </div>
                <div>
                  <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-2">Distributor</p>
                  <div className="flex gap-2 flex-wrap">
                    {DISTRIBUTORS.slice(0, 5).map(d => (
                      <button key={d} onClick={() => setDistFilter(d)}
                        className={`px-3 py-1 text-xs font-semibold rounded-md border transition-colors ${distFilter === d ? 'bg-[#0B1B3E] text-white border-[#0B1B3E]' : 'bg-white border-slate-200 text-slate-600 hover:bg-slate-100'}`}>
                        {d === 'Direct (Master)' ? 'Direct' : d.split(' ')[0]}
                      </button>
                    ))}
                  </div>
                </div>
                {(kycFilter !== 'All' || distFilter !== 'All') && (
                  <button onClick={() => { setKycFilter('All'); setDistFilter('All'); }}
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
                <th className="px-6 py-4">Investor Name</th>
                <th className="px-6 py-4">Distributor</th>
                <th className="px-6 py-4">Product Classes</th>
                <th className="px-6 py-4">Invested Amount</th>
                <th className="px-6 py-4">KYC Status</th>
                <th className="px-6 py-4 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {filtered.map(inv => {
                const kc = kycConfig[inv.kyc];
                return (
                  <tr key={inv.id} className="group hover:bg-slate-50 transition-colors cursor-pointer">
                    <td className="px-6 py-4">
                      <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{inv.name}</div>
                      <div className="text-xs text-slate-400 font-mono mt-0.5">{inv.pan}</div>
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-600">{inv.distributor}</td>
                    <td className="px-6 py-4">
                      <div className="flex gap-1.5 flex-wrap">
                        {inv.productClasses.map(pc => (
                          <span key={pc} className={`px-2 py-0.5 text-[10px] font-bold rounded ${pc === 'MF' ? 'bg-blue-50 text-blue-700' : 'bg-violet-50 text-violet-700'}`}>
                            {pc}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="px-6 py-4 font-mono font-semibold text-slate-800">{inv.invested}</td>
                    <td className="px-6 py-4">
                      <span className={`flex items-center gap-1.5 text-xs font-semibold px-2.5 py-1 rounded-md w-fit ${kc.bg} ${kc.color}`}>
                        {kc.icon} {inv.kyc}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center gap-2 justify-end opacity-0 group-hover:opacity-100 transition-opacity">
                        <button className="px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors">View</button>
                        {(inv.kyc === 'Pending' || inv.kyc === 'Failed') && (
                          <button className="px-3 py-1.5 text-xs font-semibold text-amber-600 border border-amber-200 rounded-lg hover:bg-amber-50 transition-colors">
                            Re-KYC
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modals */}
      <AnimatePresence>
        {addModal === 'manual' && <ManualOnboardModal onClose={() => setAddModal(null)} />}
        {addModal === 'csv'    && <CsvUploadModal     onClose={() => setAddModal(null)} />}
      </AnimatePresence>
    </motion.div>
  );
}

function ManualOnboardModal({ onClose }: { onClose: () => void }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <motion.div initial={{ opacity: 0, scale: 0.95, y: 20 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white w-full max-w-md rounded-3xl shadow-xl relative z-10 overflow-hidden max-h-[90vh] overflow-y-auto">
        <button onClick={onClose} className="absolute top-4 right-4 p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors z-10">
          <X className="w-5 h-5" />
        </button>
        <div className="p-8">
          <h2 className="text-xl font-semibold text-slate-800 mb-1">Onboard Investor</h2>
          <p className="text-sm text-slate-500 mb-6">Manually register a new investor</p>
          <div className="space-y-4">
            {[
              { label: 'Full Name',     placeholder: 'e.g. Aditya Sharma',     type: 'text'  },
              { label: 'Mobile Number', placeholder: '+91 XXXXX XXXXX',        type: 'tel'   },
              { label: 'Email Address', placeholder: 'investor@example.com',   type: 'email' },
              { label: 'PAN Number',    placeholder: 'ABCDE1234F',             type: 'text', upper: true },
            ].map(f => (
              <div key={f.label}>
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">{f.label}</label>
                <input type={f.type} placeholder={f.placeholder}
                  className={`w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none ${f.upper ? 'uppercase' : ''}`} />
              </div>
            ))}
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Date of Birth</label>
              <input type="date" className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none" />
            </div>
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Assign Distributor</label>
              <select className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm outline-none cursor-pointer">
                {DISTRIBUTORS.map(d => <option key={d}>{d}</option>)}
              </select>
            </div>
            <div>
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">Address (Optional)</label>
              <textarea rows={2} placeholder="Full address..."
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none resize-none" />
            </div>
          </div>
          <div className="mt-8 flex gap-3">
            <button onClick={onClose} className="px-5 py-2.5 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">Cancel</button>
            <button onClick={onClose} className="flex-1 py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-xl hover:bg-[#1A3066] transition-colors">Create Investor</button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}

function CsvUploadModal({ onClose }: { onClose: () => void }) {
  const [dragging, setDragging] = useState(false);
  const [uploaded, setUploaded] = useState(false);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        onClick={onClose} className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" />
      <motion.div initial={{ opacity: 0, scale: 0.95, y: 20 }} animate={{ opacity: 1, scale: 1, y: 0 }} exit={{ opacity: 0, scale: 0.95, y: 20 }}
        className="bg-white w-full max-w-md rounded-3xl shadow-xl relative z-10 overflow-hidden">
        <button onClick={onClose} className="absolute top-4 right-4 p-2 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors">
          <X className="w-5 h-5" />
        </button>
        <div className="p-8">
          <h2 className="text-xl font-semibold text-slate-800 mb-1">Bulk Upload via CSV</h2>
          <p className="text-sm text-slate-500 mb-6">Import multiple investors at once using a CSV file</p>

          {!uploaded ? (
            <>
              <div
                onDragOver={e => { e.preventDefault(); setDragging(true); }}
                onDragLeave={() => setDragging(false)}
                onDrop={e => { e.preventDefault(); setDragging(false); setUploaded(true); }}
                onClick={() => setUploaded(true)}
                className={`border-2 border-dashed rounded-2xl p-10 text-center cursor-pointer transition-all ${dragging ? 'border-blue-400 bg-blue-50' : 'border-slate-200 hover:border-blue-300 hover:bg-slate-50'}`}
              >
                <Upload className={`w-10 h-10 mx-auto mb-3 ${dragging ? 'text-blue-500' : 'text-slate-300'}`} />
                <p className="text-sm font-semibold text-slate-700">Drop your CSV here</p>
                <p className="text-xs text-slate-400 mt-1">or click to browse files</p>
                <p className="text-[10px] text-slate-400 mt-3">Supports .csv files up to 5MB</p>
              </div>

              <div className="mt-4 p-4 bg-slate-50 rounded-xl border border-slate-100">
                <div className="flex justify-between items-center">
                  <div>
                    <p className="text-xs font-semibold text-slate-700">Required CSV columns:</p>
                    <p className="text-[10px] text-slate-500 mt-1 font-mono">name, mobile, email, pan, dob, distributor_arn, address</p>
                  </div>
                  <button className="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-slate-200 rounded-lg text-xs font-semibold text-blue-600 hover:bg-blue-50 transition-colors">
                    <Download className="w-3.5 h-3.5" /> Template
                  </button>
                </div>
              </div>
            </>
          ) : (
            <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
              className="text-center py-6">
              <div className="w-16 h-16 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-4">
                <CheckCircle2 className="w-8 h-8 text-green-500" />
              </div>
              <p className="font-semibold text-slate-800">investors_batch.csv uploaded</p>
              <p className="text-sm text-slate-500 mt-1">24 investors detected · 0 errors</p>
              <div className="mt-4 bg-slate-50 rounded-xl p-4 text-left border border-slate-100">
                <p className="text-xs font-semibold text-slate-600 mb-2">Preview (first 3 rows)</p>
                {['Kavya Reddy · PQRST1234U', 'Amit Joshi · VWXYZ5678A', 'Deepa Nair · BCDEF9012G'].map(r => (
                  <div key={r} className="flex items-center gap-2 py-1 text-xs text-slate-500">
                    <CheckCircle2 className="w-3 h-3 text-green-500 flex-shrink-0" /> {r}
                  </div>
                ))}
              </div>
            </motion.div>
          )}

          <div className="mt-6 flex gap-3">
            <button onClick={onClose} className="px-5 py-2.5 bg-slate-100 text-slate-600 text-sm font-medium rounded-xl hover:bg-slate-200 transition-colors">Cancel</button>
            <button onClick={onClose} disabled={!uploaded}
              className={`flex-1 py-2.5 text-sm font-medium rounded-xl transition-colors ${uploaded ? 'bg-[#0B1B3E] text-white hover:bg-[#1A3066]' : 'bg-slate-200 text-slate-400 cursor-not-allowed'}`}>
              {uploaded ? 'Import 24 Investors' : 'Waiting for file...'}
            </button>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
