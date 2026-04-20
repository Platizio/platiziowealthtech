import React, { useState } from 'react';
import { motion } from 'motion/react';
import {
  Search, Filter, ChevronLeft, MessageSquare, Phone,
  Plus, Youtube, Instagram, Globe, UserCheck
} from 'lucide-react';

interface Lead {
  id: number;
  name: string;
  mobile: string;
  source: string;
  stage: StageKey;
  amount: string;
  assignedTo: string;
  lastContact: string;
  notes: string;
}

type StageKey = 'Initial Contact' | 'In Progress' | 'Converted' | 'Post-Investment';

const leads: Lead[] = [
  { id: 1, name: 'Priya Nair', mobile: '+91 98712 34567', source: 'YouTube', stage: 'Initial Contact', amount: '₹10 L', assignedTo: 'Aditya Sharma', lastContact: 'Today', notes: 'Interested in SIP for retirement planning. Wants low-risk options.' },
  { id: 2, name: 'Vikram Singh', mobile: '+91 87654 32109', source: 'Instagram', stage: 'In Progress', amount: '₹5 L', assignedTo: 'Aditya Sharma', lastContact: '2 days ago', notes: 'Wants equity mutual funds. Reviewing fund options shared via WhatsApp.' },
  { id: 3, name: 'Anjali Desai', mobile: '+91 76543 21098', source: 'Referral', stage: 'Converted', amount: '₹25 L', assignedTo: 'Aditya Sharma', lastContact: 'Yesterday', notes: 'KYC completed. First SIP of ₹10,000/month set up in HDFC Large & Mid Cap.' },
  { id: 4, name: 'Mohit Gupta', mobile: '+91 65432 10987', source: 'YouTube', stage: 'Post-Investment', amount: '₹50 L', assignedTo: 'Aditya Sharma', lastContact: '1 week ago', notes: 'Regular investor. Quarterly portfolio review due. Portfolio up 18% YTD.' },
  { id: 5, name: 'Nisha Patel', mobile: '+91 54321 09876', source: 'Instagram', stage: 'Initial Contact', amount: '₹3 L', assignedTo: 'Aditya Sharma', lastContact: '3 days ago', notes: 'First-time investor. Needs financial literacy guidance before proceeding.' },
  { id: 6, name: 'Rajesh Kumar', mobile: '+91 43210 98765', source: 'Referral', stage: 'In Progress', amount: '₹8 L', assignedTo: 'Aditya Sharma', lastContact: '4 days ago', notes: 'HNI profile. Evaluating Quant Small Cap and Parag Parikh Flexi Cap.' },
];

const stageConfig: Record<StageKey, string> = {
  'Initial Contact': 'bg-slate-100 text-slate-600',
  'In Progress': 'bg-blue-50 text-blue-700',
  'Converted': 'bg-green-50 text-green-700',
  'Post-Investment': 'bg-purple-50 text-purple-700',
};

const STAGES: StageKey[] = ['Initial Contact', 'In Progress', 'Converted', 'Post-Investment'];

function SourceIcon({ source }: { source: string }) {
  if (source === 'YouTube') return <Youtube className="w-3.5 h-3.5 text-red-500" />;
  if (source === 'Instagram') return <Instagram className="w-3.5 h-3.5 text-pink-500" />;
  return <Globe className="w-3.5 h-3.5 text-blue-500" />;
}

const interactions = [
  { date: 'Today, 10:00 AM', action: 'Phone Call', note: 'Discussed SIP options. Client interested in ₹5,000/month equity SIP.' },
  { date: 'Yesterday, 3:00 PM', action: 'WhatsApp', note: 'Sent fund brochures and 3-year performance comparison sheet.' },
  { date: '3 days ago', action: 'First Contact', note: 'Lead generated via YouTube comment. Initial inquiry about mutual funds.' },
];

export default function Leads() {
  const [selected, setSelected] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [stageFilter, setStageFilter] = useState<StageKey | 'All'>('All');

  const filtered = leads.filter(l => {
    const matchSearch = l.name.toLowerCase().includes(search.toLowerCase()) ||
      l.source.toLowerCase().includes(search.toLowerCase());
    const matchStage = stageFilter === 'All' || l.stage === stageFilter;
    return matchSearch && matchStage;
  });

  if (selected !== null) {
    const lead = leads.find(l => l.id === selected)!;
    return <LeadDetail lead={lead} onBack={() => setSelected(null)} />;
  }

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 h-full flex flex-col">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Lead Pipeline</h1>
          <p className="text-slate-500 text-sm mt-1">Track investor leads from first contact to post-investment</p>
        </div>
        <div className="flex items-center gap-3">
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-slate-200 text-slate-700 rounded-lg shadow-sm hover:bg-slate-50 transition-colors">
            <Filter className="w-4 h-4" /> Filter
          </button>
          <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors">
            <Plus className="w-4 h-4" /> Add Lead
          </button>
        </div>
      </div>

      {/* Stage summary cards */}
      <div className="grid grid-cols-4 gap-4 mb-6">
        {STAGES.map(stage => {
          const count = leads.filter(l => l.stage === stage).length;
          return (
            <button
              key={stage}
              onClick={() => setStageFilter(stageFilter === stage ? 'All' : stage)}
              className={`bg-white rounded-xl p-4 shadow-sm border text-left transition-all ${
                stageFilter === stage ? 'border-blue-400 ring-2 ring-blue-100' : 'border-slate-200 hover:border-slate-300'
              }`}
            >
              <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{stage}</p>
              <p className="text-2xl font-semibold text-slate-800">{count}</p>
              <span className={`mt-2 inline-block px-2 py-0.5 rounded text-[10px] font-bold ${stageConfig[stage]}`}>
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
              {filtered.map(lead => (
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
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </motion.div>
  );
}

function LeadDetail({ lead, onBack }: { lead: Lead; onBack: () => void }) {
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
          {lead.stage !== 'Converted' && lead.stage !== 'Post-Investment' && (
            <button className="flex items-center gap-2 px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors">
              <UserCheck className="w-4 h-4" /> Convert to Investor
            </button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 space-y-6">
          {/* Lead Details */}
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <h2 className="font-semibold text-slate-800 mb-5">Lead Details</h2>
            <div className="grid grid-cols-2 gap-5">
              {[
                { label: 'Mobile', value: lead.mobile },
                { label: 'Expected Investment', value: lead.amount },
                { label: 'Source', value: lead.source },
                { label: 'Assigned To', value: lead.assignedTo },
                { label: 'Last Contact', value: lead.lastContact },
                { label: 'Current Stage', value: lead.stage },
              ].map(({ label, value }) => (
                <div key={label}>
                  <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{label}</p>
                  <p className="text-sm font-medium text-slate-700">{value}</p>
                </div>
              ))}
            </div>
            <div className="mt-5 pt-5 border-t border-slate-100">
              <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-2">Notes</p>
              <p className="text-sm text-slate-600 leading-relaxed">{lead.notes}</p>
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
              const done = i <= currentIdx;
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
                      <div className={`w-0.5 h-10 mt-1 ${done && i < currentIdx ? 'bg-green-500/40' : 'bg-white/10'}`} />
                    )}
                  </div>
                  <div className="pb-10">
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
