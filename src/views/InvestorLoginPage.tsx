import React, { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  AlertCircle, ShieldCheck, ArrowLeft, Mail, LogIn, MessageSquare, RefreshCw, Smartphone,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch } from '../config/api';
import { useAppDispatch } from '../store/hooks';
import { setInvestorUser } from '../store/slices/investorAuthSlice';
import { normalizeInvestorUser } from '../types/investorAuth';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MOBILE_RE = /^\d{10,13}$/;

type Channel = 'email' | 'mobile';

const safeInvestorReturnTo = (value: string | null) => {
  if (!value) return '/investor/dashboard';
  if (!value.startsWith('/investor')) return '/investor/dashboard';
  if (value.startsWith('/investor/login') || value.startsWith('/investor/signup')) {
    return '/investor/dashboard';
  }
  if (value.startsWith('//') || value.includes('://')) return '/investor/dashboard';
  return value;
};

const Spinner = () => (
  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
  </svg>
);

/** Maps HTTP-level failures to honest copy — never blames the OTP for a transport failure. */
const requestFailureMessage = (status: number, serverMessage?: string, fallback?: string) => {
  if (status === 429) return serverMessage || 'Too many attempts. Please wait ~60 seconds and try again.';
  if (status >= 500) {
    return `Can't reach the backend (HTTP ${status}). The API server may be down or on a different port than the app proxies to — start it and retry.`;
  }
  return serverMessage || fallback || `Request failed (HTTP ${status}).`;
};

/**
 * Investor login — passwordless, OTP-only. Two channels:
 *
 *   Email OTP (default): POST /investor-auth/otp/request {email, purpose:"LOGIN"}
 *   → 6-digit code → POST /investor-auth/login/otp/verify {email, code}.
 *
 *   Mobile OTP (DEMO): POST /investor-auth/login/mobile/otp/request {mobileNumber}
 *   → 6-digit code → POST /investor-auth/login/mobile/otp/verify {mobileNumber, code}.
 *   SMS delivery is simulated server-side (code 000000 always works in the demo)
 *   until MSG91 is integrated.
 *
 * On success the backend sets the HttpOnly investor cookie and returns the investor
 * auth payload; we store it via setInvestorUser and go to the dashboard.
 */
