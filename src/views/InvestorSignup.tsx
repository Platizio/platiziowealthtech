import React, { useState, useEffect, useRef } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  AlertCircle, ShieldCheck, CheckCircle2, RefreshCw,
  MessageSquare, ArrowLeft, ArrowRight, Check, UserPlus,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch } from '../config/api';
import { useAppDispatch } from '../store/hooks';
import { setInvestorUser } from '../store/slices/investorAuthSlice';
import { normalizeInvestorUser } from '../types/investorAuth';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PAN_RE = /^[A-Z]{5}[0-9]{4}[A-Z]$/;
const MOBILE_RE = /^\d{10,13}$/;
const TNC_VERSION = 'v1.0';

const Spinner = () => (
  <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4l3-3-3-3v4a8 8 0 00-8 8h4z" />
  </svg>
);

const inp =
  'w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';

const STEP_LABELS = ['Email', 'Your details', 'Verify & consent', 'Done'];

/**
 * Multi-step investor self-signup. No pre-selected checkboxes anywhere (SRS
 * no-defaults rule): both the ownership-declaration and the Terms & Conditions
 * checkboxes start UNTICKED. The 6-box OTP handlers mirror LoginPage.
 *
 *   Step 1: enter email → request a SIGNUP-purpose OTP.
 *   Step 2: fullName + PAN + mobileNumber.
 *   Step 3: enter the email code + two unticked confirmation checkboxes.
 *   Step 4 (submit): POST /investor-auth/signup → sets investor cookie → onboarding.
 */
