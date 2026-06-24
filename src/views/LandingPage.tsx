import React from 'react';
import { motion } from 'motion/react';

export default function LandingPage({
  onLogin,
  onSignUp,
  onInvestorLogin,
}: {
  onLogin:  () => void;
  onSignUp: () => void;
  onInvestorLogin: () => void;
}) {
  return (
    <div className="min-h-screen bg-[#0B1B3E] flex flex-col relative overflow-hidden">

      {/* ── Background glows ──────────────────────────────────────────────── */}
      <div className="absolute inset-0 pointer-events-none select-none">
        <div className="absolute top-16 left-1/3 w-[480px] h-[480px] bg-blue-500/10  rounded-full blur-3xl" />
        <div className="absolute bottom-16 right-1/4 w-[360px] h-[360px] bg-violet-600/8 rounded-full blur-3xl" />
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[700px] h-[700px] bg-blue-900/15 rounded-full blur-3xl" />
      </div>

      {/* ── Top navbar ────────────────────────────────────────────────────── */}
      <nav className="relative z-10 flex items-center justify-between px-8 py-6">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-white/10 border border-white/20 flex items-center justify-center text-white font-bold text-lg shadow-lg">
            P
          </div>
          <span className="text-white font-bold text-xl tracking-tight">Platizio</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={onInvestorLogin}
            className="px-5 py-2 text-sm font-medium text-blue-200 hover:text-white transition-colors rounded-xl hover:bg-white/5"
          >
            Investor Login
          </button>
          <button
            onClick={onLogin}
            className="px-5 py-2 text-sm font-medium text-white/70 hover:text-white transition-colors rounded-xl hover:bg-white/5"
          >
            Log In
          </button>
          <button
            onClick={onSignUp}
            className="px-5 py-2 text-sm font-semibold bg-white text-[#0B1B3E] rounded-xl hover:bg-blue-50 transition-colors shadow-md"
          >
            Sign Up
          </button>
        </div>
      </nav>

      {/* ── Hero ──────────────────────────────────────────────────────────── */}
      <div className="flex-1 flex flex-col items-center justify-center relative z-10 px-6 text-center pb-8">
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.55, ease: 'easeOut' }}
          className="flex flex-col items-center"
        >
          {/* Logo mark */}
          <div className="w-24 h-24 rounded-3xl bg-white/10 backdrop-blur-sm border border-white/20 flex items-center justify-center mb-8 shadow-2xl">
            <span className="text-white font-bold text-5xl leading-none">P</span>
          </div>

          {/* Brand name */}
          <h1 className="text-6xl font-bold text-white mb-3 tracking-tight leading-none">
            Platizio
          </h1>
          <p className="text-xl text-blue-200/70 mb-3 font-light">
            India's Premier MFD Distribution Platform
          </p>
          <p className="text-sm text-white/40 mb-10 max-w-xl mx-auto leading-relaxed">
            Empowering AMFI-registered distributors to onboard investors, execute SIPs,
            track earnings, and grow their AUM — all in one place.
          </p>

          {/* Feature pills */}
          <div className="flex gap-2.5 justify-center mb-12 flex-wrap">
            {[
              'ARN & NISM Management',
              'SIP Automation',
              'KYC Workflow',
              'Real-time Analytics',
              'Lead Pipeline',
              'Brokerage Tracking',
            ].map(f => (
              <span
                key={f}
                className="px-4 py-1.5 rounded-full bg-white/5 border border-white/10 text-xs text-white/50 font-medium backdrop-blur-sm"
              >
                {f}
              </span>
            ))}
          </div>

          {/* CTA buttons */}
          <div className="flex gap-4 justify-center">
            <button
              onClick={onLogin}
              className="px-9 py-4 bg-white text-[#0B1B3E] font-semibold text-base rounded-2xl shadow-2xl hover:bg-blue-50 transition-all hover:-translate-y-0.5 duration-200"
            >
              Log In
            </button>
            <button
              onClick={onSignUp}
              className="px-9 py-4 bg-transparent text-white font-semibold text-base rounded-2xl border border-white/25 hover:bg-white/10 transition-all hover:-translate-y-0.5 duration-200 backdrop-blur-sm"
            >
              Sign Up as Distributor
            </button>
          </div>

          {/* Investor entry */}
          <p className="mt-6 text-sm text-white/50">
            Are you an investor?{' '}
            <button
              onClick={onInvestorLogin}
              className="font-semibold text-blue-300 underline-offset-4 hover:text-white hover:underline transition-colors"
            >
              Log in to the investor portal
            </button>
          </p>
        </motion.div>
      </div>

      {/* ── Footer ────────────────────────────────────────────────────────── */}
      <div className="relative z-10 text-center py-5 border-t border-white/5">
        <p className="text-white/20 text-xs tracking-wider">
          AMFI Registered Platform · SEBI Compliant · For Mutual Fund Distributors Only
        </p>
      </div>
    </div>
  );
}
