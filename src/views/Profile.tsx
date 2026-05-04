import React, { useState } from 'react';
import { motion } from 'motion/react';
import { AlertTriangle, CheckCircle2, Edit2, Building, ShieldCheck, CreditCard, User, XCircle } from 'lucide-react';

export default function Profile({ userData }: { userData?: any }) {
  const [editingSection, setEditingSection] = useState<string | null>(null);

  const isExpired = (dateStr?: any) => {
    if (!dateStr) return false;
    let d;
    if (Array.isArray(dateStr)) d = new Date(dateStr[0], dateStr[1] - 1, dateStr[2]);
    else d = new Date(dateStr);
    return !isNaN(d.getTime()) && d < new Date();
  };

  const arnExpiry = userData?.arnExpiryDate || userData?.arn_expiry_date;
  const arnExpired = isExpired(arnExpiry);

  const complianceItems = [
    { label: 'ARN Number', value: userData?.arnNumber || '—', expiry: arnExpiry || '31 Dec 2025', status: (arnExpired ? 'expired' : 'active') as any },
    { label: 'NISM Certificate', value: userData?.nismCertificateNumber || '—', expiry: userData?.nismExpiryDate || '15 Jun 2025', status: (isExpired(userData?.nismExpiryDate) ? 'expired' : 'active') as any },
    { label: 'KYC Status', value: userData?.kycStatus || 'Not Started', expiry: null, status: (userData?.kycStatus === 'VERIFIED' ? 'active' : 'expiring') as any },
  ];

  const completionItems = [
    { label: 'Personal Details', done: !!userData?.fullName },
    { label: 'ARN & NISM', done: !!userData?.arnNumber && !arnExpired },
    { label: 'Bank Details', done: !!userData?.bankAccountNumber },
    { label: 'KYC Verified', done: userData?.kycStatus === 'VERIFIED' },
    { label: 'NISM Renewed', done: !!userData?.nismExpiryDate && !isExpired(userData?.nismExpiryDate) },
  ];

  const completionPct = userData?.profileCompletionPercent || Math.round(
    (completionItems.filter(i => i.done).length / completionItems.length) * 100
  );

  const toggle = (section: string) =>
    setEditingSection(prev => (prev === section ? null : section));

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Profile & Compliance</h1>
        <p className="text-slate-500 text-sm mt-1">Manage your professional identity and compliance credentials</p>
      </div>

      {/* NISM expiry alert */}
      {isExpired(userData?.nismExpiryDate) && (
        <div className="bg-amber-50 border border-amber-200 rounded-2xl p-4 flex items-center gap-3">
          <AlertTriangle className="w-5 h-5 text-amber-600 flex-shrink-0" />
          <div>
            <p className="text-sm font-semibold text-amber-800">NISM Certificate Expired</p>
            <p className="text-xs text-amber-700 mt-0.5">Your NISM Series V-A certificate expired on {userData?.nismExpiryDate}. Renew to avoid transaction restrictions.</p>
          </div>
          <button className="ml-auto px-4 py-2 bg-amber-600 text-white text-xs font-semibold rounded-lg hover:bg-amber-700 transition-colors whitespace-nowrap">
            Renew Now
          </button>
        </div>
      )}

      <div className="grid grid-cols-3 gap-6">
        {/* Left: detail cards */}
        <div className="col-span-2 space-y-6">
          {/* Personal Details */}
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <div className="flex justify-between items-center mb-5">
              <h2 className="font-semibold text-slate-800 flex items-center gap-2">
                <User className="w-4 h-4 text-slate-400" /> Personal Details
              </h2>
              <button
                onClick={() => toggle('personal')}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
              >
                <Edit2 className="w-3 h-3" /> {editingSection === 'personal' ? 'Save Changes' : 'Edit'}
              </button>
            </div>
            <div className="grid grid-cols-2 gap-5">
              {[
                { label: 'Full Name', value: userData?.fullName || '—', editable: true },
                { label: 'Mobile Number', value: userData?.mobileNumber || '—', editable: true },
                { label: 'Email Address', value: userData?.email || '—', editable: true },
                { label: 'PAN Number', value: userData?.panNumber || '—', editable: false },
              ].map(({ label, value, editable }) => (
                <div key={label}>
                  <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1.5">{label}</p>
                  {editingSection === 'personal' && editable ? (
                    <input
                      defaultValue={value}
                      className="w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none"
                    />
                  ) : (
                    <p className="text-sm font-medium text-slate-700">{value}</p>
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Compliance */}
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <div className="flex justify-between items-center mb-5">
              <h2 className="font-semibold text-slate-800 flex items-center gap-2">
                <ShieldCheck className="w-4 h-4 text-slate-400" /> Compliance Details
              </h2>
            </div>
            <div className="space-y-3">
              {complianceItems.map(c => (
                <div
                  key={c.label}
                  className={`p-4 rounded-xl flex items-center justify-between border ${
                    c.status === 'expired' ? 'bg-red-50 border-red-200' : 
                    c.status === 'expiring' ? 'bg-amber-50 border-amber-200' : 
                    'bg-slate-50 border-slate-100'
                  }`}
                >
                  <div>
                    <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">{c.label}</p>
                    <p className={`text-sm font-semibold ${c.status === 'expired' ? 'text-red-700' : 'text-slate-800'}`}>{c.value}</p>
                    {c.expiry && (
                      <p className={`text-xs mt-0.5 ${
                        c.status === 'expired' ? 'text-red-600 font-medium' : 
                        c.status === 'expiring' ? 'text-amber-600 font-medium' : 
                        'text-slate-500'
                      }`}>
                        {c.status === 'expired' ? 'Expired' : 'Expires'}: {c.expiry}
                      </p>
                    )}
                  </div>
                  {c.status === 'active' ? (
                    <CheckCircle2 className="w-5 h-5 text-green-500" />
                  ) : c.status === 'expired' ? (
                    <XCircle className="w-5 h-5 text-red-500" />
                  ) : (
                    <AlertTriangle className="w-5 h-5 text-amber-500" />
                  )}
                </div>
              ))}
            </div>
          </div>

          {/* Bank Details */}
          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <div className="flex justify-between items-center mb-5">
              <h2 className="font-semibold text-slate-800 flex items-center gap-2">
                <CreditCard className="w-4 h-4 text-slate-400" /> Bank Details
              </h2>
              <button
                onClick={() => toggle('bank')}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
              >
                <Edit2 className="w-3 h-3" /> {editingSection === 'bank' ? 'Save Changes' : 'Edit'}
              </button>
            </div>
            <div className="grid grid-cols-2 gap-5">
              {[
                { label: 'Bank Name', value: userData?.bankName || '—', mono: false },
                { label: 'Branch', value: userData?.bankBranch || '—', mono: false },
                { label: 'Account Number', value: userData?.bankAccountNumber ? `•••• •••• •••• ${userData.bankAccountNumber.slice(-4)}` : '—', mono: true },
                { label: 'IFSC Code', value: userData?.bankIfsc || '—', mono: true },
              ].map(({ label, value, mono }) => (
                <div key={label}>
                  <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1.5">{label}</p>
                  {editingSection === 'bank' ? (
                    <input
                      defaultValue={value}
                      className="w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none"
                    />
                  ) : (
                    <p className={`text-sm font-medium text-slate-700 ${mono ? 'font-mono' : ''}`}>{value}</p>
                  )}
                </div>
              ))}
            </div>
            <div className="mt-4 flex items-center gap-2 text-xs text-green-600 font-medium">
              <CheckCircle2 className="w-4 h-4" /> Bank account verified
            </div>
          </div>
        </div>

        {/* Right sidebar */}
        <div className="space-y-6">
          <div className="bg-[#0B1B3E] rounded-2xl p-6 text-white">
            <p className="text-[10px] font-bold text-blue-300 uppercase tracking-wider mb-3">Profile Completion</p>
            <p className="text-4xl font-semibold">{completionPct}%</p>
            <div className="h-1.5 bg-white/10 rounded-full mt-3 mb-5 overflow-hidden">
              <div className="h-full bg-blue-400 rounded-full transition-all" style={{ width: `${completionPct}%` }} />
            </div>
            <div className="space-y-2.5">
              {completionItems.map(item => (
                <div key={item.label} className="flex items-center gap-2 text-sm">
                  <CheckCircle2 className={`w-4 h-4 flex-shrink-0 ${item.done ? 'text-green-400' : 'text-white/20'}`} />
                  <span className={item.done ? 'text-white' : 'text-white/40'}>{item.label}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-3">Account Status</p>
            <div className="flex items-center gap-2 mb-2">
              <span className="relative flex h-2.5 w-2.5">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-green-500"></span>
              </span>
              <span className="text-sm font-semibold text-green-600">Approved & Active</span>
            </div>
            <p className="text-xs text-slate-500">Member since January 2023</p>
          </div>

          <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200">
            <p className="text-[10px] font-bold text-slate-400 uppercase tracking-wider mb-3">
              <Building className="w-3 h-3 inline mr-1" /> ARN Status
            </p>
            <p className={`text-sm font-bold ${arnExpired ? 'text-red-600' : 'text-green-600'}`}>{userData?.arnNumber || '—'}</p>
            <p className={`text-xs mt-1 ${arnExpired ? 'text-red-500 font-medium' : 'text-slate-500'}`}>
              {arnExpired ? 'Expired: ' : 'Expires: '} {arnExpiry || '—'}
            </p>
            <div className="mt-3 h-1.5 bg-slate-100 rounded-full overflow-hidden">
              <div className={`h-full rounded-full ${arnExpired ? 'bg-red-500' : 'bg-green-500'}`} style={{ width: arnExpired ? '100%' : '72%' }} />
            </div>
            <p className={`text-[10px] mt-1 ${arnExpired ? 'text-red-400 font-bold' : 'text-slate-400'}`}>
              {arnExpired ? 'CERTIFICATE EXPIRED' : '72% of validity remaining'}
            </p>
          </div>
        </div>
      </div>
    </motion.div>
  );
}
