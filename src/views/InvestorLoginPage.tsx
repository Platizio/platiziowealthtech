import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  AlertCircle, ShieldCheck, CheckCircle2, Smartphone,
  RefreshCw, MessageSquare, Mail, ArrowLeft,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch } from '../config/api';
import { useAppDispatch } from '../store/hooks';
import { setInvestorUser } from '../store/slices/investorAuthSlice';
import { normalizeInvestorUser } from '../types/investorAuth';

type OtpStep = 'send' | 'verify';
type LoginChannel = 'email' | 'mobile';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const Spinner = () => (
  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
  </svg>
);

/**
 * Passwordless investor login. No password field anywhere: enter email → request
 * a LOGIN-purpose OTP → enter the 6-box code → verify. On success the backend sets
 * the HttpOnly investor cookie and we go to /investor/onboarding. Mobile OTP is a
 * disabled "coming soon" toggle. The 6-box OTP handlers mirror LoginPage exactly.
 */
export default function InvestorLoginPage() {
  const navigate = useNavigate();
  const dispatch = useAppDispatch();
  const [searchParams] = useSearchParams();
  const sessionExpired = searchParams.get('reason') === 'session_expired';

  /** Post-login destination. Only same-origin /investor/* paths are honored so the
   *  return-to cannot be used to bounce the session somewhere unexpected. */
  const safeReturnTo = (() => {
    const raw = searchParams.get('returnTo');
    if (raw && raw.startsWith('/investor/')) return raw;
    return '/investor/dashboard';
  })();

  const [channel, setChannel] = useState<LoginChannel>('email');

  const [email, setEmail] = useState('');
  const [otpStep, setOtpStep] = useState<OtpStep>('send');
  const [otpDigits, setOtpDigits] = useState<string[]>(Array(6).fill(''));
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [devCode, setDevCode] = useState('');
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    if (otpStep === 'verify') {
      setTimeout(() => otpRefs.current[0]?.focus(), 120);
    }
  }, [otpStep]);

  useEffect(() => {
    if (countdown <= 0) return;
    const t = setTimeout(() => setCountdown(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [countdown]);

  const requestOtp = async (rawEmail: string) => {
    const res = await apiFetch('/investor-auth/otp/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: rawEmail, purpose: 'LOGIN' }),
      skipAuthRedirect: true,
    });
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      throw new Error(data?.message || 'Could not send the OTP. Please try again.');
    }
    return data;
  };

  const handleSendOtp = async () => {
    const id = email.trim().toLowerCase();
    if (!id) { setError('Please enter your email address.'); return; }
    if (!EMAIL_RE.test(id)) {
      setError('Please enter a valid email address. (Mobile OTP is coming soon.)');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const data = await requestOtp(id);
      setEmail(id);
      setOtpDigits(Array(6).fill(''));
      setDevCode(typeof data?.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data?.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      setOtpStep('verify');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not send the OTP. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleDigitChange = (i: number, val: string) => {
    if (!/^\d*$/.test(val)) return;
    const next = [...otpDigits];
    next[i] = val.slice(-1);
    setOtpDigits(next);
    setError('');
    if (val && i < 5) otpRefs.current[i + 1]?.focus();
  };

  const handleDigitKeyDown = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !otpDigits[i] && i > 0) otpRefs.current[i - 1]?.focus();
    if (e.key === 'Enter' && otpDigits.every(d => d)) handleVerifyOtp();
  };

  const handleDigitPaste = (e: React.ClipboardEvent) => {
    const paste = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (paste.length === 6) {
      setOtpDigits(paste.split(''));
      setTimeout(() => otpRefs.current[5]?.focus(), 0);
    }
    e.preventDefault();
  };

  const handleVerifyOtp = async () => {
    const entered = otpDigits.join('');
    if (entered.length < 6) { setError('Please enter the complete 6-digit OTP.'); return; }
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor-auth/login/otp/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim().toLowerCase(), code: entered }),
        skipAuthRedirect: true,
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error(data?.message || 'Incorrect or expired OTP. Please try again.');
      }
      const user = normalizeInvestorUser(data);
      if (user) dispatch(setInvestorUser(user));
      navigate(safeReturnTo, { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Incorrect OTP. Please check and try again.');
      setOtpDigits(Array(6).fill(''));
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } finally {
      setLoading(false);
    }
  };

  const handleResend = async () => {
    setError('');
    try {
      const data = await requestOtp(email.trim().toLowerCase());
      setOtpDigits(Array(6).fill(''));
      setDevCode(typeof data?.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data?.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not resend the OTP. Please try again.');
    }
  };

  const resetOtp = () => {
    setOtpStep('send');
    setOtpDigits(Array(6).fill(''));
    setError('');
    setCountdown(0);
    setDevCode('');
  };

  const LeftPanel = (
    <div className="hidden lg:flex w-[420px] bg-[#0B1B3E] flex-col justify-between p-12 flex-shrink-0 relative overflow-hidden">
      <div className="absolute top-0 right-0 w-64 h-64 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-0 left-0 w-48 h-48 bg-violet-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="relative z-10">
        <div className="flex items-center gap-3 mb-16">
          <div className="w-10 h-10 rounded-xl bg-white/10 border border-white/20 flex items-center justify-center text-white font-bold text-xl shadow-lg">
            P
          </div>
          <span className="text-white font-bold text-xl tracking-tight">Platizio</span>
        </div>
        <h2 className="text-3xl font-bold text-white mb-3 leading-snug">Investor Portal</h2>
        <p className="text-blue-200/60 text-sm leading-relaxed mb-10">
          Sign in securely with a one-time passcode — no password needed. Review and approve
          your onboarding submission before it is finalized.
        </p>
        <div className="space-y-4">
          {[
            { icon: '🔐', text: 'Passwordless one-time-passcode login' },
            { icon: '📄', text: 'Review your exact onboarding submission' },
            { icon: '✅', text: 'Approve before your distributor finalizes' },
            { icon: '📨', text: 'Verify your email and contact details' },
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
          <button
            onClick={() => navigate('/')}
            className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
          >
            <ArrowLeft className="w-4 h-4" /> Back to Home
          </button>
          <p className="text-sm text-slate-500">
            New investor?{' '}
            <button onClick={() => navigate('/investor/signup')} className="text-blue-600 font-semibold hover:underline">
              Create account
            </button>
          </p>
        </div>

        <div className="flex-1 flex items-center justify-center p-8">
          <div className="w-full max-w-md">

            {sessionExpired && (
              <div className="mb-6 flex items-start gap-2.5 bg-amber-50 border border-amber-100 rounded-xl px-4 py-3">
                <AlertCircle className="w-4 h-4 text-amber-600 flex-shrink-0 mt-0.5" />
                <p className="text-sm text-amber-800">Your session expired, please sign in again.</p>
              </div>
            )}

            <motion.div
              className="mb-7"
              initial={{ opacity: 0, y: -8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.35, ease: 'easeOut' }}
            >
              <h1 className="text-2xl font-semibold text-slate-800 mb-1">Investor sign in</h1>
              <p className="text-sm text-slate-500">Sign in with a one-time passcode. No password needed.</p>
            </motion.div>

            {/* Channel toggle — mobile is disabled "coming soon" */}
            <div className="flex bg-slate-100 rounded-xl p-1 mb-8 gap-1">
              <button
                onClick={() => setChannel('email')}
                className={`flex-1 flex items-center justify-center gap-2 py-2.5 text-sm font-medium rounded-lg transition-all duration-200
                  ${channel === 'email' ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}
              >
                <Mail className="w-3.5 h-3.5" /> Email OTP
              </button>
              <button
                type="button"
                disabled
                title="Mobile OTP is coming soon"
                aria-disabled="true"
                className="flex-1 flex items-center justify-center gap-2 py-2.5 text-sm font-medium rounded-lg text-slate-400 cursor-not-allowed"
              >
                <Smartphone className="w-3.5 h-3.5" /> Mobile OTP
                <span className="ml-1 text-[9px] font-bold uppercase tracking-wide bg-slate-200 text-slate-500 rounded px-1.5 py-0.5">
                  Soon
                </span>
              </button>
            </div>

            <AnimatePresence mode="wait">
              {otpStep === 'send' && (
                <motion.div key="otp-send" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, x: -16 }}>
                  <div className="mb-6 bg-blue-50 border border-blue-100 rounded-xl px-4 py-3 flex items-start gap-3">
                    <span className="text-blue-400 mt-0.5 text-sm">ℹ</span>
                    <p className="text-xs text-blue-700 leading-relaxed">
                      We'll email a 6-digit one-time passcode to your registered address. No password needed.
                    </p>
                  </div>

                  <AnimatePresence>
                    {error && (
                      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                        className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                        <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                        <p className="text-sm text-red-700">{error}</p>
                      </motion.div>
                    )}
                  </AnimatePresence>

                  <div className="mb-6">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      Email Address <span className="text-red-400">*</span>
                    </label>
                    <input type="email" value={email}
                      onChange={e => { setEmail(e.target.value); setError(''); }}
                      onKeyDown={e => e.key === 'Enter' && handleSendOtp()}
                      placeholder="you@example.com"
                      autoFocus
                      className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                    <p className="text-[11px] text-slate-400 mt-1">
                      Enter your registered email address. Mobile OTP is coming soon.
                    </p>
                  </div>

                  <button onClick={handleSendOtp} disabled={loading || !email.trim()}
                    className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] disabled:opacity-60 disabled:hover:translate-y-0 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                    {loading
                      ? (<><Spinner />Sending OTP…</>)
                      : (<><MessageSquare className="w-4 h-4" />Send OTP</>)}
                  </button>

                  <p className="text-center text-xs text-slate-400 mt-5">
                    New investor?{' '}
                    <button onClick={() => navigate('/investor/signup')} className="text-blue-500 hover:underline font-medium">
                      Create an account
                    </button>
                  </p>
                </motion.div>
              )}

              {otpStep === 'verify' && (
                <motion.div key="otp-verify" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>
                  <div className="flex items-center gap-3 bg-green-50 border border-green-200 rounded-xl px-4 py-3 mb-6">
                    <div className="w-8 h-8 rounded-full bg-green-100 flex items-center justify-center flex-shrink-0">
                      <CheckCircle2 className="w-4 h-4 text-green-600" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-xs font-semibold text-slate-700">OTP sent to your email</p>
                      <p className="text-[11px] text-slate-500 truncate">{email}</p>
                    </div>
                    <button onClick={resetOtp} className="text-[11px] text-blue-500 hover:underline font-medium flex-shrink-0">
                      Change
                    </button>
                  </div>

                  {devCode && (
                    <div className="bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 mb-5 text-left">
                      <p className="text-[11px] font-semibold text-amber-900 mb-1">Dev OTP code</p>
                      <p className="font-mono text-base tracking-widest text-amber-800">{devCode}</p>
                    </div>
                  )}

                  <AnimatePresence>
                    {error && (
                      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                        className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                        <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                        <p className="text-sm text-red-700">{error}</p>
                      </motion.div>
                    )}
                  </AnimatePresence>

                  <div className="mb-2">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">
                      Enter 6-Digit OTP
                    </label>
                    <div className="flex gap-2 justify-between" onPaste={handleDigitPaste}>
                      {otpDigits.map((digit, i) => (
                        <input
                          key={i}
                          ref={el => { otpRefs.current[i] = el; }}
                          type="text"
                          inputMode="numeric"
                          maxLength={1}
                          value={digit}
                          onChange={e => handleDigitChange(i, e.target.value)}
                          onKeyDown={e => handleDigitKeyDown(i, e)}
                          className={`w-12 h-14 text-center text-2xl font-bold rounded-xl border-2 outline-none transition-all
                            ${digit
                              ? 'border-blue-500 bg-blue-50 text-blue-700'
                              : 'border-slate-200 bg-slate-50 text-slate-700'}
                            focus:border-blue-500 focus:bg-blue-50 focus:ring-2 focus:ring-blue-100`}
                        />
                      ))}
                    </div>
                  </div>

                  <div className="flex items-center justify-end mb-6 mt-2.5">
                    {countdown > 0 ? (
                      <p className="text-xs text-slate-400">
                        Resend OTP in <span className="font-semibold text-slate-600">{countdown}s</span>
                      </p>
                    ) : (
                      <button onClick={handleResend}
                        className="flex items-center gap-1.5 text-xs text-blue-500 hover:text-blue-700 font-medium transition-colors">
                        <RefreshCw className="w-3 h-3" /> Resend OTP
                      </button>
                    )}
                  </div>

                  <button onClick={handleVerifyOtp} disabled={otpDigits.some(d => !d) || loading}
                    className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] disabled:opacity-60 disabled:hover:translate-y-0 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                    {loading ? (<><Spinner />Verifying…</>) : (<>Verify &amp; Sign In</>)}
                  </button>

                  <p className="text-center text-xs text-slate-400 mt-5">
                    <button onClick={resetOtp} className="text-blue-500 hover:underline font-medium">
                      ← Use a different email
                    </button>
                  </p>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}
