import React, { useState, useEffect, useRef } from 'react';
import { MessageSquare, Phone, Mail, Send, Link2, ChevronDown, Clock, Search } from 'lucide-react';
import { API_BASE_URL } from '../config/api';

const BASE = API_BASE_URL;

const COMM_TYPES = [
  { id: 'whatsapp', label: 'WhatsApp',  icon: MessageSquare, badge: 'bg-green-50 text-green-600' },
  { id: 'email',    label: 'Email',     icon: Mail,          badge: 'bg-blue-50 text-blue-600' },
  { id: 'sms',      label: 'SMS',       icon: MessageSquare, badge: 'bg-indigo-50 text-indigo-600' },
  { id: 'call',     label: 'Call Note', icon: Phone,         badge: 'bg-slate-100 text-slate-500' },
];

const QUICK_TEMPLATES = [
  { label: 'KYC Link',        text: 'Hi [Name], please complete your KYC using this link: [KYC_LINK]. Let me know if you need help!' },
  { label: 'Payment Link',    text: 'Hi [Name], your payment link is ready. Please complete the payment here: [PAYMENT_LINK].' },
  { label: 'Onboarding Link', text: 'Hi [Name], start your investment journey here: [ONBOARDING_LINK]. I am here to guide you through every step.' },
  { label: 'SIP Reminder',    text: 'Hi [Name], this is a friendly reminder that your SIP instalment is due on [DATE]. Please ensure sufficient balance in your bank account.' },
  { label: 'Follow Up',       text: 'Hi [Name], just following up on our last conversation. Do you have any questions about your investment? Happy to help!' },
];

interface LogEntry {
  id: number;
  contactName: string;
  contactType: string;
  commType: string;
  message: string;
  timestamp: Date;
}