export default function InvestorLoginPage() {
  const navigate = useNavigate();
  const dispatch = useAppDispatch();
  const [searchParams] = useSearchParams();
  const sessionExpired = searchParams.get('reason') === 'session_expired';

  const [channel, setChannel] = useState<Channel>('email');
  const [email, setEmail] = useState('');
  const [mobileNumber, setMobileNumber] = useState('');

  const [sent, setSent] = useState(false);
  const [otpDigits, setOtpDigits] = useState<string[]>(Array(6).fill(''));
  const [devCode, setDevCode] = useState('');
  const [countdown, setCountdown] = useState(0);

  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    if (sent) setTimeout(() => otpRefs.current[0]?.focus(), 120);
  }, [sent]);

  useEffect(() => {
    if (countdown <= 0) return;
    const t = setTimeout(() => setCountdown(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [countdown]);

  const switchChannel = (next: Channel) => {
    if (next === channel) return;
    setChannel(next);
    setSent(false);
    setOtpDigits(Array(6).fill(''));
    setDevCode('');
    setCountdown(0);
    setError('');
  };

  const requestOtp = async () => {
    if (channel === 'email') {
      const id = email.trim().toLowerCase();
      const res = await apiFetch('/investor-auth/otp/request', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: id, purpose: 'LOGIN' }),
        skipAuthRedirect: true,
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error(requestFailureMessage(res.status, data?.message, 'Could not send the sign-in code. Please try again.'));
      }
      return data;
    }
    // MSG91 STUB: mobile login OTP is simulated server-side (code 000000 always
    // works in the demo); swap to real MSG91-backed delivery when integrated.
    const res = await apiFetch('/investor-auth/login/mobile/otp/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mobileNumber: mobileNumber.trim() }),
      skipAuthRedirect: true,
    });
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      throw new Error(requestFailureMessage(res.status, data?.message, 'Could not send the sign-in code. Please try again.'));
    }
    return data;
  };

  const handleSend = async () => {
    if (channel === 'email') {
      const id = email.trim().toLowerCase();
      if (!id) { setError('Please enter your email address.'); return; }
      if (!EMAIL_RE.test(id)) { setError('Please enter a valid email address.'); return; }
      setEmail(id);
    } else {
      if (!MOBILE_RE.test(mobileNumber.trim())) { setError('Please enter a valid mobile number.'); return; }
    }
    setSending(true);
    setError('');
    try {
      const data = await requestOtp();
      setOtpDigits(Array(6).fill(''));
      setDevCode(typeof data?.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data?.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      setSent(true);
    } catch (e) {
      setError(
        e instanceof TypeError
          ? "Can't reach the server. Make sure the backend is running and the app points at the right port, then try again."
          : e instanceof Error ? e.message : 'Could not send the sign-in code. Please try again.',
      );
    } finally {
      setSending(false);
    }
  };

  const handleResend = async () => {
    setError('');
    try {
      const data = await requestOtp();
      setOtpDigits(Array(6).fill(''));
      setDevCode(typeof data?.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data?.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not resend the code. Please try again.');
    }
  };

  /* ── 6-box OTP handlers (mirror InvestorSignup) ── */
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
    if (e.key === 'Enter' && otpDigits.every(d => d)) void handleVerify();
  };

  const handleDigitPaste = (e: React.ClipboardEvent) => {
    const paste = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (paste.length === 6) {
      setOtpDigits(paste.split(''));
      setTimeout(() => otpRefs.current[5]?.focus(), 0);
    }
    e.preventDefault();
  };

  const handleVerify = async () => {
    const code = otpDigits.join('');
    if (code.length < 6) { setError('Please enter the complete 6-digit code.'); return; }
    setVerifying(true);
    setError('');
    try {
      // MSG91 STUB (mobile branch): verification runs against the simulated
      // server-side OTP store until MSG91 delivery is integrated.
      const res = channel === 'email'
        ? await apiFetch('/investor-auth/login/otp/verify', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: email.trim().toLowerCase(), code }),
          skipAuthRedirect: true,
        })
        : await apiFetch('/investor-auth/login/mobile/otp/verify', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ mobileNumber: mobileNumber.trim(), code }),
          skipAuthRedirect: true,
        });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        if (res.status === 401) {
          throw new Error(data?.message || 'Incorrect or expired code. Please check and try again.');
        }
        throw new Error(requestFailureMessage(res.status, data?.message, `Sign-in failed (HTTP ${res.status}).`));
      }
      // Same post-login handling as the previous flow: cookie is HttpOnly,
      // payload → Redux, then redirect.
      const user = normalizeInvestorUser(data);
      if (user) dispatch(setInvestorUser(user));
      navigate(safeInvestorReturnTo(searchParams.get('returnTo')), { replace: true });
    } catch (e) {
      const isNetworkError = e instanceof TypeError;
      setError(
        isNetworkError
          ? "Can't reach the server. Make sure the backend is running and the app points at the right port, then try again."
          : e instanceof Error ? e.message : 'Incorrect or expired code. Please check and try again.',
      );
      setOtpDigits(Array(6).fill(''));
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    } finally {
      setVerifying(false);
    }
  };

  const destination = channel === 'email' ? email : mobileNumber;

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
          Sign in with a one-time passcode sent to your email or mobile to review your portfolio,
          complete KYC, and approve your onboarding.
        </p>
        <div className="space-y-4">
          {[
            { icon: '🔐', text: 'Passwordless one-time-passcode sign in' },
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
              <p className="text-sm text-slate-500">We'll send a 6-digit one-time passcode — no password needed.</p>
            </motion.div>

            {/* Channel tabs */}
            <div className="mb-6 grid grid-cols-2 gap-1 rounded-xl bg-slate-100 p-1">
              <button
                type="button"
                onClick={() => switchChannel('email')}
                className={`flex items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-sm font-semibold transition-colors ${
                  channel === 'email' ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500 hover:text-slate-700'
                }`}
              >
                <Mail className="w-4 h-4" /> Email OTP
              </button>
              <button
                type="button"
                onClick={() => switchChannel('mobile')}
                className={`flex items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-sm font-semibold transition-colors ${
                  channel === 'mobile' ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500 hover:text-slate-700'
                }`}
              >
                <Smartphone className="w-4 h-4" /> Mobile OTP
                <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-700">Demo</span>
              </button>
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

            {!sent ? (
              <>
                {channel === 'email' ? (
                  <div className="mb-6">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      Email Address <span className="text-red-400">*</span>
                    </label>
                    <div className="relative">
                      <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                      <input type="email" value={email}
                        onChange={e => { setEmail(e.target.value); setError(''); }}
                        onKeyDown={e => e.key === 'Enter' && handleSend()}
                        placeholder="you@example.com" autoFocus
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                    </div>
                  </div>
                ) : (
                  <div className="mb-6">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      Mobile Number <span className="text-red-400">*</span>
                    </label>
                    <div className="relative">
                      <Smartphone className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                      <input type="tel" value={mobileNumber}
                        onChange={e => { setMobileNumber(e.target.value.replace(/\D/g, '').slice(0, 13)); setError(''); }}
                        onKeyDown={e => e.key === 'Enter' && handleSend()}
                        placeholder="9876543210" autoFocus
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl pl-10 pr-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                    </div>
                    <p className="text-[11px] text-slate-400 mt-1.5">
                      SMS OTP is simulated for now — real delivery arrives with MSG91. In this demo, code
                      {' '}<span className="font-mono font-semibold text-slate-600">000000</span> always works.
                    </p>
                  </div>
                )}

                <button onClick={() => void handleSend()} disabled={sending || (channel === 'email' ? !email.trim() : !mobileNumber.trim())}
                  className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] disabled:opacity-60 disabled:hover:translate-y-0 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                  {sending ? (<><Spinner />Sending code…</>) : (<><MessageSquare className="w-4 h-4" />Send OTP</>)}
                </button>
              </>
            ) : (
              <>
                <div className="flex items-center gap-3 bg-green-50 border border-green-200 rounded-xl px-4 py-3 mb-6">
                  <div className="w-8 h-8 rounded-full bg-green-100 flex items-center justify-center flex-shrink-0">
                    <MessageSquare className="w-4 h-4 text-green-600" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-xs font-semibold text-slate-700">
                      Sign-in code sent to your {channel === 'email' ? 'email' : 'mobile'}
                    </p>
                    <p className="text-[11px] text-slate-500 truncate">{destination}</p>
                  </div>
                  <button
                    onClick={() => { setSent(false); setOtpDigits(Array(6).fill('')); setDevCode(''); setError(''); }}
                    className="text-[11px] font-semibold text-blue-600 hover:underline flex-shrink-0"
                  >
                    Change
                  </button>
                </div>

                {channel === 'mobile' && (
                  <p className="mb-4 text-[11px] text-slate-400">
                    Demo mode: SMS delivery is simulated — code
                    {' '}<span className="font-mono font-semibold text-slate-600">000000</span> always works.
                  </p>
                )}

                {import.meta.env.DEV && devCode && (
                  <div className="bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 mb-5 text-left">
                    <p className="text-[11px] font-semibold text-amber-900 mb-1">Dev OTP code</p>
                    <p className="font-mono text-base tracking-widest text-amber-800">{devCode}</p>
                  </div>
                )}

                <div className="mb-2">
                  <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">
                    Enter 6-Digit Code
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
                      Resend code in <span className="font-semibold text-slate-600">{countdown}s</span>
                    </p>
                  ) : (
                    <button onClick={() => void handleResend()}
                      className="flex items-center gap-1.5 text-xs text-blue-500 hover:text-blue-700 font-medium transition-colors">
                      <RefreshCw className="w-3 h-3" /> Resend code
                    </button>
                  )}
                </div>

                <button onClick={() => void handleVerify()} disabled={verifying || otpDigits.some(d => !d)}
                  className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] disabled:opacity-60 disabled:hover:translate-y-0 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                  {verifying ? (<><Spinner />Signing in…</>) : (<><LogIn className="w-4 h-4" />Verify &amp; Sign In</>)}
                </button>
              </>
            )}

            <div className="mt-6 rounded-xl border border-slate-100 bg-slate-50 px-4 py-3 text-center">
              <p className="text-xs text-slate-500 leading-relaxed">
                New to Platizio? Investors are onboarded by their distributor — you can&rsquo;t self-register.
                Ask your distributor to add you or send you the registration link.
              </p>
              <p className="mt-1.5 text-xs text-slate-500">
                Already received your distributor&rsquo;s invite?{' '}
                <button onClick={() => navigate('/investor/signup')} className="font-semibold text-blue-600 hover:underline">
                  Complete your registration
                </button>
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