export default function InvestorSignup() {
  const navigate = useNavigate();
  const dispatch = useAppDispatch();
  // Invite-only signup: an investor account is created ONLY after the distributor's
  // approval link (investor.md D2/R6). `invited` is set when arriving from /investor/link-approve.
  const [params] = useSearchParams();
  const invited = params.get('invited') === '1';

  const [step, setStep] = useState(1);

  // Step 1
  const [email, setEmail] = useState(params.get('email') || '');
  const [devCode, setDevCode] = useState('');

  // Step 2
  const [fullName, setFullName] = useState('');
  const [pan, setPan] = useState((params.get('pan') || '').toUpperCase());
  const [mobileNumber, setMobileNumber] = useState('');
  const [panVerified, setPanVerified] = useState(false);
  const [phoneVerified, setPhoneVerified] = useState(false);

  // Step 3
  const [otpDigits, setOtpDigits] = useState<string[]>(Array(6).fill(''));
  const [ownershipDeclarationAccepted, setOwnershipDeclarationAccepted] = useState(false);
  const [tncAccepted, setTncAccepted] = useState(false);
  const [countdown, setCountdown] = useState(0);

  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const otpRefs = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    if (step === 3) {
      setTimeout(() => otpRefs.current[0]?.focus(), 120);
    }
  }, [step]);

  useEffect(() => {
    if (countdown <= 0) return;
    const t = setTimeout(() => setCountdown(c => c - 1), 1000);
    return () => clearTimeout(t);
  }, [countdown]);

  const requestOtp = async (rawEmail: string) => {
    const res = await apiFetch('/investor-auth/otp/request', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: rawEmail, purpose: 'SIGNUP' }),
      skipAuthRedirect: true,
    });
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      throw new Error(data?.message || 'Could not send the verification code. Please try again.');
    }
    return data;
  };

  /* ── Step 1 → request OTP, move to details ── */
  const handleEmailNext = async () => {
    const id = email.trim().toLowerCase();
    if (!id) { setError('Please enter your email address.'); return; }
    if (!EMAIL_RE.test(id)) { setError('Please enter a valid email address.'); return; }
    setLoading(true);
    setError('');
    try {
      const data = await requestOtp(id);
      setEmail(id);
      setDevCode(typeof data?.devCode === 'string' ? data.devCode : '');
      setCountdown(typeof data?.resendInSeconds === 'number' ? data.resendInSeconds : 30);
      setStep(2);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not send the verification code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  /* ── Dummy verifications (no live SMS/PAN check — instant) ── */
  const verifyPanDummy = () => {
    if (!PAN_RE.test(pan.trim().toUpperCase())) { setError('Please enter a valid PAN (e.g. ABCDE1234F).'); return; }
    setError(''); setPanVerified(true);
  };
  const verifyPhoneDummy = () => {
    if (!MOBILE_RE.test(mobileNumber.trim())) { setError('Please enter a valid mobile number.'); return; }
    setError(''); setPhoneVerified(true);
  };

  /* ── Step 2 → require dummy PAN + phone verification, move to email verify ── */
  const handleDetailsNext = () => {
    const name = fullName.trim();
    if (!name) { setError('Please enter the investor full name.'); return; }
    if (!panVerified) { setError('Please verify your PAN to continue.'); return; }
    if (!phoneVerified) { setError('Please verify your phone number to continue.'); return; }
    setError('');
    setStep(3);
  };

  /* ── 6-box OTP handlers (mirror LoginPage) ── */
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
  };

  const handleDigitPaste = (e: React.ClipboardEvent) => {
    const paste = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (paste.length === 6) {
      setOtpDigits(paste.split(''));
      setTimeout(() => otpRefs.current[5]?.focus(), 0);
    }
    e.preventDefault();
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
      setError(e instanceof Error ? e.message : 'Could not resend the code. Please try again.');
    }
  };

  /* ── Step 4 → submit signup ── */
  const handleSignup = async () => {
    const code = otpDigits.join('');
    if (code.length < 6) { setError('Please enter the complete 6-digit code.'); return; }
    if (!ownershipDeclarationAccepted) { setError('Please confirm this email belongs to the investor.'); return; }
    if (!tncAccepted) { setError('Please accept the Terms & Conditions to continue.'); return; }
    setLoading(true);
    setError('');
    try {
      const res = await apiFetch('/investor-auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fullName: fullName.trim(),
          pan: pan.trim().toUpperCase(),
          email: email.trim().toLowerCase(),
          mobileNumber: mobileNumber.trim(),
          emailOtp: code,
          tncVersion: TNC_VERSION,
          ownershipDeclarationAccepted,
          tncAccepted,
        }),
        skipAuthRedirect: true,
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error(data?.message || 'Could not complete signup. Please check your details and try again.');
      }
      const user = normalizeInvestorUser(data);
      if (user) dispatch(setInvestorUser(user));
      setStep(4);
      setTimeout(() => navigate('/investor/dashboard', { replace: true }), 900);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not complete signup. Please try again.');
      setOtpDigits(Array(6).fill(''));
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
          <div className="w-10 h-10 rounded-xl bg-white/10 border border-white/20 flex items-center justify-center text-white font-bold text-xl shadow-lg">
            P
          </div>
          <span className="text-white font-bold text-xl tracking-tight">Platizio</span>
        </div>
        <h2 className="text-3xl font-bold text-white mb-3 leading-snug">Create your investor account</h2>
        <p className="text-blue-200/60 text-sm leading-relaxed mb-10">
          A few details, an email verification, and quick phone/PAN checks — that's all it takes. Your PAN becomes your password.
        </p>
        <div className="space-y-4">
          {STEP_LABELS.slice(0, 3).map((label, i) => {
            const idx = i + 1;
            const done = step > idx;
            const current = step === idx;
            return (
              <div key={label} className="flex items-center gap-3">
                <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold border transition-all ${
                  done ? 'bg-green-500 border-green-500 text-white'
                    : current ? 'bg-white/15 border-white/40 text-white'
                      : 'bg-white/5 border-white/15 text-white/40'
                }`}>
                  {done ? <Check className="w-3.5 h-3.5" /> : idx}
                </div>
                <p className={`text-sm ${current ? 'text-white font-medium' : 'text-white/40'}`}>{label}</p>
              </div>
            );
          })}
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

  if (!invited) {
    return (
      <div className="min-h-screen bg-[#0B1B3E] flex items-center justify-center p-6">
        <div className="w-full max-w-md rounded-2xl bg-white p-8 shadow-xl text-center">
          <div className="mb-5 flex items-center justify-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-[#0B1B3E] text-base font-bold text-white">P</div>
            <span className="text-lg font-bold tracking-tight text-slate-800">Platizio</span>
          </div>
          <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-blue-50">
            <ShieldCheck className="h-7 w-7 text-blue-600" />
          </div>
          <h1 className="text-xl font-semibold text-slate-800">Registration is by invitation</h1>
          <p className="mt-2 text-sm text-slate-500 leading-relaxed">
            Investors are onboarded by their distributor. Open the registration link your distributor emailed you,
            review and approve it, and you&rsquo;ll be brought here to create your account.
          </p>
          <button onClick={() => navigate('/investor/login')}
            className="mt-6 w-full rounded-xl bg-[#0B1B3E] py-3 text-sm font-semibold text-white hover:bg-[#1A3066]">
            Go to investor login
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex">
      {LeftPanel}

      <div className="flex-1 bg-white flex flex-col">
        <div className="flex items-center justify-between px-8 py-5 border-b border-slate-100">
          <button
            onClick={() => (step > 1 && step < 4 ? setStep(s => s - 1) : navigate('/investor/login'))}
            className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
          >
            <ArrowLeft className="w-4 h-4" /> {step > 1 && step < 4 ? 'Back' : 'Back to Sign In'}
          </button>
          <p className="text-sm text-slate-500">
            Already registered?{' '}
            <button onClick={() => navigate('/investor/login')} className="text-blue-600 font-semibold hover:underline">
              Sign In
            </button>
          </p>
        </div>

        <div className="flex-1 flex items-center justify-center p-8">
          <div className="w-full max-w-md">

            <motion.div
              className="mb-7"
              initial={{ opacity: 0, y: -8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 0.35, ease: 'easeOut' }}
            >
              <h1 className="text-2xl font-semibold text-slate-800 mb-1">
                {step === 4 ? 'Account created' : 'Create investor account'}
              </h1>
              <p className="text-sm text-slate-500">
                {step === 4 ? 'Redirecting you to your dashboard…' : `Step ${step} of 3 — ${STEP_LABELS[step - 1]}`}
              </p>
            </motion.div>

            <AnimatePresence>
              {error && (
                <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }}
                  className="mb-5 flex items-start gap-2.5 bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                  <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0 mt-0.5" />
                  <p className="text-sm text-red-700">{error}</p>
                </motion.div>
              )}
            </AnimatePresence>

            <AnimatePresence mode="wait">
              {/* ── Step 1: email ── */}
              {step === 1 && (
                <motion.div key="s1" initial={{ opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -12 }}>
                  <div className="mb-6 bg-blue-50 border border-blue-100 rounded-xl px-4 py-3 flex items-start gap-3">
                    <span className="text-blue-400 mt-0.5 text-sm">ℹ</span>
                    <p className="text-xs text-blue-700 leading-relaxed">
                      We'll email a 6-digit verification code to confirm this address belongs to you.
                    </p>
                  </div>
                  <div className="mb-6">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      Email Address <span className="text-red-400">*</span>
                    </label>
                    <input type="email" value={email}
                      onChange={e => { setEmail(e.target.value); setError(''); }}
                      onKeyDown={e => e.key === 'Enter' && handleEmailNext()}
                      placeholder="you@example.com" autoFocus className={inp} />
                  </div>
                  <button onClick={handleEmailNext} disabled={loading || !email.trim()}
                    className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] disabled:opacity-60 disabled:hover:translate-y-0 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                    {loading ? (<><Spinner />Sending code…</>) : (<><MessageSquare className="w-4 h-4" />Send verification code</>)}
                  </button>
                </motion.div>
              )}

              {/* ── Step 2: details ── */}
              {step === 2 && (
                <motion.div key="s2" initial={{ opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -12 }}>
                  <div className="mb-5">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      Full Name <span className="text-red-400">*</span>
                    </label>
                    <input type="text" value={fullName}
                      onChange={e => { setFullName(e.target.value); setError(''); }}
                      placeholder="As per PAN" autoFocus className={inp} />
                  </div>
                  <div className="mb-5">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      PAN <span className="text-red-400">*</span>
                    </label>
                    <div className="flex gap-2">
                      <input type="text" value={pan}
                        onChange={e => { setPan(e.target.value.toUpperCase()); setPanVerified(false); setError(''); }}
                        placeholder="ABCDE1234F" maxLength={10}
                        className={inp + ' font-mono tracking-widest uppercase'} />
                      {panVerified ? (
                        <span className="flex items-center gap-1 px-3 rounded-xl bg-green-50 text-green-700 text-xs font-semibold whitespace-nowrap"><Check className="w-3.5 h-3.5" /> Verified</span>
                      ) : (
                        <button type="button" onClick={verifyPanDummy} className="px-4 rounded-xl bg-slate-100 text-slate-700 text-xs font-semibold hover:bg-slate-200 transition-colors whitespace-nowrap">Verify</button>
                      )}
                    </div>
                    <p className="text-[11px] text-slate-400 mt-1">Your PAN will also be your login password.</p>
                  </div>
                  <div className="mb-6">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5">
                      Mobile Number <span className="text-red-400">*</span>
                    </label>
                    <div className="flex gap-2">
                      <input type="tel" value={mobileNumber}
                        onChange={e => { setMobileNumber(e.target.value.replace(/\D/g, '')); setPhoneVerified(false); setError(''); }}
                        onKeyDown={e => e.key === 'Enter' && handleDetailsNext()}
                        placeholder="9876543210" maxLength={13} className={inp} />
                      {phoneVerified ? (
                        <span className="flex items-center gap-1 px-3 rounded-xl bg-green-50 text-green-700 text-xs font-semibold whitespace-nowrap"><Check className="w-3.5 h-3.5" /> Verified</span>
                      ) : (
                        <button type="button" onClick={verifyPhoneDummy} className="px-4 rounded-xl bg-slate-100 text-slate-700 text-xs font-semibold hover:bg-slate-200 transition-colors whitespace-nowrap">Verify</button>
                      )}
                    </div>
                  </div>
                  <button onClick={handleDetailsNext}
                    className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] flex items-center justify-center gap-2 shadow-lg">
                    Continue <ArrowRight className="w-4 h-4" />
                  </button>
                </motion.div>
              )}

              {/* ── Step 3: verify code + consent ── */}
              {step === 3 && (
                <motion.div key="s3" initial={{ opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -12 }}>
                  <div className="flex items-center gap-3 bg-green-50 border border-green-200 rounded-xl px-4 py-3 mb-6">
                    <div className="w-8 h-8 rounded-full bg-green-100 flex items-center justify-center flex-shrink-0">
                      <CheckCircle2 className="w-4 h-4 text-green-600" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-xs font-semibold text-slate-700">Verification code sent to your email</p>
                      <p className="text-[11px] text-slate-500 truncate">{email}</p>
                    </div>
                  </div>

                  {devCode && (
                    <div className="bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 mb-5 text-left">
                      <p className="text-[11px] font-semibold text-amber-900 mb-1">Dev OTP code</p>
                      <p className="font-mono text-base tracking-widest text-amber-800">{devCode}</p>
                    </div>
                  )}

                  <div className="mb-2">
                    <label className="block text-xs font-bold text-slate-500 uppercase tracking-wider mb-3">
                      Enter 6-Digit Email Code
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

                  <div className="flex items-center justify-end mb-5 mt-2.5">
                    {countdown > 0 ? (
                      <p className="text-xs text-slate-400">
                        Resend code in <span className="font-semibold text-slate-600">{countdown}s</span>
                      </p>
                    ) : (
                      <button onClick={handleResend}
                        className="flex items-center gap-1.5 text-xs text-blue-500 hover:text-blue-700 font-medium transition-colors">
                        <RefreshCw className="w-3 h-3" /> Resend code
                      </button>
                    )}
                  </div>

                  <label className="flex items-start gap-3 mb-3 cursor-pointer select-none">
                    <input
                      type="checkbox"
                      checked={ownershipDeclarationAccepted}
                      onChange={e => { setOwnershipDeclarationAccepted(e.target.checked); setError(''); }}
                      className="mt-0.5 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                    />
                    <span className="text-xs text-slate-600 leading-relaxed">
                      I confirm this email belongs to the investor.
                    </span>
                  </label>

                  <label className="flex items-start gap-3 mb-6 cursor-pointer select-none">
                    <input
                      type="checkbox"
                      checked={tncAccepted}
                      onChange={e => { setTncAccepted(e.target.checked); setError(''); }}
                      className="mt-0.5 h-4 w-4 rounded border-slate-300 text-[#0B1B3E] focus:ring-blue-200"
                    />
                    <span className="text-xs text-slate-600 leading-relaxed">
                      I have read and accept the{' '}
                      <span className="text-blue-600 font-medium">Terms &amp; Conditions</span> ({TNC_VERSION}).
                    </span>
                  </label>

                  <button onClick={handleSignup}
                    disabled={loading || otpDigits.some(d => !d) || !ownershipDeclarationAccepted || !tncAccepted}
                    className="w-full py-3.5 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-all duration-150 hover:-translate-y-0.5 active:translate-y-0 active:scale-[0.99] disabled:opacity-60 disabled:hover:translate-y-0 disabled:cursor-not-allowed flex items-center justify-center gap-2 shadow-lg">
                    {loading ? (<><Spinner />Creating account…</>) : (<><UserPlus className="w-4 h-4" />Create account</>)}
                  </button>
                </motion.div>
              )}

              {/* ── Step 4: success ── */}
              {step === 4 && (
                <motion.div key="s4" initial={{ opacity: 0, scale: 0.97 }} animate={{ opacity: 1, scale: 1 }}
                  className="bg-green-50 border border-green-200 rounded-2xl p-8 text-center">
                  <div className="w-14 h-14 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-4">
                    <CheckCircle2 className="w-7 h-7 text-green-600" />
                  </div>
                  <p className="text-sm font-semibold text-slate-700 mb-1">Account created</p>
                  <p className="text-xs text-slate-500">Taking you to your dashboard…</p>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}