export default function Communications({ userData }: { userData?: any }) {
  const [contacts, setContacts]             = useState<any[]>([]);
  const [loading, setLoading]               = useState(true);
  const [selectedContact, setSelectedContact] = useState<any>(null);
  const [dropdownOpen, setDropdownOpen]     = useState(false);
  const [contactSearch, setContactSearch]   = useState('');
  const [commType, setCommType]             = useState('whatsapp');
  const [message, setMessage]               = useState('');
  const [log, setLog]                       = useState<LogEntry[]>([]);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!userData?.id) { setLoading(false); return; }

    const token = userData.token || sessionStorage.getItem('token') || '';
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    Promise.all([
      fetch(`${BASE}/investors/by-distributor/${userData.id}`, { headers }).then(r => r.ok ? r.json() : []),
      fetch(`${BASE}/leads/distributor/${userData.id}`, { headers }).then(r => r.ok ? r.json() : []),
    ])
      .then(([investors, leads]) => {
        const inv = (Array.isArray(investors) ? investors : []).map((i: any) => ({
          ...i,
          contactType: 'Investor',
          displayName: i.fullName || i.full_name || 'Unknown',
          contactInfo: i.mobileNumber || i.mobile_number || i.email || '',
        }));
        const ld = (Array.isArray(leads) ? leads : []).map((l: any) => ({
          ...l,
          contactType: 'Lead',
          displayName: l.prospectName || l.prospect_name || 'Unknown',
          contactInfo: l.mobileNumber || l.mobile_number || l.email || '',
        }));
        setContacts([...inv, ...ld]);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [userData?.id]);

  // Close dropdown on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const filteredContacts = contacts.filter(c =>
    !contactSearch || c.displayName.toLowerCase().includes(contactSearch.toLowerCase())
  );

  const handleSend = () => {
    if (!message.trim() || !selectedContact) return;
    setLog(prev => [{
      id: Date.now(),
      contactName: selectedContact.displayName,
      contactType: selectedContact.contactType,
      commType,
      message: message.trim(),
      timestamp: new Date(),
    }, ...prev]);
    setMessage('');
  };

  const typeObj = COMM_TYPES.find(c => c.id === commType)!;

  const fmtTime = (d: Date) =>
    d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) +
    ' · ' +
    d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-xl font-bold text-slate-800">Communications</h1>
        <p className="text-sm text-slate-500 mt-0.5">
          Manage all distributor-to-investor and distributor-to-lead communications
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

        {/* ── Left: Compose panel ──────────────────────────────── */}
        <div className="lg:col-span-2 space-y-4">

          {/* Contact selector */}
          <div className="bg-white rounded-2xl border border-slate-100 shadow-sm p-5">
            <p className="text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">Select Contact</p>
            <div className="relative" ref={dropdownRef}>
              <button
                onClick={() => setDropdownOpen(o => !o)}
                className="w-full flex items-center justify-between px-4 py-2.5 border border-slate-200 rounded-xl text-sm hover:border-slate-300 transition-colors bg-white"
              >
                {selectedContact ? (
                  <span className="flex items-center gap-2">
                    <span className="font-medium text-slate-800">{selectedContact.displayName}</span>
                    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded uppercase ${
                      selectedContact.contactType === 'Investor'
                        ? 'bg-blue-50 text-blue-600'
                        : 'bg-amber-50 text-amber-600'
                    }`}>
                      {selectedContact.contactType}
                    </span>
                  </span>
                ) : (
                  <span className="text-slate-400">Search investor or lead…</span>
                )}
                <ChevronDown className="w-4 h-4 text-slate-400 flex-shrink-0" />
              </button>

              {dropdownOpen && (
                <div className="absolute top-full left-0 right-0 mt-1 bg-white border border-slate-200 rounded-xl shadow-lg z-20 overflow-hidden">
                  <div className="p-2 border-b border-slate-100">
                    <div className="relative">
                      <Search className="w-3.5 h-3.5 absolute left-3 top-2.5 text-slate-400" />
                      <input
                        autoFocus
                        type="text"
                        placeholder="Type to search…"
                        value={contactSearch}
                        onChange={e => setContactSearch(e.target.value)}
                        className="w-full pl-8 pr-3 py-2 text-sm border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-100"
                      />
                    </div>
                  </div>
                  <div className="max-h-52 overflow-y-auto">
                    {loading ? (
                      <p className="text-sm text-slate-400 p-3 text-center">Loading contacts…</p>
                    ) : filteredContacts.length === 0 ? (
                      <p className="text-sm text-slate-400 p-3 text-center">No contacts found</p>
                    ) : (
                      filteredContacts.slice(0, 25).map(c => (
                        <button
                          key={c.id}
                          onClick={() => { setSelectedContact(c); setDropdownOpen(false); setContactSearch(''); }}
                          className="w-full flex items-center justify-between px-4 py-2.5 hover:bg-slate-50 text-left text-sm"
                        >
                          <div>
                            <p className="font-medium text-slate-800">{c.displayName}</p>
                            <p className="text-xs text-slate-400">{c.contactInfo}</p>
                          </div>
                          <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded uppercase ${
                            c.contactType === 'Investor'
                              ? 'bg-blue-50 text-blue-600'
                              : 'bg-amber-50 text-amber-600'
                          }`}>
                            {c.contactType}
                          </span>
                        </button>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Type tabs + compose */}
          <div className="bg-white rounded-2xl border border-slate-100 shadow-sm p-5 space-y-4">

            {/* Communication type tabs */}
            <div className="flex gap-2 flex-wrap">
              {COMM_TYPES.map(ct => {
                const Icon = ct.icon;
                return (
                  <button
                    key={ct.id}
                    onClick={() => setCommType(ct.id)}
                    className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg border transition-all ${
                      commType === ct.id
                        ? 'bg-slate-800 text-white border-slate-800'
                        : 'bg-white text-slate-500 border-slate-200 hover:border-slate-300'
                    }`}
                  >
                    <Icon className="w-3.5 h-3.5" />
                    {ct.label}
                  </button>
                );
              })}
            </div>

            {/* Quick templates */}
            <div>
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wider mb-2 flex items-center gap-1.5">
                <Link2 className="w-3 h-3" /> Quick Templates
              </p>
              <div className="flex gap-2 flex-wrap">
                {QUICK_TEMPLATES.map(qt => (
                  <button
                    key={qt.label}
                    onClick={() => setMessage(qt.text)}
                    className="px-2.5 py-1 text-xs font-medium bg-slate-50 text-slate-600 rounded-lg border border-slate-200 hover:bg-slate-100 transition-colors"
                  >
                    {qt.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Textarea */}
            <div>
              <textarea
                value={message}
                onChange={e => setMessage(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter' && e.ctrlKey) handleSend(); }}
                placeholder={`Type your ${typeObj.label} message here… (Ctrl+Enter to send)`}
                rows={5}
                className="w-full px-4 py-3 text-sm border border-slate-200 rounded-xl resize-none focus:outline-none focus:ring-2 focus:ring-blue-100 focus:border-blue-300 transition-colors"
              />
              <div className="flex items-center justify-between mt-2">
                <p className="text-xs text-slate-400">{message.length} characters</p>
                <button
                  onClick={handleSend}
                  disabled={!message.trim() || !selectedContact}
                  className="flex items-center gap-1.5 px-4 py-2 text-sm font-semibold bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <Send className="w-3.5 h-3.5" /> Log Communication
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Right: Communication log ──────────────────────────── */}
        <div className="bg-white rounded-2xl border border-slate-100 shadow-sm p-5 flex flex-col">
          <h2 className="text-sm font-semibold text-slate-700 mb-4 flex items-center gap-2 flex-shrink-0">
            <Clock className="w-4 h-4 text-slate-400" /> Communication Log
          </h2>

          {log.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center text-slate-300 gap-2">
              <MessageSquare className="w-8 h-8" />
              <p className="text-sm">No communications logged</p>
              <p className="text-xs text-center leading-relaxed">
                Select a contact, compose a message,<br />and click Log Communication.
              </p>
            </div>
          ) : (
            <div className="space-y-3 overflow-y-auto flex-1">
              {log.map(entry => {
                const ct = COMM_TYPES.find(c => c.id === entry.commType);
                return (
                  <div key={entry.id} className="p-3 bg-slate-50 rounded-xl border border-slate-100">
                    <div className="flex items-start justify-between gap-2 mb-1.5">
                      <div>
                        <span className="text-xs font-bold text-slate-700">{entry.contactName}</span>
                        <span className={`ml-1.5 text-[9px] font-bold px-1.5 py-0.5 rounded uppercase ${
                          entry.contactType === 'Investor' ? 'bg-blue-50 text-blue-500' : 'bg-amber-50 text-amber-500'
                        }`}>
                          {entry.contactType}
                        </span>
                      </div>
                      <span className="text-[10px] text-slate-400 flex-shrink-0">{fmtTime(entry.timestamp)}</span>
                    </div>
                    {ct && (
                      <span className={`inline-block text-[9px] font-bold uppercase px-1.5 py-0.5 rounded mb-1.5 ${ct.badge}`}>
                        {ct.label}
                      </span>
                    )}
                    <p className="text-xs text-slate-600 leading-relaxed">{entry.message}</p>
                  </div>
                );
              })}
            </div>
          )}
        </div>

      </div>
    </div>
  );
}
