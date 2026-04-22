import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Eye, EyeOff, ArrowLeft, AlertCircle, ShieldCheck,
  CheckSquare, Square, Mail, KeyRound, ChevronLeft,
  CheckCircle2, Smartphone, RefreshCw, MessageSquare,
} from 'lucide-react';

const REMEMBER_KEY = 'apex_remembered_email';

type LoginMode = 'password' | 'otp';
type OtpStep   = 'send' | 'verify';
type ForgotStatus = 'idle' | 'loading' | 'sent' | 'notfound';

export default function LoginPage({
  onLogin,
  onSignUp,
  onBack,
}: {
  onLogin:  () => void;
  onSignUp: () => void;
  onBack:   () => void;
}) {
  /* ── Mode ───────────────────────────────────────────────────────────── */
  const [loginMode, setLoginMode] = useState<LoginMode>('password');

  /* ── Password state ─────────────────────────────────────────────────── */
  const [email,      setEmail]      = useState('');
  const [arn,        setArn]        = useState('');
  const [showArn,    setShowArn]    = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [pwError,    setPwError]    = useState('');
  const [pwLoading,  setPwLoading]  = useState(false);

  /* ── Forgot state ───────────────────────────────────────────────────── */
  const [forgotMode,   setForgotMode]   = useState(false);
  const [forgotEmail,  setForgotEmail]  = useState('');
  const [forgotStatus, setForgotStatus] = useState<ForgotStatus>('idle');
  const [recoveredArn, setRecoveredArn] = useState('');

  /* ── OTP state ──────────────────────────────────────────────────────── */
  const [otpId,       setOtpId]       = useState('');         // identifier entered
  const [otpStep,     setOtpStep]     = useState<OtpStep>('send');
  const [otpDigits,   setOtpDigits]   = useState<string[]>(Array(6).fill(''));
  const [otpGenerated,setOtpGenerated]= useState('');
  const [otpUser,     setOtpUser]     = useState<any>(null);
  const [otpError,    setOtpError]    = useState('');
  const [otpLoading,  setOtpLoading]  = useState(false);
  const [countdown,   setCountdown]   = useState(0);
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  /* ── Effects ────────────────────────────────────────────────────────── */
  // Pre-fill remembered email
  useEffect(() => {
    const saved = localStorage.getItem(REMEMBER_KEY);
    if (saved) { setEmail(saved); setRememberMe(true); }
  }, []);

  // Auto-focus first OTP box after OTP is sent
  useEffect(() => {
    if (otpStep === 'verify') {
      setTimeout(() => otpRefs.current[0]?.focus(), 120);
    }
  }, [otpStep]);

  // Resend countdown ticker
  useEffect(() => {
    if (countdown <= 0) return;
    const t = setTimeout(() => setCountdown(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [countdown]);

  /* ── Helpers: look up users ─────────────────────────────────────────── */
  const getUsers = (): any[] => {
    try { return JSON.parse(localStorage.getItem('apex_users') || '[]'); }
    catch { return []; }
  };

  /* ── Password login ─────────────────────────────────────────────────── */
  const handlePasswordLogin = () => {
    setPwError('');
    const em = email.trim().toLowerCase();
    const ar = arn.trim();
    if (!em || !ar) { setPwError('Please enter both Email Address and ARN Number.'); return; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(em)) {
      setPwError('Email address format is invalid (e.g. you@example.com).');
      return;
    }
    setPwLoading(true);
    setTimeout(() => {
      const user = getUsers().find(
        u => u.email?.toLowerCase() === em && u.arn?.toLowerCase() === ar.toLowerCase(),
      );
      if (user) {
        if (rememberMe) localStorage.setItem(REMEMBER_KEY, em);
        else            localStorage.removeItem(REMEMBER_KEY);
        sessionStorage.setItem('apex_session', JSON.stringify(user));
        onLogin();
      } else {
        setPwError('No account found with these credentials. Check your email and ARN, or sign up.');
      }
      setPwLoading(false);
    }, 600);
  };

  /* ── Forgot ARN ─────────────────────────────────────────────────────── */
  const handleForgotSubmit = () => {
    const em = forgotEmail.trim().toLowerCase();
    if (!em || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(em)) return;
    setForgotStatus('loading');
    setTimeout(() => {
      const match = getUsers().find(u => u.email?.toLowerCase() === em);
      if (match) { setRecoveredArn(match.arn); setForgotStatus('sent'); }
      else setForgotStatus('notfound');
    }, 800);
  };

  const resetForgot = () => {
    setForgotMode(false); setForgotEmail('');
    setForgotStatus('idle'); setRecoveredArn('');
  };

  /* ── OTP: send ──────────────────────────────────────────────────────── */
  const handleSendOtp = () => {
    const id = otpId.trim();
    if (!id) { setOtpError('Please enter your email or mobile number.'); return; }
    setOtpLoading(true);
    setTimeout(() => {
      const normalised = id.toLowerCase();
      const mobile     = id.replace(/^\+91\s?/, '').replace(/\s/g, '');
      const user = getUsers().find(
        u => u.email?.toLowerCase() === normalised || u.mobile === mobile,
      );
      if (user) {
        const code = Math.floor(100000 + Math.random() * 900000).toString();
        setOtpGenerated(code);
        setOtpUser(user);
        setOtpStep('verify');
        setCountdown(30);
      } else {
        setOtpError('No account found. Check your email / mobile, or sign up.');
      }
      setOtpLoading(false);
    }, 800);
  };

  /* ── OTP: digit input handlers ──────────────────────────────────────── */
  const handleDigitChange = (i: number, val: string) => {
    if (!/^\d*$/.test(val)) return;
    const next = [...otpDigits];
    next[i] = val.slice(-1);
    setOtpDigits(next);
    setOtpError('');
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

  /* ── OTP: verify ────────────────────────────────────────────────────── */
  const handleVerifyOtp = () => {
    const entered = otpDigits.join('');
    if (entered.length < 6) { setOtpError('Please enter the complete 6-digit OTP.'); return; }
    if (entered === otpGenerated) {
      sessionStorage.setItem('apex_session', JSON.stringify(otpUser));
      onLogin();
    } else {
      setOtpError('Incorrect OTP. Please check and try again.');
      setOtpDigits(Array(6).fill(''));
      setTimeout(() => otpRefs.current[0]?.focus(), 50);
    }
  };

  /* ── OTP: resend ────────────────────────────────────────────────────── */
  const handleResend = () => {
    const code = Math.floor(100000 + Math.random() * 900000).toString();
    setOtpGenerated(code);
    setOtpDigits(Array(6).fill(''));
    setOtpError('');
    setCountdown(30);
    setTimeout(() => otpRefs.current[0]?.focus(), 50);
  };

  /* ── OTP: reset to step 1 ───────────────────────────────────────────── */
  const resetOtp = () => {
    setOtpStep('send'); setOtpId('');
    setOtpDigits(Array(6).fill('')); setOtpGenerated('');
    setOtpUser(null); setOtpError(''); setCountdown(0);
  };

  /* ── Spinner helper ─────────────────────────────────────────────────── */
  const Spinner = () => (
    <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
    </svg>
  );

  /* ── Left branding panel ────────────────────────────────────────────── */
  const leftTitle  = forgotMode ? 'Recover Access'
                   : loginMode === 'otp' ? 'Quick OTP Login'
                   : 'Welcome Back';
  const leftSubtitle = forgotMode
    ? "Enter your registered email and we'll retrieve your ARN credentials."
    : loginMode === 'otp'
    ? 'No password needed — enter your email or mobile to receive a one-time passcode.'
    : 'Sign in to access your distributor dashboard, manage investors, and track earnings.';

  const LeftPanel = (
    <div className="hidden lg:flex w-[420px] bg-[#0B1B3E] flex-col justify-between p-12 flex-shrink-0 relative overflow-hidden">
      <div className="absolute top-0 right-0 w-64 h-64 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute bottom-0 left-0 w-48 h-48 bg-violet-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="relative z-10">
        <div className="flex items-center gap-3 mb-16">
          <div className="w-10 h-10 rounded-xl bg-white/10 border border-white/20 flex items-center justify-center text-white font-bold text-xl shadow-lg">
            A
          </div>
          <span className="text-white font-bold text-xl tracking-tight">Apex Wealth</span>
        </div>
        <h2 className="text-3xl font-bold text-white mb-3 leading-snug">{leftTitle}</h2>
        <p className="text-blue-200/60 text-sm leading-relaxed mb-10">{leftSubtitle}</p>
        <div className="space-y-4">
          {[
            { icon: '📊', text: 'Real-time AUM and earnings dashboard' },
            { icon: '👥', text: 'Manage your full investor portfolio' },
            { icon: '🔔', text: 'SIP alerts, KYC reminders & notifications' },
            { icon: '📈', text: 'Lead pipeline & conversion tracking' },
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

  /* ════════════════════════════════════════════════════════════════════
     FORGOT PASSWORD VIEW
  ════════════════════════════════════════════════════════════════════ */
  if (forgotMode) {
    return (
      <div className="min-h-screen flex">
        {LeftPanel}
        <div className="flex-1 bg-white flex flex-col">
          <div className="flex items-center justify-between px-8 py-5 border-b border-slate-100">
            <button onClick={resetForgot} className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors">
              <ChevronLeft className="w-4 h-4" /> Back to Sign In
            </button>
            <p className="text-sm text-slate-500">
              New distributor?{' '}
              <button onClick={onSignUp} className="text-blue-600 font-semibold hover:underline">Sign Up</button>
            </p>
          </div>
          <div className="flex-1 flex items-center justify-center p-8">
            <motion.div key="forgot" initial={{ opacity: 0, y: 16 }} animate={{ opacity: 1, y: 0 }} className="w-full max-w-md">
              <div className="w-14 h-14 rounded-2xl bg-blue-50 border border-blue-100 flex items-center justify-center mb-6">
                <KeyRound className="w-6 h-6 text-blue-500" />
              </div>
              <h1 className="text-2xl font-semibold text-slate-800 mb-1">Forgot your credentials?</h1>
              <p className="text-sm text-slate-500 mb-8">Enter your registered email and we'll retrieve your ARN number.</p>

              <AnimatePresence mode="wait">
                {forgotStatus === 'sent' && (
                  <motion.div key="sent" initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }} className="bg-green-50 border border-green-200 rounded-2xl p-6 text-center">
                    <div className="w-12 h-12 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-4">
                      <CheckCircle2 className="w-6 h-6 text-green-500" />
                    </div>
                    <p className="text-sm font-semibold text-slate-700 mb-1">Account found!</p>
                    <p className="text-xs text-slate-500 mb-4">Your registered ARN number is:</p>
                    <div className="inline-flex items-center gap-2 bg-white border border-green-200 rounded-xl px-4 py-2.5 mb-5">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">ARN</span>
                      <span className="text-sm font-mono font-bold text-slate-800">{recoveredArn}</span>
                    </div>
                    <p className="text-xs text-slate-400 mb-5">Use this ARN along with your email to sign in.</p>
                    <button onClick={resetForgot} className="w-full py-3 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors">
                      Back to Sign In
                    </button>
                  </motion.div>
                )}
                {forgotStatus === 'notfound' && (
                  <motion.div key="notfound" initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }} className="bg-amber-50 border border-amber-200 rounded-2xl p-6 text-center">
                    <div className="w-12 h-12 rounded-full bg-amber-100 flex items-center justify-center mx-auto mb-4">
                      <Mail className="w-6 h-6 text-amber-500" />
                    </div>
                    <p className="text-sm font-semibold text-slate-700 mb-2">No account found</p>
                    <p className="text-xs text-slate-500 leading-relaxed mb-5">
                      We couldn't find an account for <span className="font-semibold text-slate-700">{forgotEmail}</span>.
                    </p>
                    <div className="flex gap-3">
                      <button onClick={() => setForgotStatus('idle')} className="flex-1 py-2.5 border border-slate-200 text-slate-600 font-medium text-sm rounded-xl hover:bg-slate-50 transition-colors">Try Again</button>
                      <button onClick={onSignUp} className="flex-1 py-2.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors">Sign Up</button>
                    </div>
                  </motion.div>
                )}
                {(forgotStatus === 'idle' || forgotStatus === 'loading') && (
                  <motion.div key="forgot-input" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
                    <div className="mb-6">
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                        Registered Email Address <span className="text-red-400">*</span>
                      </label>
                      <input type="email" value={forgotEmail} onChange={e => setForgotEmail(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && handleForgotSubmit()}
                        placeholder="you@example.com" autoFocus
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                    </div>
                    <button onClick={handleForgotSubmit} disabled={forgotStatus === 'loading' || !forgotEmail.trim()}
                      className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                      {forgotStatus === 'loading' ? (<><Spinner />Looking up…</>) : 'Retrieve My ARN'}
                    </button>
                    <p className="text-center text-xs text-slate-400 mt-5">
                      Remember your credentials?{' '}
                      <button onClick={resetForgot} className="text-blue-500 hover:underline font-medium">Sign in instead</button>
                    </p>
                  </motion.div>
                )}
              </AnimatePresence>
            </motion.div>
          </div>
        </div>
      </div>
    );
  }

  /* ════════════════════════════════════════════════════════════════════
     MAIN LOGIN VIEW — password + OTP tabs
  ════════════════════════════════════════════════════════════════════ */
  return (
    <div className="min-h-screen flex">
      {LeftPanel}

      <div className="flex-1 bg-white flex flex-col">
        {/* Top bar */}
        <div className="flex items-center justify-between px-8 py-5 border-b border-slate-100">
          <button onClick={onBack} className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors">
            <ArrowLeft className="w-4 h-4" /> Back to Home
          </button>
          <p className="text-sm text-slate-500">
            New distributor?{' '}
            <button onClick={onSignUp} className="text-blue-600 font-semibold hover:underline">Sign Up</button>
          </p>
        </div>

        <div className="flex-1 flex items-center justify-center p-8">
          <div className="w-full max-w-md">

            {/* Heading */}
            <div className="mb-7">
              <h1 className="text-2xl font-semibold text-slate-800 mb-1">Sign in to your account</h1>
              <p className="text-sm text-slate-500">Choose how you'd like to sign in.</p>
            </div>

            {/* ── Tab switcher ─────────────────────────────────────────── */}
            <div className="flex bg-slate-100 rounded-xl p-1 mb-8 gap-1">
              <button
                onClick={() => { setLoginMode('password'); setPwError(''); }}
                className={`flex-1 flex items-center justify-center gap-2 py-2.5 text-sm font-medium rounded-lg transition-all duration-200
                  ${loginMode === 'password' ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}
              >
                <KeyRound className="w-3.5 h-3.5" /> Password
              </button>
              <button
                onClick={() => { setLoginMode('otp'); setPwError(''); }}
                className={`flex-1 flex items-center justify-center gap-2 py-2.5 text-sm font-medium rounded-lg transition-all duration-200
                  ${loginMode === 'otp' ? 'bg-white text-slate-800 shadow-sm' : 'text-slate-500 hover:text-slate-700'}`}
              >
                <Smartphone className="w-3.5 h-3.5" /> OTP
              </button>
            </div>

            <AnimatePresence mode="wait">

              {/* ══════════════════ PASSWORD TAB ══════════════════ */}
              {loginMode === 'password' && (
                <motion.div key="pw-tab"
                  initial={{ opacity: 0, x: -12 }} animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: -12 }} transition={{ duration: 0.18 }}
                >
                  {/* Demo hint */}
                  <div className="mb-6 bg-blue-50 border border-blue-100 rounded-xl px-4 py-3 flex items-start gap-3">
                    <span className="text-blue-400 mt-0.5 text-sm">ℹ</span>
                    <p className="text-xs text-blue-700 leading-relaxed">
                      <span className="font-semibold">Demo credentials:</span>{' '}
                      Email: <span className="font-mono font-bold">aditya@apexwealth.in</span>&nbsp;|&nbsp;
                      ARN: <span className="font-mono font-bold">ARN-102943</span>
                    </p>
                  </div>

                  {/* Error */}
                  <AnimatePresence>
                    {pwError && (
                      <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                        className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                        <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                        <p className="text-sm text-red-700">{pwError}</p>
                      </motion.div>
                    )}
                  </AnimatePresence>

                  {/* Email */}
                  <div className="mb-5">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      Email Address <span className="text-red-400">*</span>
                    </label>
                    <input type="email" value={email}
                      onChange={e => { setEmail(e.target.value); setPwError(''); }}
                      onKeyDown={e => e.key === 'Enter' && handlePasswordLogin()}
                      placeholder="you@example.com"
                      className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                  </div>

                  {/* ARN */}
                  <div className="mb-5">
                    <div className="flex items-center justify-between mb-1.5">
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider">
                        ARN Number <span className="text-red-400">*</span>
                      </label>
                      <button type="button" onClick={() => { setForgotMode(true); setForgotEmail(email); }}
                        className="text-[11px] text-blue-500 hover:text-blue-700 font-medium hover:underline transition-colors">
                        Forgot ARN?
                      </button>
                    </div>
                    <div className="relative">
                      <input type={showArn ? 'text' : 'password'} value={arn}
                        onChange={e => { setArn(e.target.value); setPwError(''); }}
                        onKeyDown={e => e.key === 'Enter' && handlePasswordLogin()}
                        placeholder="e.g. ARN-102943"
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 pr-11 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                      <button type="button" onClick={() => setShowArn(p => !p)}
                        className="absolute right-3 top-3 text-slate-400 hover:text-slate-600 transition-colors">
                        {showArn ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                      </button>
                    </div>
                    <p className="text-[11px] text-slate-400 mt-1">Format: ARN- followed by digits (e.g. ARN-102943)</p>
                  </div>

                  {/* Remember me */}
                  <div className="mb-7">
                    <button type="button" onClick={() => setRememberMe(p => !p)}
                      className="flex items-center gap-2.5 group select-none">
                      {rememberMe
                        ? <CheckSquare className="w-4 h-4 text-blue-600" />
                        : <Square className="w-4 h-4 text-slate-300 group-hover:text-slate-400 transition-colors" />}
                      <span className="text-sm text-slate-600 group-hover:text-slate-800 transition-colors">
                        Remember me on this device
                      </span>
                    </button>
                  </div>

                  {/* Submit */}
                  <button onClick={handlePasswordLogin} disabled={pwLoading}
                    className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                    {pwLoading ? (<><Spinner />Signing in…</>) : 'Sign In'}
                  </button>

                  <p className="text-center text-xs text-slate-400 mt-6 leading-relaxed">
                    By signing in you agree to our{' '}
                    <span className="text-blue-500 cursor-pointer hover:underline">Terms of Service</span> and{' '}
                    <span className="text-blue-500 cursor-pointer hover:underline">Privacy Policy</span>.
                  </p>
                </motion.div>
              )}

              {/* ══════════════════ OTP TAB ══════════════════ */}
              {loginMode === 'otp' && (
                <motion.div key="otp-tab"
                  initial={{ opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: 12 }} transition={{ duration: 0.18 }}
                >
                  <AnimatePresence mode="wait">

                    {/* ─── Step 1: Enter identifier ─── */}
                    {otpStep === 'send' && (
                      <motion.div key="otp-send" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0, x: -16 }}>

                        {/* Demo hint */}
                        <div className="mb-6 bg-blue-50 border border-blue-100 rounded-xl px-4 py-3 flex items-start gap-3">
                          <span className="text-blue-400 mt-0.5 text-sm">ℹ</span>
                          <p className="text-xs text-blue-700 leading-relaxed">
                            <span className="font-semibold">Demo accounts:</span>{' '}
                            Email: <span className="font-mono font-bold">aditya@apexwealth.in</span>&nbsp;|&nbsp;
                            Mobile: <span className="font-mono font-bold">9876543210</span>
                          </p>
                        </div>

                        {/* Error */}
                        <AnimatePresence>
                          {otpError && (
                            <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                              className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                              <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                              <p className="text-sm text-red-700">{otpError}</p>
                            </motion.div>
                          )}
                        </AnimatePresence>

                        <div className="mb-6">
                          <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                            Email or Mobile Number <span className="text-red-400">*</span>
                          </label>
                          <input type="text" value={otpId}
                            onChange={e => { setOtpId(e.target.value); setOtpError(''); }}
                            onKeyDown={e => e.key === 'Enter' && handleSendOtp()}
                            placeholder="e.g. you@example.com or 9876543210"
                            autoFocus
                            className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" />
                          <p className="text-[11px] text-slate-400 mt-1">
                            Enter your registered email address or 10-digit mobile number.
                          </p>
                        </div>

                        <button onClick={handleSendOtp} disabled={otpLoading || !otpId.trim()}
                          className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                          {otpLoading
                            ? (<><Spinner />Sending OTP…</>)
                            : (<><MessageSquare className="w-4 h-4" />Send OTP</>)}
                        </button>

                        <p className="text-center text-xs text-slate-400 mt-5">
                          Prefer password login?{' '}
                          <button onClick={() => setLoginMode('password')} className="text-blue-500 hover:underline font-medium">
                            Use credentials
                          </button>
                        </p>
                      </motion.div>
                    )}

                    {/* ─── Step 2: Enter OTP ─── */}
                    {otpStep === 'verify' && (
                      <motion.div key="otp-verify" initial={{ opacity: 0, x: 20 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -20 }}>

                        {/* Sent-to confirmation */}
                        <div className="flex items-center gap-3 bg-green-50 border border-green-200 rounded-xl px-4 py-3 mb-6">
                          <div className="w-8 h-8 rounded-full bg-green-100 flex items-center justify-center flex-shrink-0">
                            <CheckCircle2 className="w-4 h-4 text-green-600" />
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-semibold text-slate-700">OTP sent to your registered contact</p>
                            <p className="text-[11px] text-slate-500 truncate">{otpId}</p>
                          </div>
                          <button onClick={resetOtp} className="text-[11px] text-blue-500 hover:underline font-medium flex-shrink-0">
                            Change
                          </button>
                        </div>

                        {/* Demo OTP reveal */}
                        <div className="mb-6 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 flex items-center gap-3">
                          <span className="text-amber-500 text-base flex-shrink-0">⚡</span>
                          <p className="text-xs text-amber-800 leading-relaxed">
                            <span className="font-semibold">Demo — your OTP is: </span>
                            <span className="font-mono font-bold text-amber-900 tracking-[0.3em]">{otpGenerated}</span>
                          </p>
                        </div>

                        {/* Error */}
                        <AnimatePresence>
                          {otpError && (
                            <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                              className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                              <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                              <p className="text-sm text-red-700">{otpError}</p>
                            </motion.div>
                          )}
                        </AnimatePresence>

                        {/* 6-digit boxes */}
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

                        {/* Resend row */}
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

                        {/* Verify button */}
                        <button onClick={handleVerifyOtp} disabled={otpDigits.some(d => !d)}
                          className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-60 disabled:cursor-not-allowed shadow-lg">
                          Verify &amp; Sign In
                        </button>

                        <p className="text-center text-xs text-slate-400 mt-5">
                          <button onClick={resetOtp} className="text-blue-500 hover:underline font-medium">
                            ← Use a different email / mobile
                          </button>
                        </p>
                      </motion.div>
                    )}

                  </AnimatePresence>
                </motion.div>
              )}

            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}
