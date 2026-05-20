import React from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'motion/react';
import { ShieldOff, ArrowLeft } from 'lucide-react';

/**
 * F-30: shown when an authenticated user reaches a route their role cannot
 * access (currently: a non-admin hitting any /admin/* path). Replaces the
 * previous silent bounce-to-dashboard so the user gets explicit feedback.
 *
 * Intentionally NOT wrapped in AppLayout — the layout includes role-aware
 * navigation (admin sidebar, mode switcher) that would be inconsistent with
 * the "this isn't allowed" message we want to surface here.
 */
export default function Unauthorized() {
  const navigate = useNavigate();

  // Prefer going back to wherever the user came from; if there's no usable
  // history (direct URL paste, fresh tab), fall back to the landing page.
  const goBack = () => {
    if (window.history.length > 1) navigate(-1);
    else navigate('/', { replace: true });
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-[#F1F5F9] p-6">
      <motion.div
        initial={{ opacity: 0, y: 8 }}
        animate={{ opacity: 1, y: 0 }}
        className="bg-white w-full max-w-md rounded-3xl shadow-sm border border-slate-200 p-8 text-center"
      >
        <div className="w-14 h-14 rounded-full bg-red-50 border border-red-100 flex items-center justify-center mx-auto mb-5">
          <ShieldOff className="w-6 h-6 text-red-500" />
        </div>

        <h1 className="text-xl font-semibold text-slate-800 mb-2">Access denied</h1>
        <p className="text-sm text-slate-500 mb-6 leading-relaxed">
          Your account doesn’t have permission to view this page. If you believe
          this is a mistake, contact your administrator.
        </p>

        <button
          onClick={goBack}
          className="inline-flex items-center justify-center gap-2 w-full py-2.5 bg-[#0B1B3E] text-white text-sm font-semibold rounded-xl hover:bg-[#1A3066] transition-colors"
        >
          <ArrowLeft className="w-4 h-4" /> Go back
        </button>
      </motion.div>
    </div>
  );
}
