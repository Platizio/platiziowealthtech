import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { getPageContent, getPageMeta } from '../utils/pagination';
import { formatDate } from '../utils/formatDate';
import { useDebounce } from '../hooks/useDebounce';
import {
  Search, Filter, ChevronLeft, MessageSquare, Phone,
  Plus, Youtube, Instagram, Globe, UserCheck, X,
  CheckCircle2,
} from 'lucide-react';
import { apiFetch } from '../config/api';
import Pagination from '../components/Pagination';

interface Lead {
  id: string;
  name: string;
  mobile: string;
  source: string;
  stage: StageKey;
  amount: string;
  assignedTo: string;
  lastContact: string;
  notes: string;
  email?: string;
  pan?: string;
  dob?: string;
  firstName?: string;
  lastName?: string;
}

type StageKey =
  | 'New Lead'
  | 'Contacted'
  | 'Interested'
  | 'Follow-up Scheduled'
  | 'Not Interested'
  | 'Ready for KYC';

const leads: Lead[] = [
  {
    id: '1', firstName: 'Priya', lastName: 'Nair',
    name: 'Priya Nair', mobile: '+91 98712 34567', email: 'priya.nair@email.com',
    source: 'YouTube', stage: 'New Lead', amount: '₹10 L',
    assignedTo: 'Aditya Sharma', lastContact: 'Today',
    notes: 'Interested in SIP for retirement planning. Wants low-risk options.',
  },
  {
    id: '2', firstName: 'Vikram', lastName: 'Singh',
    name: 'Vikram Singh', mobile: '+91 87654 32109', email: 'vikram.singh@email.com',
    source: 'Instagram', stage: 'Contacted', amount: '₹5 L',
    assignedTo: 'Aditya Sharma', lastContact: '2 days ago',
    notes: 'Wants equity mutual funds. Reviewing fund options shared via WhatsApp.',
  },
  {
    id: '3', firstName: 'Anjali', lastName: 'Desai',
    name: 'Anjali Desai', mobile: '+91 76543 21098', email: 'anjali.desai@email.com',
    source: 'Referral', stage: 'Interested', amount: '₹25 L',
    assignedTo: 'Aditya Sharma', lastContact: 'Yesterday',
    notes: 'Discussed large-cap funds. Very interested, wants detailed presentation.',
  },
  {
    id: '4', firstName: 'Mohit', lastName: 'Gupta',
    name: 'Mohit Gupta', mobile: '+91 65432 10987', email: 'mohit.gupta@email.com',
    source: 'YouTube', stage: 'Follow-up Scheduled', amount: '₹50 L',
    assignedTo: 'Aditya Sharma', lastContact: '1 week ago',
    notes: 'Follow-up call scheduled for next Tuesday. HNI profile.',
  },
  {
    id: '5', firstName: 'Nisha', lastName: 'Patel',
    name: 'Nisha Patel', mobile: '+91 54321 09876', email: 'nisha.patel@email.com',
    source: 'Instagram', stage: 'Not Interested',  amount: '₹3 L',
    assignedTo: 'Aditya Sharma', lastContact: '3 days ago',
    notes: 'Decided not to invest at this time. May revisit in 6 months.',
  },
  {
    id: '6', firstName: 'Rajesh', lastName: 'Kumar',
    name: 'Rajesh Kumar', mobile: '+91 43210 98765', email: 'rajesh.kumar@email.com',
    pan: 'ABCPK1234R', dob: '1985-06-15',
    source: 'Referral', stage: 'Ready for KYC', amount: '₹8 L',
    assignedTo: 'Aditya Sharma', lastContact: '4 days ago',
    notes: 'HNI profile. Agreed to invest. PAN verified. Ready for KYC & onboarding.',
  },
];

