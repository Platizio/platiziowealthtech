import React, { useState } from 'react';
import { motion } from 'motion/react';
import { Eye, EyeOff, ArrowLeft, AlertCircle, ShieldCheck } from 'lucide-react';

export default function LoginPage({
  onLogin,
  onSignUp,
  onBack,
}: {
  onLogin:  () => void;
  onSignUp: () => void;
  onBack:   () => void;
}) {
  const [email,     setEmail]     = useState('');
  const [arn,       setArn]       = useState('');
  const [showArn,   setShowArn]   = useState(false);
  const [error,     setError]     = useState('');
  const [loading,   setLoading]   = useState(false);

  const handleLogin = () => {
    setError('');
    const emailTrimmed = email.trim().toLowerCase();
    const arnTrimmed = arn.trim();

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
          sessionStorage.setItem('apex_session', JSON.stringify(user));
          onLogin();
        } else {
          setError('No account found with these credentials. Check your email and ARN, or sign up.');
        }
      } catch {
        setError('Something went wrong. Please try again.');
      }
      setLoading(false);
    }, 600); // brief loading feel
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleLogin();
  };

  return (
    <div className="min-h-screen flex">

      {/* ── Left branding panel ───────────────────────────────────────────── */}
      <div className="hidden lg:flex w-[420px] bg-[#0B1B3E] flex-col justify-between p-12 flex-shrink-0 relative overflow-hidden">
        {/* Background glow */}
        <div className="absolute top-0 right-0 w-64 h-64 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute bottom-0 left-0 w-48 h-48 bg-violet-500/10 rounded-full blur-3xl pointer-events-none" />

        {/* Logo */}
        <div className="relative z-10">
          <div className="flex items-center gap-3 mb-16">
            <div className="w-10 h-10 rounded-xl bg-white/10 border border-white/20 flex items-center justify-center text-white font-bold text-xl shadow-lg">
              A
            </div>
            <span className="text-white font-bold text-xl tracking-tight">Apex Wealth</span>
          </div>

          <h2 className="text-3xl font-bold text-white mb-3 leading-snug">
            Welcome Back
          </h2>
          <p className="text-blue-200/60 text-sm leading-relaxed mb-10">
            Sign in to access your distributor dashboard, manage investors, and track your earnings.
          </p>

          {/* Benefits */}
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

        {/* Bottom */}
        <div className="relative z-10">
          <div className="flex items-center gap-2 bg-white/5 border border-white/10 rounded-xl px-4 py-3">
            <ShieldCheck className="w-4 h-4 text-green-400 flex-shrink-0" />
            <p className="text-xs text-white/40">
              SEBI compliant · Data encrypted · ISO 27001
            </p>
          </div>
        </div>
      </div>

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
                Email: <span className="font-mono font-bold">aditya@apexwealth.in</span> &nbsp;|&nbsp;
                ARN: <span className="font-mono font-bold">ARN-102943</span>
              </p>
            </div>

            {/* Error */}
            {error && (
              <motion.div
                initial={{ opacity: 0, y: -8 }}
                animate={{ opacity: 1, y: 0 }}
                className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3"
              >
                <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                <p className="text-sm text-red-700">{error}</p>
              </motion.div>
            )}

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
            <div className="mb-7">
              <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                ARN Number <span className="text-red-400">*</span>
              </label>
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
