import React, { useState } from 'react';
import { motion } from 'motion/react';
import {
  AlertCircle, ShieldCheck, ArrowLeft, Mail, Eye, EyeOff, LogIn,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch } from '../config/api';
import { useAppDispatch } from '../store/hooks';
import { setInvestorUser } from '../store/slices/investorAuthSlice';
import { normalizeInvestorUser } from '../types/investorAuth';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;

const Spinner = () => (
  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
  </svg>
);

/**
 * Investor login — email + PAN (the PAN is the password). Replaces the previous
 * passwordless OTP login. On success the backend sets the HttpOnly investor cookie
 * and we go to the dashboard.
 */
export default function InvestorLoginPage() {
  const navigate = useNavigate();
  const dispatch = useAppDispatch();
  const [searchParams] = useSearchParams();
  const sessionExpired = searchParams.get('reason') === 'session_expired';

  const [email, setEmail] = useState('');
  const [pan, setPan] = useState('');
  const [showPan, setShowPan] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleLogin = async () => {
    const id = email.trim().toLowerCase();
    const panUpper = pan.trim().toUpperCase();
    if (!id) { setError('Please enter your email address.'); return; }
    if (!EMAIL_RE.test(id)) { setError('Please enter a valid email address.'); return; }
    if (!PAN_RE.test(panUpper)) { setError('Please enter your 10-character PAN (e.g. ABCDE1234F).'); return; }
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor-auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: id, pan: panUpper }),
        skipAuthRedirect: true,
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error(data?.message || 'Invalid email or PAN. Please check and try again.');
      }
      const user = normalizeInvestorUser(data);
      if (user) dispatch(setInvestorUser(user));
      navigate('/investor/dashboard', { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Invalid email or PAN. Please check and try again.');
    } finally {
      setLoading(false);
    }
  };

  const LeftPanel = (
    <div className="hidden lg:flex w-[420px] bg-[#0B1B3E] flex-col justify-between p-12 flex-shrink-0 relative overflow-hidden">
      <div className="absolute top-0 right-0 w-64 h-64 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-0 left-0 w-48 h-48 bg-violet-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="relative z-10">
        <div className="flex items-center gap-3 mb-16">
          <div className="w-10 h-10 rounded-xl bg-white/10 border border-white/20 flex items-center justify-center text-white font-bold text-xl shadow-lg">P</div>
          <span className="text-white font-bold text-xl tracking-tight">Platizio</span>
        </div>
        <h2 className="text-3xl font-bold text-white mb-3 leading-snug">Investor Portal</h2>
        <p className="text-blue-200/60 text-sm leading-relaxed mb-10">
          Sign in with your email and PAN to review your portfolio, complete KYC, and approve your onboarding.
        </p>
        <div className="space-y-4">
          {[
            { icon: '🔐', text: 'Sign in with email + PAN' },
            { icon: '📈', text: 'Track your holdings & returns' },
            { icon: '✅', text: 'Approve your onboarding submission' },
            { icon: '📄', text: 'Complete KYC and buy funds' },
          ].map(b => (
            <div key={b.text} className="flex items-center gap-3">
              <span className="text-lg">{b.icon}</span>
              <p className="text-sm text-white/50">{b.text}</p>
            </div>
          ))}
        </div>
      </div>
      <div className="relative z-10">
        <div className="flex items-center gap-2 bg-white/5 border border-white/10 rounded-xl px-4 py-3">
          <ShieldCheck className="w-4 h-4 text-green-400 flex-shrink-0" />
          <p className="text-xs text-white/40">SEBI compliant · Data encrypted · ISO 27001</p>
        </div>
      </div>
    </div>
  );

  return (
    <div className="min-h-screen flex">
      {LeftPanel}

      <div className="flex-1 bg-white flex flex-col">
        <div className="flex items-center justify-between px-8 py-5 border-b border-slate-100">
          <button onClick={() => navigate('/')} className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors">
            <ArrowLeft className="w-4 h-4" /> Back to Home
          </button>
          <p className="text-sm text-slate-400">Onboarded by your distributor</p>
        </div>

        <div className="flex-1 flex items-center justify-center p-8">
          <div className="w-full max-w-md">
            {sessionExpired && (
              <div className="mb-6 flex items-start gap-2.5 bg-amber-50 border border-amber-100 rounded-xl px-4 py-3">
                <AlertCircle className="w-4 h-4 text-amber-600 flex-shrink-0 mt-0.5" />
                <p className="text-sm text-amber-800">Your session expired, please sign in again.</p>
              </div>
            )}

            <motion.div className="mb-7" initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.35, ease: 'easeOut' }}>
              <h1 className="text-2xl font-semibold text-slate-800 mb-1">Investor sign in</h1>
              <p className="text-sm text-slate-500">Use your registered email and PAN to sign in.</p>
            </motion.div>

            {error && (
              <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }}
                className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                <p className="text-sm text-red-700">{error}</p>
              </motion.div>
            )}

            <div className="mb-5">
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                Email Address <span className="text-red-400">*</span>
              </label>
              <div className="relative">
                <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                <input type="email" value={email}
                  onChange={e => { setEmail(e.target.value); setError(''); }}
                  onKeyDown={e => e.key === 'Enter' && handleLogin()}
                  placeholder="you@example.com" autoFocus
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
              </div>
            </div>

            <div className="mb-6">
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                PAN <span className="text-red-400">*</span>
              </label>
              <div className="relative">
                <input type={showPan ? 'text' : 'password'} value={pan}
                  onChange={e => { setPan(e.target.value.toUpperCase()); setError(''); }}
                  onKeyDown={e => e.key === 'Enter' && handleLogin()}
                  placeholder="ABCDE1234F" maxLength={10}
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 pr-11 py-3 text-sm font-mono tracking-widest uppercase focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                <button type="button" onClick={() => setShowPan(s => !s)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600" aria-label={showPan ? 'Hide PAN' : 'Show PAN'}>
                  {showPan ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
              <p className="text-[11px] text-slate-400 mt-1">Your PAN is used as your password.</p>
            </div>

            <button onClick={handleLogin} disabled={loading || !email.trim() || !pan.trim()}
              className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] disabled:opacity-60 disabled:hover:translate-y-0 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
              {loading ? (<><Spinner />Signing in…</>) : (<><LogIn className="w-4 h-4" />Sign In</>)}
            </button>

            <div className="mt-6 rounded-xl border border-slate-100 bg-slate-50 px-4 py-3 text-center">
              <p className="text-xs text-slate-500 leading-relaxed">
                New to Platizio? Investors are onboarded by their distributor — you can&rsquo;t self-register.
                Ask your distributor to add you or send you the registration link.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