const stageConfig: Record<StageKey, string> = {
  'New Lead':            'bg-slate-100 text-slate-600',
  'Contacted':           'bg-blue-50 text-blue-700',
  'Interested':          'bg-cyan-50 text-cyan-700',
  'Follow-up Scheduled': 'bg-amber-50 text-amber-700',
  'Not Interested':      'bg-red-50 text-red-600',
  'Ready for KYC':       'bg-green-50 text-green-700',
};

const STAGES: StageKey[] = [
  'New Lead', 'Contacted', 'Interested',
  'Follow-up Scheduled', 'Not Interested', 'Ready for KYC',
];

function SourceIcon({ source }: { source: string }) {
  if (source === 'YouTube')   return <Youtube   className="w-3.5 h-3.5 text-red-500"  />;
  if (source === 'Instagram') return <Instagram className="w-3.5 h-3.5 text-pink-500" />;
  return <Globe className="w-3.5 h-3.5 text-blue-500" />;
}

const interactions = [
  { date: 'Today, 10:00 AM',    action: 'Phone Call',   note: 'Discussed SIP options. Client interested in ₹5,000/month equity SIP.'          },
  { date: 'Yesterday, 3:00 PM', action: 'WhatsApp',     note: 'Sent fund brochures and 3-year performance comparison sheet.'                   },
  { date: '3 days ago',         action: 'First Contact', note: 'Lead generated via YouTube comment. Initial inquiry about mutual funds.'        },
];

/* ── Add Prospect Modal ────────────────────────────────────────────────────── */
interface ProspectForm {
  firstName: string; lastName: string;
  mobile: string; email: string;
  pan: string; dob: string;
  source: string; amount: string; notes: string;
}

// ── Hoisted to module scope so identity is stable across re-renders.
// Defining these inside AddProspectModal caused React to treat them as new
// component types on every keystroke, unmounting inputs and losing focus. ──
function Field({
  label, required, error, children,
}: {
  label: string; required?: boolean; error?: string; children: React.ReactNode;
}) {
  return (
    <div>
      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1">
        {label}{required && <span className="text-red-400 ml-0.5">*</span>}
      </label>
      {children}
      {error && <p className="text-xs text-red-500 mt-1">{error}</p>}
    </div>
  );
}

function inputCls(err?: string) {
  return `w-full bg-slate-50 border ${err ? 'border-red-300' : 'border-slate-200'} rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all`;
}

