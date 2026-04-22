import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { Eye, EyeOff, ArrowLeft, AlertCircle, ShieldCheck, CheckSquare, Square, Mail, KeyRound, ChevronLeft, CheckCircle2 } from 'lucide-react';

const REMEMBER_KEY = 'apex_remembered_email';

export default function LoginPage({
  onLogin,
  onSignUp,
  onBack,
}: {
  onLogin:  () => void;
  onSignUp: () => void;
  onBack:   () => void;
}) {
  /* ── Login state ──────────────────────────────────────────────────────── */
  const [email,      setEmail]      = useState('');
  const [arn,        setArn]        = useState('');
  const [showArn,    setShowArn]    = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const [error,      setError]      = useState('');
  const [loading,    setLoading]    = useState(false);

  /* ── Forgot-password state ────────────────────────────────────────────── */
  const [forgotMode,   setForgotMode]   = useState(false);
  const [forgotEmail,  setForgotEmail]  = useState('');
  const [forgotStatus, setForgotStatus] = useState<'idle' | 'loading' | 'sent' | 'notfound'>('idle');
  const [recoveredArn, setRecoveredArn] = useState('');

  /* ── Pre-fill remembered email on mount ──────────────────────────────── */
  useEffect(() => {
    const saved = localStorage.getItem(REMEMBER_KEY);
    if (saved) {
      setEmail(saved);
      setRememberMe(true);
    }
  }, []);

  /* ── Login handler ────────────────────────────────────────────────────── */
  const handleLogin = () => {
    setError('');
    const emailTrimmed = email.trim().toLowerCase();
    const arnTrimmed   = arn.trim();

    if (!emailTrimmed || !arnTrimmed) {
      setError('Please enter both Email Address and ARN Number.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailTrimmed)) {
      setError('Email address format is invalid (e.g. you@example.com).');
      return;
    }

    setLoading(true);
    setTimeout(() => {
      try {
        const users: any[] = JSON.parse(localStorage.getItem('apex_users') || '[]');
        const user = users.find(
          u =>
            u.email?.toLowerCase() === emailTrimmed &&
            u.arn.toLowerCase() === arnTrimmed.toLowerCase(),
        );
        if (user) {
          // Persist or clear remembered email
          if (rememberMe) {
            localStorage.setItem(REMEMBER_KEY, emailTrimmed);
          } else {
            localStorage.removeItem(REMEMBER_KEY);
          }
          sessionStorage.setItem('apex_session', JSON.stringify(user));
          onLogin();
        } else {
          setError('No account found with these credentials. Check your email and ARN, or sign up.');
        }
      } catch {
        setError('Something went wrong. Please try again.');
      }
      setLoading(false);
    }, 600);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleLogin();
  };

  /* ── Forgot-password handler ──────────────────────────────────────────── */
  const handleForgotSubmit = () => {
    const trimmed = forgotEmail.trim().toLowerCase();
    if (!trimmed || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) return;

    setForgotStatus('loading');
    setTimeout(() => {
      try {
        const users: any[] = JSON.parse(localStorage.getItem('apex_users') || '[]');
        const match = users.find(u => u.email?.toLowerCase() === trimmed);
        if (match) {
          setRecoveredArn(match.arn);
          setForgotStatus('sent');
        } else {
          setForgotStatus('notfound');
        }
      } catch {
        setForgotStatus('notfound');
      }
    }, 800);
  };

  const resetForgot = () => {
    setForgotMode(false);
    setForgotEmail('');
    setForgotStatus('idle');
    setRecoveredArn('');
  };

  /* ── Shared left panel (reused in both modes) ─────────────────────────── */
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

        <h2 className="text-3xl font-bold text-white mb-3 leading-snug">
          {forgotMode ? 'Recover Access' : 'Welcome Back'}
        </h2>
        <p className="text-blue-200/60 text-sm leading-relaxed mb-10">
          {forgotMode
            ? 'Enter your registered email and we\'ll retrieve your ARN credentials.'
            : 'Sign in to access your distributor dashboard, manage investors, and track your earnings.'}
        </p>

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

  /* ════════════════════════════════════════════════════════════════════════
     FORGOT PASSWORD VIEW
  ════════════════════════════════════════════════════════════════════════ */
  if (forgotMode) {
    return (
      <div className="min-h-screen flex">
        {LeftPanel}

        <div className="flex-1 bg-white flex flex-col">
          {/* Top bar */}
          <div className="flex items-center justify-between px-8 py-5 border-b border-slate-100">
            <button
              onClick={resetForgot}
              className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
            >
              <ChevronLeft className="w-4 h-4" />
              Back to Sign In
            </button>
            <p className="text-sm text-slate-500">
              New distributor?{' '}
              <button onClick={onSignUp} className="text-blue-600 font-semibold hover:underline">
                Sign Up
              </button>
            </p>
          </div>

          {/* Forgot form */}
          <div className="flex-1 flex items-center justify-center p-8">
            <motion.div
              key="forgot"
              initial={{ opacity: 0, y: 16 }}
              animate={{ opacity: 1, y: 0 }}
              className="w-full max-w-md"
            >
              {/* Icon */}
              <div className="w-14 h-14 rounded-2xl bg-blue-50 border border-blue-100 flex items-center justify-center mb-6">
                <KeyRound className="w-6 h-6 text-blue-500" />
              </div>

              <h1 className="text-2xl font-semibold text-slate-800 mb-1">Forgot your credentials?</h1>
              <p className="text-sm text-slate-500 mb-8">
                Enter your registered email address and we'll retrieve your ARN number.
              </p>

              <AnimatePresence mode="wait">
                {/* ── Success state ─────────────────────────────────────── */}
                {forgotStatus === 'sent' && (
                  <motion.div
                    key="sent"
                    initial={{ opacity: 0, scale: 0.97 }}
                    animate={{ opacity: 1, scale: 1 }}
                    className="bg-green-50 border border-green-200 rounded-2xl p-6 text-center"
                  >
                    <div className="w-12 h-12 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-4">
                      <CheckCircle2 className="w-6 h-6 text-green-500" />
                    </div>
                    <p className="text-sm font-semibold text-slate-700 mb-1">Account found!</p>
                    <p className="text-xs text-slate-500 mb-4 leading-relaxed">
                      Your registered ARN number is:
                    </p>
                    <div className="inline-flex items-center gap-2 bg-white border border-green-200 rounded-xl px-4 py-2.5 mb-5">
                      <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">ARN</span>
                      <span className="text-sm font-mono font-bold text-slate-800">{recoveredArn}</span>
                    </div>
                    <p className="text-xs text-slate-400 mb-5">
                      Use this ARN along with your email to sign in.
                    </p>
                    <button
                      onClick={resetForgot}
                      className="w-full py-3 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors"
                    >
                      Back to Sign In
                    </button>
                  </motion.div>
                )}

                {/* ── Not found state ───────────────────────────────────── */}
                {forgotStatus === 'notfound' && (
                  <motion.div
                    key="notfound"
                    initial={{ opacity: 0, scale: 0.97 }}
                    animate={{ opacity: 1, scale: 1 }}
                    className="bg-amber-50 border border-amber-200 rounded-2xl p-6 text-center"
                  >
                    <div className="w-12 h-12 rounded-full bg-amber-100 flex items-center justify-center mx-auto mb-4">
                      <Mail className="w-6 h-6 text-amber-500" />
                    </div>
                    <p className="text-sm font-semibold text-slate-700 mb-2">No account found</p>
                    <p className="text-xs text-slate-500 leading-relaxed mb-5">
                      We couldn't find an account associated with <span className="font-semibold text-slate-700">{forgotEmail}</span>.
                      Please check the email or sign up.
                    </p>
                    <div className="flex gap-3">
                      <button
                        onClick={() => setForgotStatus('idle')}
                        className="flex-1 py-2.5 border border-slate-200 text-slate-600 font-medium text-sm rounded-xl hover:bg-slate-50 transition-colors"
                      >
                        Try Again
                      </button>
                      <button
                        onClick={onSignUp}
                        className="flex-1 py-2.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors"
                      >
                        Sign Up
                      </button>
                    </div>
                  </motion.div>
                )}

                {/* ── Input state ───────────────────────────────────────── */}
                {(forgotStatus === 'idle' || forgotStatus === 'loading') && (
                  <motion.div key="input" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
                    <div className="mb-6">
                      <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                        Registered Email Address <span className="text-red-400">*</span>
                      </label>
                      <input
                        type="email"
                        value={forgotEmail}
                        onChange={e => setForgotEmail(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && handleForgotSubmit()}
                        placeholder="you@example.com"
                        className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all"
                        autoFocus
                      />
                    </div>

                    <button
                      onClick={handleForgotSubmit}
                      disabled={forgotStatus === 'loading' || !forgotEmail.trim()}
                      className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg"
                    >
                      {forgotStatus === 'loading' ? (
                        <>
                          <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
                          </svg>
                          Looking up your account…
                        </>
                      ) : (
                        'Retrieve My ARN'
                      )}
                    </button>

                    <p className="text-center text-xs text-slate-400 mt-5 leading-relaxed">
                      Remember your credentials?{' '}
                      <button onClick={resetForgot} className="text-blue-500 hover:underline font-medium">
                        Sign in instead
                      </button>
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

  /* ════════════════════════════════════════════════════════════════════════
     MAIN LOGIN VIEW
  ════════════════════════════════════════════════════════════════════════ */
  return (
    <div className="min-h-screen flex">
      {LeftPanel}

      {/* ── Right form panel ──────────────────────────────────────────────── */}
      <div className="flex-1 bg-white flex flex-col">
        {/* Top bar */}
        <div className="flex items-center justify-between px-8 py-5 border-b border-slate-100">
          <button
            onClick={onBack}
            className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
          >
            <ArrowLeft className="w-4 h-4" />
            Back to Home
          </button>
          <p className="text-sm text-slate-500">
            New distributor?{' '}
            <button onClick={onSignUp} className="text-blue-600 font-semibold hover:underline">
              Sign Up
            </button>
          </p>
        </div>

        {/* Form */}
        <div className="flex-1 flex items-center justify-center p-8">
          <motion.div
            key="login"
            initial={{ opacity: 0, y: 16 }}
            animate={{ opacity: 1, y: 0 }}
            className="w-full max-w-md"
          >
            <div className="mb-8">
              <h1 className="text-2xl font-semibold text-slate-800 mb-1">Sign in to your account</h1>
              <p className="text-sm text-slate-500">
                Use your registered email address and ARN number to sign in.
              </p>
            </div>

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
              {error && (
                <motion.div
                  initial={{ opacity: 0, y: -8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -8 }}
                  className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3"
                >
                  <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                  <p className="text-sm text-red-700">{error}</p>
                </motion.div>
              )}
            </AnimatePresence>

            {/* Email field */}
            <div className="mb-5">
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                Email Address <span className="text-red-400">*</span>
              </label>
              <input
                type="email"
                value={email}
                onChange={e => { setEmail(e.target.value); setError(''); }}
                onKeyDown={handleKeyDown}
                placeholder="you@example.com"
                className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all"
              />
            </div>

            {/* ARN field */}
            <div className="mb-5">
              <div className="flex items-center justify-between mb-1.5">
                <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider">
                  ARN Number <span className="text-red-400">*</span>
                </label>
                <button
                  type="button"
                  onClick={() => { setForgotMode(true); setForgotEmail(email); }}
                  className="text-[11px] text-blue-500 hover:text-blue-700 font-medium hover:underline transition-colors"
                >
                  Forgot ARN?
                </button>
              </div>
              <div className="relative">
                <input
                  type={showArn ? 'text' : 'password'}
                  value={arn}
                  onChange={e => { setArn(e.target.value); setError(''); }}
                  onKeyDown={handleKeyDown}
                  placeholder="e.g. ARN-102943"
                  className="w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 pr-11 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all"
                />
                <button
                  type="button"
                  onClick={() => setShowArn(p => !p)}
                  className="absolute right-3 top-3 text-slate-400 hover:text-slate-600 transition-colors"
                >
                  {showArn ? <EyeOff className="w-5 h-5" /> : <Eye className="w-5 h-5" />}
                </button>
              </div>
              <p className="text-[11px] text-slate-400 mt-1">Format: ARN- followed by digits (e.g. ARN-102943)</p>
            </div>

            {/* Remember me */}
            <div className="mb-7">
              <button
                type="button"
                onClick={() => setRememberMe(p => !p)}
                className="flex items-center gap-2.5 group select-none"
              >
                {rememberMe
                  ? <CheckSquare className="w-4 h-4 text-blue-600 transition-colors" />
                  : <Square     className="w-4 h-4 text-slate-300 group-hover:text-slate-400 transition-colors" />
                }
                <span className="text-sm text-slate-600 group-hover:text-slate-800 transition-colors">
                  Remember me on this device
                </span>
              </button>
            </div>

            {/* Submit */}
            <button
              onClick={handleLogin}
              disabled={loading}
              className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors disabled:opacity-60 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg"
            >
              {loading ? (
                <>
                  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
                  </svg>
                  Signing in…
                </>
              ) : (
                'Sign In'
              )}
            </button>

            <p className="text-center text-xs text-slate-400 mt-6 leading-relaxed">
              By signing in you agree to our{' '}
              <span className="text-blue-500 cursor-pointer hover:underline">Terms of Service</span> and{' '}
              <span className="text-blue-500 cursor-pointer hover:underline">Privacy Policy</span>.
            </p>
          </motion.div>
        </div>
      </div>
    </div>
  );
}
