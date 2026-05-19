import React, { useEffect, useMemo, useState } from 'react';
import { motion } from 'motion/react';
import { useForm } from 'react-hook-form';
import { AlertTriangle, CheckCircle2, Edit2, Building, ShieldCheck, CreditCard, User, XCircle, X } from 'lucide-react';
import { apiFetch } from '../config/api';

type ProfileFormValues = {
  name: string;
  phone: string;
  firmName: string;
  email: string;
};

export default function Profile({ userData }: { userData?: any }) {
  const [editingSection, setEditingSection] = useState<string | null>(null);
  const [profileData, setProfileData] = useState(userData || {});
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileError, setProfileError] = useState('');
  const [successToast, setSuccessToast] = useState('');

  useEffect(() => {
    setProfileData(userData || {});
  }, [userData]);

  const profileDefaults = useMemo<ProfileFormValues>(() => ({
    name: profileData?.name || profileData?.fullName || '',
    phone: profileData?.phone || profileData?.mobileNumber || '',
    firmName: profileData?.firmName || profileData?.firm_name || '',
    email: profileData?.email || '',
  }), [profileData]);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isDirty },
  } = useForm<ProfileFormValues>({
    values: profileDefaults,
  });

  const isExpired = (dateStr?: any) => {
    if (!dateStr) return false;
    let d;
    if (Array.isArray(dateStr)) d = new Date(dateStr[0], dateStr[1] - 1, dateStr[2]);
    else d = new Date(dateStr);
    return !isNaN(d.getTime()) && d < new Date();
  };

  const arnExpiry = profileData?.arnExpiryDate || profileData?.arn_expiry_date;
  const arnExpired = isExpired(arnExpiry);

  const complianceItems = [
    { label: 'ARN Number', value: profileData?.arnNumber || '—', expiry: arnExpiry || '31 Dec 2025', status: (arnExpired ? 'expired' : 'active') as any },
    { label: 'NISM Certificate', value: profileData?.nismCertificateNumber || '—', expiry: profileData?.nismExpiryDate || '15 Jun 2025', status: (isExpired(profileData?.nismExpiryDate) ? 'expired' : 'active') as any },
    { label: 'KYC Status', value: profileData?.kycStatus || 'Not Started', expiry: null, status: (profileData?.kycStatus === 'VERIFIED' ? 'active' : 'expiring') as any },
  ];

  const completionItems = [
    { label: 'Personal Details', done: !!(profileData?.fullName || profileData?.name) },
    { label: 'ARN & NISM', done: !!profileData?.arnNumber && !arnExpired },
    { label: 'Bank Details', done: !!profileData?.bankAccountNumber },
    { label: 'KYC Verified', done: profileData?.kycStatus === 'VERIFIED' },
    { label: 'NISM Renewed', done: !!profileData?.nismExpiryDate && !isExpired(profileData?.nismExpiryDate) },
  ];

  const completionPct = profileData?.profileCompletionPercent || Math.round(
    (completionItems.filter(i => i.done).length / completionItems.length) * 100
  );

  const toggle = (section: string) =>
    setEditingSection(prev => (prev === section ? null : section));

  const startProfileEdit = () => {
    setProfileError('');
    reset(profileDefaults);
    toggle('personal');
  };

  const onSubmitProfile = async (values: ProfileFormValues) => {
    const distributorId = profileData?.id || profileData?.distributorId;
    if (!distributorId) {
      setProfileError('Distributor ID is missing. Please log in again.');
      return;
    }

    const fieldMap = [
      { formKey: 'name', apiKey: 'name', current: profileDefaults.name },
      { formKey: 'phone', apiKey: 'phone', current: profileDefaults.phone },
      { formKey: 'firmName', apiKey: 'firmName', current: profileDefaults.firmName },
    ] as const;

    const changedFields = fieldMap.reduce<Record<string, string>>((payload, field) => {
      const nextValue = values[field.formKey].trim();
      if (nextValue !== field.current) payload[field.apiKey] = nextValue;
      return payload;
    }, {});

    if (Object.keys(changedFields).length === 0) {
      setEditingSection(null);
      return;
    }

    try {
      setSavingProfile(true);
      setProfileError('');

      const response = await apiFetch(`/distributors/${distributorId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(changedFields),
      });

      if (!response.ok) {
        const errorBody = await response.json().catch(() => null);
        throw new Error(errorBody?.message || `Profile update failed (${response.status})`);
      }

      const updated = await response.json().catch(() => changedFields);
      const updatedProfile = updated?.data || updated?.distributor || updated || changedFields;
      setProfileData((prev: any) => ({
        ...prev,
        ...updatedProfile,
        fullName: updatedProfile.fullName || updatedProfile.name || changedFields.name || prev.fullName,
        mobileNumber: updatedProfile.mobileNumber || updatedProfile.phone || changedFields.phone || prev.mobileNumber,
        firmName: updatedProfile.firmName || changedFields.firmName || prev.firmName,
      }));
      setEditingSection(null);
      setSuccessToast('Profile updated successfully.');
      window.setTimeout(() => setSuccessToast(''), 3000);
    } catch (err: any) {
      setProfileError(err?.message || 'Unable to update profile.');
    } finally {
      setSavingProfile(false);
    }
  };

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Profile & Compliance</h1>
        <p className="text-slate-500 text-sm mt-1">Manage your professional identity and compliance credentials</p>
      </div>

      {successToast && (
        <div className="fixed right-6 top-6 z-50 flex items-center gap-2 rounded-xl border border-green-200 bg-green-50 px-4 py-3 text-sm font-semibold text-green-700 shadow-lg">
          <CheckCircle2 className="h-4 w-4" />
          {successToast}
        </div>
      )}

      {/* NISM expiry alert */}
      {isExpired(profileData?.nismExpiryDate) && (
        <div className="bg-amber-50 border border-amber-200 rounded-2xl p-4 flex items-center gap-3">
          <AlertTriangle className="w-5 h-5 text-amber-600 flex-shrink-0" />
          <div>
            <p className="text-sm font-semibold text-amber-800">NISM Certificate Expired</p>
            <p className="text-xs text-amber-700 mt-0.5">Your NISM Series V-A certificate expired on {profileData?.nismExpiryDate}. Renew to avoid transaction restrictions.</p>
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
                onClick={startProfileEdit}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-blue-600 border border-blue-200 rounded-lg hover:bg-blue-50 transition-colors"
              >
                {editingSection === 'personal' ? <X className="w-3 h-3" /> : <Edit2 className="w-3 h-3" />}
                {editingSection === 'personal' ? 'Cancel' : 'Edit Profile'}
              </button>
            </div>
            {editingSection === 'personal' ? (
              <form onSubmit={handleSubmit(onSubmitProfile)} className="space-y-4">
                <div className="grid grid-cols-2 gap-5">
                  <div>
                    <label className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1.5 block">Full Name</label>
                    <input
                      {...register('name', { required: 'Name is required' })}
                      className="w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none"
                    />
                    {errors.name && <p className="mt-1 text-xs font-medium text-red-600">{errors.name.message}</p>}
                  </div>
                  <div>
                    <label className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1.5 block">Mobile Number</label>
                    <input
                      {...register('phone', {
                        required: 'Phone is required',
                        pattern: { value: /^[6-9]\d{9}$/, message: 'Enter a valid 10-digit Indian mobile number' },
                      })}
                      className="w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none"
                    />
                    {errors.phone && <p className="mt-1 text-xs font-medium text-red-600">{errors.phone.message}</p>}
                  </div>
                  <div>
                    <label className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1.5 block">Firm Name</label>
                    <input
                      {...register('firmName', { required: 'Firm name is required' })}
                      className="w-full bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none"
                    />
                    {errors.firmName && <p className="mt-1 text-xs font-medium text-red-600">{errors.firmName.message}</p>}
                  </div>
                  <div>
                    <label className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1.5 block">Email Address</label>
                    <input
                      {...register('email')}
                      disabled
                      className="w-full bg-slate-100 border border-slate-200 rounded-lg px-3 py-2 text-sm text-slate-500 outline-none cursor-not-allowed"
                    />
                    <p className="mt-1 text-xs text-slate-400">Email changes require verification.</p>
                  </div>
                </div>
                {profileError && <p className="text-xs font-medium text-red-600">{profileError}</p>}
                <div className="flex justify-end">
                  <button
                    type="submit"
                    disabled={savingProfile || !isDirty}
                    className="px-4 py-2 text-sm font-semibold bg-[#0B1B3E] text-white rounded-lg hover:bg-[#1A3066] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {savingProfile ? 'Saving...' : 'Save Changes'}
                  </button>
                </div>
              </form>
            ) : (
              <div className="grid grid-cols-2 gap-5">
                {[
                  { label: 'Full Name', value: profileDefaults.name || '—' },
                  { label: 'Mobile Number', value: profileDefaults.phone || '—' },
                  { label: 'Firm Name', value: profileDefaults.firmName || '—' },
                  { label: 'Email Address', value: profileDefaults.email || '—' },
                  { label: 'PAN Number', value: profileData?.panNumber || '—' },
                ].map(({ label, value }) => (
                  <div key={label}>
                    <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1.5">{label}</p>
                    <p className="text-sm font-medium text-slate-700">{value}</p>
                  </div>
                ))}
              </div>
            )}
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
                { label: 'Bank Name', value: profileData?.bankName || '—', mono: false },
                { label: 'Branch', value: profileData?.bankBranch || '—', mono: false },
                { label: 'Account Number', value: profileData?.bankAccountNumber ? `•••• •••• •••• ${profileData.bankAccountNumber.slice(-4)}` : '—', mono: true },
                { label: 'IFSC Code', value: profileData?.bankIfsc || '—', mono: true },
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
            <p className={`text-sm font-bold ${arnExpired ? 'text-red-600' : 'text-green-600'}`}>{profileData?.arnNumber || '—'}</p>
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
