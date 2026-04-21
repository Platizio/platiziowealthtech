import React from 'react';
import { motion } from 'motion/react';
import { Clock, CheckCircle2, Mail, ChevronRight } from 'lucide-react';

export default function PendingApproval({ onGoToLogin }: { onGoToLogin: () => void }) {
  // Simulated reference number from timestamp
  const refNo = `APX-${Date.now().toString().slice(-8)}`;

  return (
    <div className="min-h-screen bg-[#F1F5F9] flex flex-col items-center justify-center p-6 relative overflow-hidden">

      {/* Background glow */}
      <div className="absolute inset-0 pointer-events-none">
        <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-amber-400/5 rounded-full blur-3xl" />
        <div className="absolute bottom-1/4 right-1/4 w-80 h-80 bg-blue-400/5 rounded-full blur-3xl" />
      </div>

      {/* Top logo */}
      <div className="relative z-10 flex items-center gap-3 mb-10">
        <div className="w-8 h-8 rounded-xl bg-[#0B1B3E] flex items-center justify-center text-white font-bold text-sm shadow">
          A
        </div>
        <span className="font-bold text-lg text-[#0B1B3E] tracking-tight">Apex Wealth</span>
      </div>

      {/* Card */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        className="relative z-10 bg-white rounded-3xl shadow-xl border border-slate-200 p-10 w-full max-w-md text-center"
      >
        {/* Status icon */}
        <div className="w-20 h-20 rounded-full bg-amber-50 border-4 border-amber-100 flex items-center justify-center mx-auto mb-6">
          <Clock className="w-9 h-9 text-amber-500" />
        </div>

        {/* Heading */}
        <h1 className="text-2xl font-semibold text-slate-800 mb-2">Application Submitted!</h1>
        <p className="text-sm text-slate-500 mb-1">Your account is currently <span className="font-semibold text-amber-600">Pending Review</span></p>

        {/* Ref number */}
        <div className="inline-flex items-center gap-2 bg-slate-50 border border-slate-200 rounded-lg px-3 py-1.5 mt-3 mb-7">
          <span className="text-[10px] font-bold text-slate-400 uppercase tracking-wider">Reference No.</span>
          <span className="text-xs font-mono font-bold text-slate-700">{refNo}</span>
        </div>

        {/* Divider */}
        <div className="border-t border-slate-100 mb-6" />

        {/* What happens next */}
        <p className="text-xs font-bold text-slate-400 uppercase tracking-wider mb-4 text-left">What happens next</p>
        <div className="space-y-4 text-left mb-8">
          {[
            {
              step: '01',
              title: 'Document Verification',
              desc: 'Our team will verify your ARN, NISM certificate, PAN, and KYC details.',
              time: '1 – 2 business days',
              icon: <CheckCircle2 className="w-4 h-4 text-blue-400" />,
            },
            {
              step: '02',
              title: 'Internal Review & Approval',
              desc: 'Your application will be reviewed by our onboarding team for compliance.',
              time: '2 – 3 business days',
              icon: <CheckCircle2 className="w-4 h-4 text-blue-400" />,
            },
            {
              step: '03',
              title: 'Account Activation',
              desc: 'Upon approval your account is marked Active and login access is enabled.',
              time: 'Post approval',
              icon: <CheckCircle2 className="w-4 h-4 text-blue-400" />,
            },
          ].map(item => (
            <div key={item.step} className="flex gap-4">
              <div className="w-7 h-7 rounded-full bg-slate-100 flex items-center justify-center flex-shrink-0 text-[10px] font-bold text-slate-500">
                {item.step}
              </div>
              <div>
                <p className="text-sm font-semibold text-slate-700">{item.title}</p>
                <p className="text-xs text-slate-400 mt-0.5 leading-relaxed">{item.desc}</p>
                <p className="text-[10px] font-semibold text-blue-500 mt-1">{item.time}</p>
              </div>
            </div>
          ))}
        </div>

        {/* Email notice */}
        <div className="bg-blue-50 border border-blue-100 rounded-xl px-4 py-3 flex items-start gap-3 text-left mb-8">
          <Mail className="w-4 h-4 text-blue-500 flex-shrink-0 mt-0.5" />
          <p className="text-xs text-blue-700 leading-relaxed">
            You will receive an activation email with login instructions once your account is approved.
          </p>
        </div>

        {/* ── Transparent ghost button (demo shortcut to login) ── */}
        {/* Barely visible at rest, fully visible on hover — intentional for demo */}
        <button
          onClick={onGoToLogin}
          className="
            group w-full flex items-center justify-center gap-2
            px-6 py-2.5 rounded-xl border text-sm font-medium
            border-slate-200/25 text-slate-400/30 bg-transparent
            hover:border-slate-300 hover:text-slate-600 hover:bg-slate-50
            transition-all duration-300
          "
        >
          Already have login credentials? Sign in
          <ChevronRight className="w-3.5 h-3.5 opacity-0 group-hover:opacity-100 transition-opacity duration-300" />
        </button>
      </motion.div>

      <p className="relative z-10 text-slate-400 text-xs mt-6">
        Need help?{' '}
        <span className="text-blue-500 cursor-pointer hover:underline">Contact support@apexwealth.in</span>
      </p>
    </div>
  );
}