function AddProspectModal({
  onClose,
  onAdd,
}: {
  onClose: () => void;
  onAdd: (lead: Lead) => void;
}) {
  const [form, setForm] = useState<ProspectForm>({
    firstName: '', lastName: '', mobile: '', email: '',
    pan: '', dob: '', source: 'Referral', amount: '', notes: '',
  });
  const [errors, setErrors] = useState<Partial<ProspectForm>>({});

  const f = (field: keyof ProspectForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    setForm(p => ({ ...p, [field]: e.target.value }));
    setErrors(p => ({ ...p, [field]: '' }));
  };

  const validate = () => {
    const e: Partial<ProspectForm> = {};
    if (!form.firstName.trim()) e.firstName = 'Required';
    if (!form.lastName.trim())  e.lastName  = 'Required';
    if (!form.mobile.trim() || !/^\+?[\d\s]{10,14}$/.test(form.mobile.replace(/\s/g, '')))
      e.mobile = 'Enter a valid mobile number';
    if (form.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email))
      e.email  = 'Invalid email format';
    return e;
  };

  const handleSubmit = () => {
    const e = validate();
    if (Object.keys(e).length) { setErrors(e); return; }
    const newLead: Lead = {
      id: Date.now().toString(),
      firstName: form.firstName.trim(),
      lastName:  form.lastName.trim(),
      name:      `${form.firstName.trim()} ${form.lastName.trim()}`,
      mobile:    form.mobile.trim(),
      email:     form.email.trim() || undefined,
      pan:       form.pan.trim().toUpperCase() || undefined,
      dob:       form.dob || undefined,
      source:    form.source,
      stage:     'New Lead',
      amount:    form.amount ? `₹${form.amount}` : '₹0',
      assignedTo:'Aditya Sharma',
      lastContact: 'Just now',
      notes:     form.notes.trim(),
    };
    onAdd(newLead);
    onClose();
  };

  return (
    <div className="fixed inset-0 bg-black/40 backdrop-blur-sm z-50 flex items-center justify-center p-4">
      <motion.div
        initial={{ opacity: 0, scale: 0.95, y: 16 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col"
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-5 border-b border-slate-100">
          <div>
            <h2 className="text-lg font-semibold text-slate-800">Add New Prospect</h2>
            <p className="text-sm text-slate-500 mt-0.5">Create a new lead in the pipeline</p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400 hover:text-slate-600 transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-auto p-6 space-y-5">
          <div className="grid grid-cols-2 gap-4">
            <Field label="First Name" required error={errors.firstName}>
              <input value={form.firstName} onChange={f('firstName')} placeholder="Priya"
                className={inputCls(errors.firstName)} />
            </Field>
            <Field label="Last Name" required error={errors.lastName}>
              <input value={form.lastName} onChange={f('lastName')} placeholder="Nair"
                className={inputCls(errors.lastName)} />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Mobile Number" required error={errors.mobile}>
              <input value={form.mobile} onChange={f('mobile')} placeholder="+91 98765 43210"
                className={inputCls(errors.mobile)} />
            </Field>
            <Field label="Email Address" error={errors.email}>
              <input type="email" value={form.email} onChange={f('email')} placeholder="priya@email.com"
                className={inputCls(errors.email)} />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Field label="PAN Number">
              <input value={form.pan} onChange={f('pan')} placeholder="ABCDE1234F"
                className={inputCls()} />
            </Field>
            <Field label="Date of Birth">
              <input type="date" value={form.dob} onChange={f('dob')}
                className={inputCls()} />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <Field label="Lead Source">
              <select value={form.source} onChange={f('source')} className={inputCls()}>
                {['YouTube', 'Instagram', 'Referral', 'Website', 'Walk-in', 'Cold Call', 'Other'].map(s => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
            </Field>
            <Field label="Expected Investment (₹)">
              <input value={form.amount} onChange={f('amount')} placeholder="5,00,000"
                className={inputCls()} />
            </Field>
          </div>
          <Field label="Notes">
            <textarea value={form.notes} onChange={f('notes')} rows={3}
              placeholder="Initial conversation notes, interests, goals…"
              className={`${inputCls()} resize-none`} />
          </Field>
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-slate-100 flex justify-end gap-3">
          <button onClick={onClose}
            className="px-4 py-2 text-sm font-medium text-slate-600 border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors">
            Cancel
          </button>
          <button onClick={handleSubmit}
            className="px-5 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors shadow-sm">
            Add Prospect
          </button>
        </div>
      </motion.div>
    </div>
  );
}

/* ── Main Leads component ──────────────────────────────────────────────────── */
export default function Leads({
  userData,
  onStartOnboarding,
}: {
  userData?: any;
  onStartOnboarding?: (prospect: Lead) => void;
}) {
  const [allLeads, setAllLeads]   = useState<Lead[]>([]);
  const [loading, setLoading]     = useState(true);
  const [selected, setSelected]   = useState<string | null>(null);
  const [search,   setSearch]     = useState('');
  const debouncedSearch           = useDebounce(search, 300);
  const [stageFilter, setStageFilter] = useState<StageKey | 'All'>('All');
  const [showAdd,  setShowAdd]    = useState(false);

  useEffect(() => {
    const fetchLeads = async () => {
      console.log("Leads: userData.id:", userData?.id);
      if (!userData?.id) {
        setLoading(false);
        return;
      }
      try {
        setLoading(true);
        const response = await apiFetch(`/leads/distributor/${userData.id}`);
        if (!response.ok) throw new Error('Failed to fetch leads');
        const data = await response.json();
        console.log("Leads: Fetched raw data from backend:", data);
        
        const mappedLeads: Lead[] = data.map((l: any) => ({
          id: String(l.id),
          name: l.prospectName,
          mobile: l.mobileNumber,
          email: l.email,
          source: mapSource(l.source),
          stage: mapStatus(l.status),
          amount: '₹0', // Backend doesn't seem to have this field yet
          assignedTo: userData.fullName || 'You',
          lastContact: new Date(l.updatedAt || l.createdAt).toLocaleDateString(),
          notes: l.notes || '',
          pan: l.pan,
          dob: l.dob
        }));
        console.log("Leads: Mapped leads:", mappedLeads);
        
        setAllLeads(mappedLeads);
      } catch (error) {
        console.error('Leads: Error fetching leads:', error);
      } finally {
        setLoading(false);
      }
    };

    fetchLeads();
  }, [userData?.id]);

  const mapStatus = (status: string): StageKey => {
    switch (status) {
      case 'NEW': return 'New Lead';
      case 'CONTACTED': return 'Contacted';
      case 'FOLLOW_UP': return 'Follow-up Scheduled';
      case 'LOST': return 'Not Interested';
      case 'CONVERTED_TO_INVESTOR':
      case 'INVESTMENT_COMPLETED': return 'Ready for KYC';
      default: return 'New Lead';
    }
  };

  const mapSource = (source: string): string => {
    switch (source) {
      case 'SOCIAL_MEDIA': return 'Instagram';
      case 'YOUTUBE': return 'YouTube';
      case 'WEBSITE': return 'Website';
      case 'REFERRAL': return 'Referral';
      case 'MANUAL': return 'Walk-in';
      default: return 'Other';
    }
  };

  const filtered = allLeads.filter(l => {
    const query = debouncedSearch.toLowerCase();
    const matchSearch = l.name.toLowerCase().includes(query) ||
      l.source.toLowerCase().includes(query);
    const matchStage = stageFilter === 'All' || l.stage === stageFilter;
    return matchSearch && matchStage;
  });

  if (selected !== null) {
    const lead = allLeads.find(l => l.id === selected);
    if (!lead) { setSelected(null); return null; }
    return (
      <LeadDetail
        lead={lead}
        onBack={() => setSelected(null)}
        onStartOnboarding={onStartOnboarding}
      />
    );
  }

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 h-full flex flex-col">
      {showAdd && (
        <AddProspectModal
          onClose={() => setShowAdd(false)}
          onAdd={lead => setAllLeads(p => [lead, ...p])}
        />
      )}

      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Lead Pipeline</h1>
          <p className="text-slate-500 text-sm mt-1">Track investor leads from first contact to KYC onboarding</p>
        </div>
        <div className="flex items-center gap-3">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Filter className="w-4 h-4" /> Filter
          </button>
          <button
            onClick={() => setShowAdd(true)}
            className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors">
            <Plus className="w-4 h-4" /> Add Lead
          </button>
        </div>
      </div>

      {/* Stage summary cards */}
      <div className="grid grid-cols-6 gap-3 mb-6">
        {STAGES.map(stage => {
          const count = allLeads.filter(l => l.stage === stage).length;
          return (
            <button
              key={stage}
              onClick={() => setStageFilter(stageFilter === stage ? 'All' : stage)}
              className={`bg-white rounded-xl p-4 shadow-sm border text-left transition-all ${
                stageFilter === stage ? 'border-blue-400 ring-2 ring-blue-100' : 'border-slate-200 hover:border-slate-300'
              }`}
            >
              <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1 truncate">{stage}</p>
              <p className="text-2xl font-semibold text-slate-800">{count}</p>
              <span className={`mt-2 inline-block px-2 py-0.5 rounded text-[10px] font-bold truncate max-w-full ${stageConfig[stage]}`}>
                {stage}
              </span>
            </button>
          );
        })}
      </div>

      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 flex flex-col flex-1 overflow-hidden">
        <div className="p-4 border-b border-slate-100">
          <div className="relative max-w-md">
            <Search className="w-4 h-4 absolute left-3 top-3 text-slate-400" />
            <input
              type="text"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search by name or source..."
              className="w-full pl-9 pr-4 py-2 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>
        </div>

        <div className="flex-1 overflow-auto">
          <table className="w-full text-left">
            <thead className="bg-slate-50 text-[10px] uppercase tracking-wider text-slate-500 sticky top-0 z-10 font-semibold">
              <tr>
                <th className="px-6 py-4">Name</th>
                <th className="px-6 py-4">Source</th>
                <th className="px-6 py-4">Expected Amount</th>
                <th className="px-6 py-4">Stage</th>
                <th className="px-6 py-4 text-right">Last Contact</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading ? (
                <tr>
                  <td colSpan={5} className="px-6 py-12 text-center text-sm text-slate-400">
                    <div className="flex flex-col items-center gap-3">
                      <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
                      Loading leads...
                    </div>
                  </td>
                </tr>
              ) : filtered.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-6 py-12 text-center text-sm text-slate-400">
                    No leads match your search.
                  </td>
                </tr>
              ) : (
                filtered.map(lead => (
                  <tr
                    key={lead.id}
                    onClick={() => setSelected(lead.id)}
                    className="group hover:bg-slate-50 transition-colors cursor-pointer"
                  >
                  <td className="px-6 py-4">
                    <div className="font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{lead.name}</div>
                    <div className="text-xs text-slate-400 mt-1">{lead.mobile}</div>
                  </td>
                  <td className="px-6 py-4">
                    <span className="flex items-center gap-1.5 text-xs font-medium text-slate-600">
                      <SourceIcon source={lead.source} /> {lead.source}
                    </span>
                  </td>
                  <td className="px-6 py-4 font-mono font-medium text-slate-700">{lead.amount}</td>
                  <td className="px-6 py-4">
                    <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${stageConfig[lead.stage]}`}>
                      {lead.stage}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-xs text-slate-500 text-right">{lead.lastContact}</td>
                </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </motion.div>
  );
}

/* ── Lead Detail ─────────────────────────────────────────────────────────────── */
function LeadDetail({
  lead,
  onBack,
  onStartOnboarding,
}: {
  lead: Lead;
  onBack: () => void;
  onStartOnboarding?: (prospect: Lead) => void;
}) {
  const currentIdx = STAGES.indexOf(lead.stage);

  return (
    <motion.div initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} className="p-8 max-w-5xl">
      <button onClick={onBack} className="flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-slate-800 mb-6 transition-colors">
        <ChevronLeft className="w-4 h-4" /> Back to Leads
      </button>

      <div className="flex justify-between items-start mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">{lead.name}</h1>
          <div className="flex items-center gap-3 mt-2">
            <span className={`px-2.5 py-1 text-xs font-semibold rounded-md ${stageConfig[lead.stage]}`}>
              {lead.stage}
            </span>
            <span className="flex items-center gap-1 text-xs text-slate-500">
              <SourceIcon source={lead.source} /> {lead.source}
            </span>
          </div>
        </div>
        <div className="flex gap-2">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg hover:bg-slate-50 transition-colors">
            <Phone className="w-4 h-4" /> Call
          </button>
          {lead.stage === 'Ready for KYC' && onStartOnboarding && (
            <button
              onClick={() => onStartOnboarding(lead)}
              className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors shadow-sm"
            >
              <UserCheck className="w-4 h-4" /> Start Onboarding
            </button>
          )}
        </div>
      </div>

      {lead.stage === 'Ready for KYC' && onStartOnboarding && (
        <div className="mb-6 bg-green-50 border border-green-200 rounded-xl px-5 py-4 flex items-center gap-4">
          <div className="w-10 h-10 rounded-full bg-green-100 flex items-center justify-center flex-shrink-0">
            <CheckCircle2 className="w-5 h-5 text-green-600" />
          </div>
          <div className="flex-1">
            <p className="text-sm font-semibold text-green-800">This prospect is ready for KYC onboarding</p>
            <p className="text-xs text-green-700 mt-0.5">Click "Start Onboarding" to begin the 7-step investor registration process.</p>
          </div>
          <button
            onClick={() => onStartOnboarding(lead)}
            className="flex items-center gap-2 px-4 py-2 text-sm font-semibold bg-green-600 text-white rounded-lg hover:bg-green-700 transition-colors whitespace-nowrap"
          >
            <UserCheck className="w-4 h-4" /> Start Onboarding
          </button>
        </div>
      )}

      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 space-y-6">
          {/* Lead Details */}
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <h2 className="font-semibold text-slate-800 mb-5">Lead Details</h2>
            <div className="grid grid-cols-2 gap-5">
              {[
                { label: 'Mobile',              value: lead.mobile          },
                { label: 'Expected Investment',  value: lead.amount          },
                { label: 'Email',               value: lead.email || '—'    },
                { label: 'Source',              value: lead.source          },
                { label: 'Assigned To',         value: lead.assignedTo      },
                { label: 'Last Contact',         value: lead.lastContact     },
                { label: 'Current Stage',        value: lead.stage           },
                ...(lead.pan ? [{ label: 'PAN', value: lead.pan }] : []),
              ].map(({ label, value }) => (
                <div key={label}>
                  <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{label}</p>
                  <p className="text-sm font-medium text-slate-700">{value}</p>
                </div>
              ))}
            </div>
            <div className="mt-5 pt-5 border-t border-slate-100">
              <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-2">Notes</p>
              <p className="text-sm text-slate-600 leading-relaxed">{lead.notes || '—'}</p>
            </div>
          </div>

          {/* Interaction History */}
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <div className="flex justify-between items-center mb-5">
              <h2 className="font-semibold text-slate-800">Interaction History</h2>
              <button className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors">
                <Plus className="w-3 h-3" /> Log Interaction
              </button>
            </div>
            <div className="space-y-0">
              {interactions.map((item, i) => (
                <div key={i} className="flex gap-4">
                  <div className="flex flex-col items-center">
                    <div className="w-8 h-8 rounded-full bg-blue-50 border border-blue-100 flex items-center justify-center flex-shrink-0">
                      <MessageSquare className="w-3.5 h-3.5 text-blue-500" />
                    </div>
                    {i < interactions.length - 1 && <div className="w-0.5 flex-1 bg-slate-100 my-1" />}
                  </div>
                  <div className={`${i < interactions.length - 1 ? 'pb-6' : ''}`}>
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-xs font-semibold text-slate-800">{item.action}</span>
                      <span className="text-xs text-slate-400">{item.date}</span>
                    </div>
                    <p className="text-sm text-slate-600 leading-relaxed">{item.note}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Lead Journey */}
        <div className="bg-[#0B1B3E] rounded-2xl p-6 text-white h-fit">
          <h2 className="font-semibold mb-6">Lead Journey</h2>
          <div className="space-y-0">
            {STAGES.map((stage, i) => {
              const done      = i <= currentIdx;
              const isCurrent = i === currentIdx;
              return (
                <div key={stage} className="flex gap-3">
                  <div className="flex flex-col items-center">
                    <div className={`w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0 border ${
                      done ? 'bg-green-500 border-green-500' : 'bg-transparent border-white/20'
                    }`}>
                      {done && <div className="w-2 h-2 rounded-full bg-white" />}
                    </div>
                    {i < STAGES.length - 1 && (
                      <div className={`w-0.5 h-8 mt-1 ${done && i < currentIdx ? 'bg-green-500/40' : 'bg-white/10'}`} />
                    )}
                  </div>
                  <div className="pb-8">
                    <p className={`text-sm font-medium leading-tight ${done ? 'text-white' : 'text-white/40'}`}>
                      {stage}
                    </p>
                    {isCurrent && (
                      <p className="text-[10px] text-green-300 mt-0.5">Current stage</p>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </motion.div>
  );
}
