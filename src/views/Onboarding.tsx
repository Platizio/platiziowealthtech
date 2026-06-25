import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import {
  Check, ChevronRight, ChevronLeft, ChevronDown, ChevronUp,
  Eye, EyeOff, Upload, X, Info, AlertCircle, MapPin, Briefcase,
  ShieldCheck, MailCheck, Lock,
} from 'lucide-react';
import { apiFetch, sendToInvestor } from '../config/api';
import { buildValidationSummary, mapServerErrorsToState, readServerValidation } from '../utils/serverValidation';

// ─── Password strength helpers ────────────────────────────────────────────────

const PWD_CHECKS = [
  { key: 'length',  label: 'At least 8 characters',         test: (p: string) => p.length >= 8 },
  { key: 'upper',   label: 'At least 1 capital letter',     test: (p: string) => /[A-Z]/.test(p) },
  { key: 'number',  label: 'At least 1 number',             test: (p: string) => /[0-9]/.test(p) },
  { key: 'special', label: 'At least 1 special character',  test: (p: string) => /[^A-Za-z0-9]/.test(p) },
];

function getPwdScore(pwd: string): number {
  return PWD_CHECKS.filter(c => c.test(pwd)).length;
}

const STRENGTH_META = [
  { label: '',            bar: '',               text: ''                },
  { label: 'Weak',        bar: 'bg-red-500',     text: 'text-red-600'   },
  { label: 'Moderate',    bar: 'bg-amber-500',   text: 'text-amber-600' },
  { label: 'Strong',      bar: 'bg-teal-500',    text: 'text-teal-600'  },
  { label: 'Very Strong', bar: 'bg-green-500',   text: 'text-green-600' },
];

function PasswordStrengthBar({ password }: { password: string }) {
  if (!password) return null;
  const score = getPwdScore(password);
  const meta  = STRENGTH_META[score];
  return (
    <div className="mt-2.5 space-y-2">
      {/* bar segments */}
      <div className="flex gap-1">
        {[1, 2, 3, 4].map(i => (
          <div
            key={i}
            className={`h-1.5 flex-1 rounded-full transition-all duration-300 ${
              i <= score ? meta.bar : 'bg-slate-200'
            }`}
          />
        ))}
      </div>
      {/* label */}
      <p className={`text-xs font-semibold ${meta.text}`}>{meta.label}</p>
      {/* criteria checklist */}
      <div className="grid grid-cols-2 gap-1 mt-1">
        {PWD_CHECKS.map(c => {
          const ok = c.test(password);
          return (
            <p key={c.key} className={`text-[11px] flex items-center gap-1.5 ${ok ? 'text-green-600' : 'text-slate-400'}`}>
              <span className={`w-3.5 h-3.5 rounded-full flex items-center justify-center flex-shrink-0 ${ok ? 'bg-green-100' : 'bg-slate-100'}`}>
                {ok ? <Check className="w-2.5 h-2.5" /> : <span className="w-1 h-1 rounded-full bg-slate-300 block" />}
              </span>
              {c.label}
            </p>
          );
        })}
      </div>
    </div>
  );
}

// ─── Constants ────────────────────────────────────────────────────────────────

const STEPS = [
  { id: 1, title: 'Basic Identity',   subtitle: 'Personal information',   description: 'Tell us about yourself — your name, contact details and PAN.'        },
  { id: 2, title: 'Credentials',      subtitle: 'ARN & NISM details',      description: 'Provide your AMFI registration and certification details.'              },
  { id: 3, title: 'Address Details',  subtitle: 'Residential & Office',    description: 'Enter your current, permanent and (optionally) office address.'        },
  { id: 4, title: 'Bank Details',     subtitle: 'Account information',     description: 'Your bank account for brokerage payouts. IFSC auto-fetches branch.'    },
  { id: 5, title: 'Agreements',       subtitle: 'Compliance & Terms',      description: 'Read and confirm all regulatory agreements to complete onboarding.'     },
];

const NISM_EXTRA = [
  { code: 'NISM-V-B',  label: 'NISM-Series-V-B: Mutual Fund Foundation Examination'  },
  { code: 'NISM-V-C',  label: 'NISM-Series-V-C: Mutual Fund Distributors (Level 2)'  },
  { code: 'NISM-X-A',  label: 'NISM-Series-X-A: Investment Adviser (Level 1)'         },
  { code: 'NISM-X-B',  label: 'NISM-Series-X-B: Investment Adviser (Level 2)'         },
  { code: 'NISM-VI',   label: 'NISM-Series-VI: Depository Operations'                },
  { code: 'NISM-VIII', label: 'NISM-Series-VIII: Equity Derivatives Certification'    },
  { code: 'NISM-XII',  label: 'NISM-Series-XII: Securities Markets Foundation'        },
];

const INDIAN_STATES = [
  'Andhra Pradesh','Arunachal Pradesh','Assam','Bihar','Chhattisgarh','Goa','Gujarat',
  'Haryana','Himachal Pradesh','Jharkhand','Karnataka','Kerala','Madhya Pradesh',
  'Maharashtra','Manipur','Meghalaya','Mizoram','Nagaland','Odisha','Punjab',
  'Rajasthan','Sikkim','Tamil Nadu','Telangana','Tripura','Uttar Pradesh',
  'Uttarakhand','West Bengal','Delhi','Jammu & Kashmir','Ladakh','Puducherry',
  'Chandigarh','Andaman & Nicobar Islands','Dadra & Nagar Haveli and Daman & Diu','Lakshadweep',
];

const BANKS = [
  'HDFC Bank','ICICI Bank','State Bank of India','Axis Bank','Kotak Mahindra Bank',
  'Punjab National Bank','Bank of Baroda','Canara Bank','Union Bank of India',
  'IndusInd Bank','Yes Bank','IDFC First Bank','Federal Bank','RBL Bank',
  'South Indian Bank','Bank of India','Central Bank of India','Indian Bank','UCO Bank',
  'Karnataka Bank','City Union Bank','Dhanlaxmi Bank','Jammu & Kashmir Bank',
];

const PIN_LOOKUP: Record<string, { city: string; state: string }> = {
  '110001': { city: 'New Delhi',      state: 'Delhi'           },
  '110011': { city: 'New Delhi',      state: 'Delhi'           },
  '110020': { city: 'New Delhi',      state: 'Delhi'           },
  '400001': { city: 'Mumbai',         state: 'Maharashtra'     },
  '400051': { city: 'Mumbai',         state: 'Maharashtra'     },
  '400703': { city: 'Navi Mumbai',    state: 'Maharashtra'     },
  '411001': { city: 'Pune',           state: 'Maharashtra'     },
  '411028': { city: 'Pune',           state: 'Maharashtra'     },
  '440001': { city: 'Nagpur',         state: 'Maharashtra'     },
  '395001': { city: 'Surat',          state: 'Gujarat'         },
  '560001': { city: 'Bengaluru',      state: 'Karnataka'       },
  '560025': { city: 'Bengaluru',      state: 'Karnataka'       },
  '600001': { city: 'Chennai',        state: 'Tamil Nadu'      },
  '600018': { city: 'Chennai',        state: 'Tamil Nadu'      },
  '641001': { city: 'Coimbatore',     state: 'Tamil Nadu'      },
  '700001': { city: 'Kolkata',        state: 'West Bengal'     },
  '700091': { city: 'Kolkata',        state: 'West Bengal'     },
  '500001': { city: 'Hyderabad',      state: 'Telangana'       },
  '500034': { city: 'Hyderabad',      state: 'Telangana'       },
  '530001': { city: 'Visakhapatnam',  state: 'Andhra Pradesh'  },
  '380001': { city: 'Ahmedabad',      state: 'Gujarat'         },
  '380006': { city: 'Ahmedabad',      state: 'Gujarat'         },
  '302001': { city: 'Jaipur',         state: 'Rajasthan'       },
  '302004': { city: 'Jaipur',         state: 'Rajasthan'       },
  '226001': { city: 'Lucknow',        state: 'Uttar Pradesh'   },
  '226010': { city: 'Lucknow',        state: 'Uttar Pradesh'   },
  '201301': { city: 'Noida',          state: 'Uttar Pradesh'   },
  '462001': { city: 'Bhopal',         state: 'Madhya Pradesh'  },
  '751001': { city: 'Bhubaneswar',    state: 'Odisha'          },
  '122001': { city: 'Gurugram',       state: 'Haryana'         },
  '682001': { city: 'Kochi',          state: 'Kerala'          },
  '160001': { city: 'Chandigarh',     state: 'Chandigarh'      },
  '248001': { city: 'Dehradun',       state: 'Uttarakhand'     },
  '492001': { city: 'Raipur',         state: 'Chhattisgarh'    },
  '834001': { city: 'Ranchi',         state: 'Jharkhand'       },
  '781001': { city: 'Guwahati',       state: 'Assam'           },
  '800001': { city: 'Patna',          state: 'Bihar'           },
};

const IFSC_LOOKUP: Record<string, { bank: string; branch: string }> = {
  'HDFC0000001': { bank: 'HDFC Bank',           branch: 'Main Branch, Mumbai'         },
  'HDFC0001234': { bank: 'HDFC Bank',           branch: 'Andheri West, Mumbai'        },
  'SBIN0000001': { bank: 'State Bank of India', branch: 'Main Branch, Mumbai'         },
  'SBIN0001234': { bank: 'State Bank of India', branch: 'Connaught Place, New Delhi'  },
  'ICIC0000001': { bank: 'ICICI Bank',          branch: 'Main Branch, Mumbai'         },
  'ICIC0001234': { bank: 'ICICI Bank',          branch: 'MG Road, Bengaluru'          },
  'UTIB0000001': { bank: 'Axis Bank',           branch: 'Main Branch, Mumbai'         },
  'UTIB0001234': { bank: 'Axis Bank',           branch: 'Connaught Place, New Delhi'  },
  'KKBK0000001': { bank: 'Kotak Mahindra Bank', branch: 'Main Branch, Mumbai'         },
  'PUNB0000001': { bank: 'Punjab National Bank',branch: 'Main Branch, New Delhi'      },
  'BARB0000001': { bank: 'Bank of Baroda',      branch: 'Main Branch, Vadodara'       },
  'CNRB0000001': { bank: 'Canara Bank',         branch: 'Main Branch, Bengaluru'      },
  'INDB0000001': { bank: 'IndusInd Bank',       branch: 'Main Branch, Mumbai'         },
  'YESB0000001': { bank: 'Yes Bank',            branch: 'Main Branch, Mumbai'         },
};

const AGREEMENT_TEXT: Record<string, string> = {
  agreeDistributor: `DISTRIBUTOR AGREEMENT — PLACEHOLDER

This Distributor Agreement ("Agreement") is entered into between Platizio Private Limited
("Company") and the undersigned Distributor.

[Full agreement terms will be added once legally reviewed and finalised]

Key provisions (summary):
• The Distributor shall act as an independent contractor and not as an employee.
• Commission structure as per the Revenue Sharing Schedule annexed hereto.
• Compliance with AMFI Code of Conduct is mandatory at all times.
• Confidentiality obligations regarding investor data and platform information.
• Either party may terminate with 30 days' written notice.
• Disputes to be resolved via arbitration in Mumbai jurisdiction.`,

  agreeRevenue: `REVENUE SHARING TERMS — PLACEHOLDER

Revenue Sharing Schedule between Platizio Private Limited and the Distributor.

[Detailed schedule will be added once finalized]

Summary:
• Trail Commission: As per AMFI-specified rates (up to 1.00% p.a.).
• Upfront Commission: As per current SEBI regulations (nil for regular plans).
• Payout Cycle: Monthly, credited by the 15th of the following month.
• Minimum Payout Threshold: ₹500 per cycle.
• Tier-based performance bonuses for Platinum and Gold distributors.
• Subject to TDS deduction as per applicable Income Tax laws.`,

  agreePlatform: `PLATFORM TERMS & CONDITIONS — PLACEHOLDER

Terms and Conditions for use of the Platizio Distributor Platform.

[Full Terms & Conditions will be published once the platform launches commercially]

By using this platform, you agree to:
• Use the platform solely for authorised mutual fund distribution activities.
• Maintain the confidentiality of your login credentials (PAN and ARN).
• Report any unauthorised access or security breach immediately.
• Not reverse-engineer, scrape, or misuse platform data.
• Comply with all applicable data protection laws including DPDP Act 2023.
• Accept periodic updates to these terms with 7 days' notice.`,

  agreeSebi: `SEBI / AMFI COMPLIANCE DECLARATION

I hereby solemnly confirm and declare that:

1. I am AMFI-registered with a valid, current ARN (AMFI Registration Number).
2. I hold the mandatory NISM-Series-V-A: Mutual Fund Distributors certification.
3. I will adhere to SEBI (Mutual Funds) Regulations, 1996 and all subsequent amendments.
4. I comply with AMFI's Code of Conduct for Mutual Fund Distributors.
5. I will provide true, fair, and non-misleading information to all investors.
6. I will disclose all commissions, trail fees, and conflicts of interest as required by SEBI.
7. I will maintain proper books of accounts and records for at least 5 years.
8. I will not indulge in mis-selling, churning, or any unethical practices.
9. I understand violations may result in suspension or cancellation of my ARN.`,
};

// ─── Types ────────────────────────────────────────────────────────────────────

interface AddressData {
  line1: string; line2: string; city: string;
  state: string; pinCode: string; country: string;
}
const emptyAddr = (): AddressData => ({ line1: '', line2: '', city: '', state: '', pinCode: '', country: 'India' });

interface FormData {
  firstName: string; lastName: string; mobile: string;
  email: string; pan: string; dob: string; referredBy: string;
  password: string; confirmPassword: string;
  arn: string; arnExpiryDate: string; nismCertificateNumber: string; nismExpiryDate: string;
  hasExtraNism: boolean; extraNismCerts: string[]; euin: string;
  currentAddress: AddressData;
  permanentSameAsCurrent: boolean; permanentAddress: AddressData;
  includeOffice: boolean; officeAddress: AddressData;
  accountHolderName: string; bankName: string; branch: string;
  accountNumber: string; confirmAccountNumber: string;
  ifscCode: string; accountType: 'Savings' | 'Current';
  agreeDistributor: boolean; agreeRevenue: boolean;
  agreePlatform: boolean; agreeSebi: boolean;
}

const initData: FormData = {
  firstName: '', lastName: '', mobile: '', email: '', pan: '', dob: '', referredBy: '',
  password: '', confirmPassword: '',
  arn: '', arnExpiryDate: '', nismCertificateNumber: '', nismExpiryDate: '',
  hasExtraNism: false, extraNismCerts: [], euin: '',
  currentAddress: emptyAddr(),
  permanentSameAsCurrent: false, permanentAddress: emptyAddr(),
  includeOffice: false, officeAddress: emptyAddr(),
  accountHolderName: '', bankName: '', branch: '',
  accountNumber: '', confirmAccountNumber: '',
  ifscCode: '', accountType: 'Savings',
  agreeDistributor: false, agreeRevenue: false, agreePlatform: false, agreeSebi: false,
};

// ─── Shared style tokens ──────────────────────────────────────────────────────
const CLS_INPUT  = 'w-full bg-slate-50 border border-slate-200 rounded-xl px-4 py-2.5 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all';
const CLS_SELECT = CLS_INPUT + ' cursor-pointer';
const CLS_LABEL  = 'block text-xs font-bold text-slate-500 uppercase tracking-wider mb-1.5';
const CLS_ERR    = 'text-xs text-red-500 mt-1.5 flex items-center gap-1';

// ─── Main component ───────────────────────────────────────────────────────────

export default function Onboarding({ onComplete, onBack }: { onComplete: () => void; onBack: () => void }) {
  const [step,        setStep]        = useState(1);
  const [data,        setData]        = useState<FormData>(initData);
  const [errors,      setErrors]      = useState<Record<string, string>>({});
  const [addrTab,     setAddrTab]     = useState<'current' | 'permanent' | 'office'>('current');
  const [openAgr,     setOpenAgr]     = useState<string | null>(null);
  const [bankQuery,   setBankQuery]   = useState('');
  const [showBankDD,  setShowBankDD]  = useState(false);
  const [pinFilled,   setPinFilled]   = useState<Set<string>>(new Set());
  const [showPwd,      setShowPwd]      = useState(false);
  const [showConfPwd,  setShowConfPwd]  = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError,  setSubmitError]  = useState<string | null>(null);
  // ── Persona-linking gate (R1/R2): Step-1 "Send to Investor" ──────────────────
  const [sentToInvestor, setSentToInvestor] = useState(false);      // wizard locked at Step 1 until investor approves
  const [showPendingModal, setShowPendingModal] = useState(false);  // "Investor approval is pending" popup
  const [pendingEmail, setPendingEmail] = useState('');             // address the approval link was emailed to

  // ── Updaters ───────────────────────────────────────────────────────────────
  const set = <K extends keyof FormData>(k: K, v: FormData[K]) => {
    setData(prev => ({ ...prev, [k]: v }));
    setErrors(prev => { const e = { ...prev }; delete e[k as string]; return e; });
  };

  const getAddr = (t: 'current' | 'permanent' | 'office'): AddressData =>
    t === 'current' ? data.currentAddress
    : t === 'permanent' ? data.permanentAddress
    : data.officeAddress;

  const updateAddr = (
    t: 'current' | 'permanent' | 'office',
    updates: Partial<AddressData>,
  ) => {
    setData(prev => {
      const cur = t === 'current' ? prev.currentAddress : t === 'permanent' ? prev.permanentAddress : prev.officeAddress;
      const next = { ...cur, ...updates };
      if (t === 'current')   return { ...prev, currentAddress:   next };
      if (t === 'permanent') return { ...prev, permanentAddress: next };
      return                        { ...prev, officeAddress:    next };
    });
    // Clear related errors
    Object.keys(updates).forEach(f => {
      setErrors(prev => { const e = { ...prev }; delete e[`${t}_${f}`]; return e; });
    });
  };

  const handlePin = (t: 'current' | 'permanent' | 'office', pin: string) => {
    const loc = pin.length === 6 ? PIN_LOOKUP[pin] : undefined;
    updateAddr(t, { pinCode: pin, ...(loc ? { city: loc.city, state: loc.state } : {}) });
    if (loc) setPinFilled(prev => new Set([...prev, t]));
    else     setPinFilled(prev => { const s = new Set(prev); s.delete(t); return s; });
  };

  const handleIfsc = (raw: string) => {
    const v = raw.toUpperCase().replace(/\s/g, '');
    const found = IFSC_LOOKUP[v];
    if (found) {
      setData(prev => ({ ...prev, ifscCode: v, bankName: found.bank, branch: found.branch }));
      setBankQuery(found.bank);
    } else {
      set('ifscCode', v as FormData['ifscCode']);
    }
    setErrors(prev => { const e = { ...prev }; delete e.ifscCode; return e; });
  };

  const toggleNism = (code: string) =>
    setData(prev => ({
      ...prev,
      extraNismCerts: prev.extraNismCerts.includes(code)
        ? prev.extraNismCerts.filter(c => c !== code)
        : [...prev.extraNismCerts, code],
    }));

  // ── Validation ─────────────────────────────────────────────────────────────
  const validate = (s: number) => {
    const e: Record<string, string> = {};
    if (s === 1) {
      if (!data.firstName.trim()) e.firstName = 'Required';
      if (!data.lastName.trim())  e.lastName  = 'Required';
      if (!/^[6-9]\d{9}$/.test(data.mobile.trim()))
        e.mobile = 'Enter a valid 10-digit mobile number starting with 6–9';
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(data.email.trim()))
        e.email = 'Enter a valid email address';
      if (!/^[A-Z]{5}[0-9]{4}[A-Z]$/.test(data.pan.trim().toUpperCase()))
        e.pan = 'Enter a valid PAN (e.g. ABCDE1234F)';
      if (!data.dob) e.dob = 'Required';
      if (getPwdScore(data.password) < 4)
        e.password = 'Password must meet all 4 requirements below';
      if (data.confirmPassword !== data.password)
        e.confirmPassword = 'Passwords do not match';
    }
    if (s === 2) {
      if (!data.nismCertificateNumber.trim())
        e.nismCertificateNumber = 'Required';
      if (!data.nismExpiryDate)
        e.nismExpiryDate = 'Required';
      else if (data.nismExpiryDate <= new Date().toISOString().split('T')[0])
        e.nismExpiryDate = 'NISM expiry date must be in the future';
    }
    if (s === 3) {
      const a = data.currentAddress;
      if (!a.line1.trim())            e['current_line1']   = 'Address Line 1 is required';
      if (!a.city.trim())             e['current_city']    = 'City is required';
      if (!a.state)                   e['current_state']   = 'State is required';
      if (!/^\d{6}$/.test(a.pinCode)) e['current_pinCode'] = 'Enter a valid 6-digit PIN code';
    }
    if (s === 4) {
      if (!data.accountHolderName.trim()) e.accountHolderName = 'Required';
      if (!data.bankName)                 e.bankName          = 'Please select a bank';
      if (!/^\d{9,18}$/.test(data.accountNumber))
        e.accountNumber = 'Enter a valid account number (9–18 digits)';
      if (data.accountNumber !== data.confirmAccountNumber)
        e.confirmAccountNumber = 'Account numbers do not match';
      if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(data.ifscCode.toUpperCase()))
        e.ifscCode = 'Enter a valid IFSC code (e.g. HDFC0000001)';
    }
    if (s === 5) {
      if (!data.agreeDistributor) e.agreeDistributor = 'Required';
      if (!data.agreeRevenue)     e.agreeRevenue     = 'Required';
      if (!data.agreePlatform)    e.agreePlatform    = 'Required';
      if (!data.agreeSebi)        e.agreeSebi        = 'Required';
    }
    return e;
  };

  const goNext = () => {
    const errs = validate(step);
    if (Object.keys(errs).length > 0) { setErrors(errs); return; }
    setErrors({});
    // R1/R2: Step-1 "Continue" is repurposed as "Send to Investor" — the wizard does
    // not advance past Step 1 until the investor approves via the emailed link.
    if (step === 1) { handleSendToInvestor(); return; }
    if (step < 5) setStep(s => s + 1);
    else handleSubmit();
  };
  const goBack = () => { setErrors({}); setStep(s => s - 1); };

  // ── Send to Investor (R1/R2) ─────────────────────────────────────────────────
  // POSTs the Step-1 basic identity for investor approval. On success the wizard is
  // locked at Step 1 (sentToInvestor) and the "approval pending" popup is shown; the
  // investor must approve via an emailed link before steps 2-5 unlock.
  const handleSendToInvestor = async () => {
    setIsSubmitting(true);
    setSubmitError(null);
    const trimmedEmail = data.email.trim().toLowerCase();
    try {
      const res = await sendToInvestor({
        fullName:     `${data.firstName.trim()} ${data.lastName.trim()}`,
        pan:          data.pan.trim().toUpperCase(),
        email:        trimmedEmail,
        mobileNumber: data.mobile.trim(),
        dateOfBirth:  data.dob,
        payloadJson:  JSON.stringify(data),
      });
      if (res.status === 'PENDING_INVESTOR_APPROVAL') {
        setPendingEmail(trimmedEmail);
        setSentToInvestor(true);
        setShowPendingModal(true);
      }
    } catch (err) {
      const e = err as Error & { fieldErrors?: Record<string, string> };
      if (e.fieldErrors && Object.keys(e.fieldErrors).length > 0) {
        setErrors(mapServerErrorsToState(e.fieldErrors, {
          fullName: 'firstName',
          mobileNumber: 'mobile',
          dateOfBirth: 'dob',
        }));
      }
      setSubmitError(e.message || 'Something went wrong. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleSubmit = async () => {
    setIsSubmitting(true);
    setSubmitError(null);
    try {
      const payload = {
        fullName:              `${data.firstName.trim()} ${data.lastName.trim()}`,
        mobileNumber:          data.mobile.trim(),
        email:                 data.email.trim().toLowerCase(),
        arnNumber:             data.arn.trim(),
        arnExpiryDate:         data.arnExpiryDate,
        nismCertificateNumber: data.nismCertificateNumber.trim(),
        nismExpiryDate:        data.nismExpiryDate,
        password:              data.password,
        eUinNumber:            data.euin.trim()              || undefined,
        bankAccountNumber:     data.accountNumber            || undefined,
        bankIfsc:              data.ifscCode.toUpperCase()   || undefined,
        bankAccountHolderName: data.accountHolderName.trim() || undefined,
      };
      const res = await apiFetch('/auth/signup', {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify(payload),
      });
      if (!res.ok) {
        const validation = await readServerValidation(res);
        if (res.status === 400) {
          const mappedErrors = mapServerErrorsToState(validation.fieldErrors, {
            fullName: 'firstName',
            mobileNumber: 'mobile',
            arnNumber: 'arn',
            bankIfsc: 'ifscCode',
            bankAccountNumber: 'accountNumber',
            nismCertificateNumber: 'nismCertificateNumber',
            nismExpiryDate: 'nismExpiryDate',
            arnExpiryDate: 'arnExpiryDate',
          });
          setErrors(mappedErrors);
          throw new Error(buildValidationSummary(validation));
        }
        throw new Error(validation.payload?.message || `Server error: ${res.status}`);
      }
      await apiFetch('/auth/logout', { method: 'POST' }).catch(() => undefined);
      onComplete();
    } catch (err: any) {
      setSubmitError(err.message || 'Something went wrong. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  // ── Address fields renderer ────────────────────────────────────────────────
  const renderAddrFields = (t: 'current' | 'permanent' | 'office') => {
    const addr = getAddr(t);
    const req  = t === 'current';
    const star = req ? <span className="text-red-400">*</span> : null;
    const err  = (f: string) => errors[`${t}_${f}`];

    return (
      <div className="space-y-4">
        <div>
          <label className={CLS_LABEL}>Address Line 1 {star}</label>
          <input type="text" value={addr.line1} placeholder="House / Flat No., Street Name"
            onChange={e => updateAddr(t, { line1: e.target.value })}
            className={CLS_INPUT + (err('line1') ? ' border-red-300 ring-1 ring-red-200' : '')} />
          {err('line1') && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{err('line1')}</p>}
        </div>

        <div>
          <label className={CLS_LABEL}>Address Line 2 <span className="normal-case font-normal text-slate-400">(Optional)</span></label>
          <input type="text" value={addr.line2} placeholder="Area, Landmark"
            onChange={e => updateAddr(t, { line2: e.target.value })} className={CLS_INPUT} />
        </div>

        <div>
          <label className={CLS_LABEL}>PIN Code {star}</label>
          <div className="relative">
            <input type="text" value={addr.pinCode} placeholder="6-digit PIN" maxLength={6}
              onChange={e => handlePin(t, e.target.value.replace(/\D/g, '').slice(0, 6))}
              className={CLS_INPUT + ' pr-32' + (err('pinCode') ? ' border-red-300 ring-1 ring-red-200' : '')} />
            {pinFilled.has(t) && (
              <span className="absolute right-3 top-2.5 flex items-center gap-1 text-[10px] font-bold text-green-600 bg-green-50 px-2 py-0.5 rounded-full pointer-events-none">
                <Check className="w-3 h-3" /> Auto-filled
              </span>
            )}
          </div>
          {err('pinCode')
            ? <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{err('pinCode')}</p>
            : <p className="text-[11px] text-slate-400 mt-1">City and state will be auto-filled from PIN code</p>}
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className={CLS_LABEL}>City {star}</label>
            <input type="text" value={addr.city} placeholder="City"
              onChange={e => { updateAddr(t, { city: e.target.value }); setPinFilled(p => { const s = new Set(p); s.delete(t); return s; }); }}
              className={CLS_INPUT + (err('city') ? ' border-red-300 ring-1 ring-red-200' : '')} />
            {err('city') && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{err('city')}</p>}
          </div>
          <div>
            <label className={CLS_LABEL}>State {star}</label>
            <select value={addr.state} onChange={e => updateAddr(t, { state: e.target.value })}
              className={CLS_SELECT + (err('state') ? ' border-red-300 ring-1 ring-red-200' : '')}>
              <option value="">Select state</option>
              {INDIAN_STATES.map(s => <option key={s} value={s}>{s}</option>)}
            </select>
            {err('state') && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{err('state')}</p>}
          </div>
        </div>

        <div>
          <label className={CLS_LABEL}>Country</label>
          <input type="text" value={addr.country} onChange={e => updateAddr(t, { country: e.target.value })} className={CLS_INPUT} />
        </div>
      </div>
    );
  };

  // ── Bank combobox ──────────────────────────────────────────────────────────
  const filteredBanks = BANKS
    .filter(b => b.toLowerCase().includes(bankQuery.toLowerCase()))
    .slice(0, 8);

  // ════════════════════════════════════════════════════════════════════════════
  return (
    <div className="min-h-screen flex bg-white">

      {/* ─── Left Sidebar ───────────────────────────────────────────────────── */}
      <aside className="w-80 bg-[#0B1B3E] flex-shrink-0 flex flex-col p-8 relative overflow-hidden">
        <div className="absolute top-0 right-0 w-56 h-56 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute bottom-0 left-0 w-40 h-40 bg-violet-500/8  rounded-full blur-3xl pointer-events-none" />

        {/* Logo */}
        <div className="relative z-10 flex items-center gap-3 mb-10">
          <div className="w-9 h-9 rounded-xl bg-white/10 border border-white/20 flex items-center justify-center text-white font-bold text-lg shadow-lg">P</div>
          <span className="text-white font-bold text-lg tracking-tight">Platizio</span>
        </div>

        {/* Step progress */}
        <div className="relative z-10 flex-1">
          <p className="text-white/35 text-[10px] font-bold uppercase tracking-widest mb-5">Application Progress</p>
          {STEPS.map((s, i) => {
            // R1/R2: while the investor's approval is pending, steps 2-5 stay locked.
            const locked = sentToInvestor && s.id > 1;
            return (
            <div key={s.id}>
              <div className={`flex items-start gap-3.5 ${step === s.id ? 'opacity-100' : step > s.id ? 'opacity-75' : 'opacity-30'}`}>
                <div className={`w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0 text-sm font-bold mt-0.5 transition-all duration-300 ${
                  step > s.id  ? 'bg-green-400 text-white shadow-md' :
                  step === s.id ? 'bg-white text-[#0B1B3E] shadow-lg' :
                  'bg-white/10 text-white/40 border border-white/10'
                }`}>
                  {step > s.id ? <Check className="w-3.5 h-3.5" /> : locked ? <Lock className="w-3 h-3" /> : s.id}
                </div>
                <div>
                  <p className={`text-sm font-semibold leading-tight flex items-center gap-1.5 ${step === s.id ? 'text-white' : 'text-white/65'}`}>
                    {s.title}
                    {locked && <Lock className="w-3 h-3 text-amber-300/80" aria-label="Locked until investor approval" />}
                  </p>
                  <p className="text-[11px] text-white/30 mt-0.5">{s.subtitle}</p>
                </div>
              </div>
              {i < STEPS.length - 1 && (
                <div className={`ml-3.5 w-px h-5 my-1 ${step > s.id ? 'bg-green-400/30' : 'bg-white/10'}`} />
              )}
            </div>
            );
          })}
        </div>

        {/* Footer */}
        <div className="relative z-10 bg-white/5 rounded-xl p-4 border border-white/10">
          <p className="text-xs text-white/40 leading-relaxed">
            Your data is encrypted and processed in compliance with SEBI / AMFI guidelines.
          </p>
        </div>
      </aside>

      {/* ─── Right Content ──────────────────────────────────────────────────── */}
      <div className="flex-1 flex flex-col overflow-y-auto">

        {/* Top bar */}
        <div className="flex items-center justify-between px-8 py-4 border-b border-slate-100 flex-shrink-0">
          <button
            onClick={onBack}
            className="flex items-center gap-2 text-slate-500 hover:text-slate-800 text-sm font-medium transition-colors"
          >
            <ChevronLeft className="w-4 h-4" /> Back to Home
          </button>
          <p className="text-xs text-slate-400">
            Already registered?{' '}
            <button className="text-blue-600 font-semibold hover:underline text-xs">Sign In</button>
          </p>
        </div>

        {/* Top progress bar */}
        <div className="h-1 bg-slate-100 flex-shrink-0">
          <motion.div
            className="h-full bg-[#0B1B3E]"
            animate={{ width: `${(step / 5) * 100}%` }}
            transition={{ duration: 0.4 }}
          />
        </div>

        <div className="flex-1 px-10 py-8 max-w-2xl mx-auto w-full">

          {/* Step heading */}
          <div className="mb-8">
            <p className="text-xs font-bold text-slate-400 uppercase tracking-wider mb-1">Step {step} of 5</p>
            <h1 className="text-2xl font-semibold text-slate-800">{STEPS[step - 1].title}</h1>
            <p className="text-sm text-slate-500 mt-1">{STEPS[step - 1].description}</p>
          </div>

          <AnimatePresence mode="wait">
            <motion.div
              key={step}
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: -20 }}
              transition={{ duration: 0.22 }}
            >

              {/* ━━━━━━━━━━━ STEP 1 — BASIC IDENTITY ━━━━━━━━━━━ */}
              {step === 1 && (
                <div className="space-y-5">
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className={CLS_LABEL}>First Name <span className="text-red-400">*</span></label>
                      <input type="text" value={data.firstName} placeholder="First name"
                        onChange={e => set('firstName', e.target.value)}
                        className={CLS_INPUT + (errors.firstName ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.firstName && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.firstName}</p>}
                    </div>
                    <div>
                      <label className={CLS_LABEL}>Last Name <span className="text-red-400">*</span></label>
                      <input type="text" value={data.lastName} placeholder="Last name"
                        onChange={e => set('lastName', e.target.value)}
                        className={CLS_INPUT + (errors.lastName ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.lastName && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.lastName}</p>}
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className={CLS_LABEL}>Mobile Number <span className="text-red-400">*</span></label>
                      <div className="relative">
                        <span className="absolute left-4 top-2.5 text-slate-400 text-sm font-medium pointer-events-none">+91</span>
                        <input type="tel" value={data.mobile} placeholder="10-digit mobile"
                          onChange={e => set('mobile', e.target.value.replace(/\D/g, '').slice(0, 10))}
                          className={CLS_INPUT + ' pl-12' + (errors.mobile ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      </div>
                      {errors.mobile && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.mobile}</p>}
                    </div>
                    <div>
                      <label className={CLS_LABEL}>Email ID <span className="text-red-400">*</span></label>
                      <input type="email" value={data.email} placeholder="you@example.com"
                        onChange={e => set('email', e.target.value)}
                        className={CLS_INPUT + (errors.email ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.email && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.email}</p>}
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className={CLS_LABEL}>PAN Number <span className="text-red-400">*</span></label>
                      <input type="text" value={data.pan} placeholder="ABCDE1234F" maxLength={10}
                        onChange={e => set('pan', e.target.value.toUpperCase().slice(0, 10))}
                        className={CLS_INPUT + ' uppercase tracking-widest font-mono' + (errors.pan ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.pan
                        ? <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.pan}</p>
                        : <p className="text-[11px] text-slate-400 mt-1">5 letters · 4 digits · 1 letter</p>}
                    </div>
                    <div>
                      <label className={CLS_LABEL}>Date of Birth <span className="text-red-400">*</span></label>
                      <input type="date" value={data.dob}
                        max={new Date().toISOString().split('T')[0]}
                        onChange={e => set('dob', e.target.value)}
                        className={CLS_SELECT + (errors.dob ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.dob && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.dob}</p>}
                    </div>
                  </div>

                  {/* Password */}
                  <div className="pt-1 border-t border-slate-100">
                    <div className="flex items-center gap-2 mb-4">
                      <ShieldCheck className="w-4 h-4 text-blue-500" />
                      <span className="text-xs font-bold text-slate-500 uppercase tracking-wider">Account Security</span>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className={CLS_LABEL}>Password <span className="text-red-400">*</span></label>
                        <div className="relative">
                          <input
                            type={showPwd ? 'text' : 'password'}
                            value={data.password}
                            placeholder="Create a strong password"
                            onChange={e => set('password', e.target.value)}
                            className={CLS_INPUT + ' pr-11' + (errors.password ? ' border-red-300 ring-1 ring-red-200' : '')}
                          />
                          <button type="button" onClick={() => setShowPwd(p => !p)}
                            aria-label={showPwd ? 'Hide password' : 'Show password'}
                            className="absolute right-3 top-2.5 text-slate-400 hover:text-slate-600 transition-colors">
                            {showPwd ? <EyeOff className="w-4 h-4" aria-hidden="true" /> : <Eye className="w-4 h-4" aria-hidden="true" />}
                          </button>
                        </div>
                        {errors.password
                          ? <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.password}</p>
                          : <PasswordStrengthBar password={data.password} />}
                      </div>
                      <div>
                        <label className={CLS_LABEL}>Confirm Password <span className="text-red-400">*</span></label>
                        <div className="relative">
                          <input
                            type={showConfPwd ? 'text' : 'password'}
                            value={data.confirmPassword}
                            placeholder="Re-enter your password"
                            onChange={e => set('confirmPassword', e.target.value)}
                            className={CLS_INPUT + ' pr-11' + (errors.confirmPassword ? ' border-red-300 ring-1 ring-red-200' : '')}
                          />
                          <button type="button" onClick={() => setShowConfPwd(p => !p)}
                            aria-label={showConfPwd ? 'Hide password' : 'Show password'}
                            className="absolute right-3 top-2.5 text-slate-400 hover:text-slate-600 transition-colors">
                            {showConfPwd ? <EyeOff className="w-4 h-4" aria-hidden="true" /> : <Eye className="w-4 h-4" aria-hidden="true" />}
                          </button>
                        </div>
                        {errors.confirmPassword
                          ? <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.confirmPassword}</p>
                          : data.confirmPassword && data.confirmPassword === data.password
                            ? <p className="text-[11px] text-green-600 mt-1 flex items-center gap-1"><Check className="w-3 h-3" /> Passwords match</p>
                            : null}
                      </div>
                    </div>
                  </div>

                  <div>
                    <label className={CLS_LABEL}>Referred By <span className="normal-case font-normal text-slate-400">(Optional)</span></label>
                    <input type="text" value={data.referredBy} placeholder="Name or referral code of person who referred you"
                      onChange={e => set('referredBy', e.target.value)} className={CLS_INPUT} />
                  </div>
                </div>
              )}

              {/* ━━━━━━━━━━━ STEP 2 — CREDENTIALS ━━━━━━━━━━━ */}
              {step === 2 && (
                <div className="space-y-6">
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className={CLS_LABEL}>ARN Number</label>
                      <input type="text" value={data.arn} placeholder="e.g. ARN-102943"
                        onChange={e => set('arn', e.target.value)}
                        className={CLS_INPUT + (errors.arn ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.arn
                        ? <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.arn}</p>
                        : <p className="text-[11px] text-slate-400 mt-1">Format: ARN- followed by digits</p>}
                    </div>
                    <div>
                      <label className={CLS_LABEL}>ARN Expiry Date</label>
                      <input type="date" value={data.arnExpiryDate}
                        onChange={e => set('arnExpiryDate', e.target.value)}
                        className={CLS_INPUT + (errors.arnExpiryDate ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.arnExpiryDate && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.arnExpiryDate}</p>}
                    </div>
                  </div>

                  <div>
                    <label className={CLS_LABEL}>EUIN Number <span className="normal-case font-normal text-slate-400">(Optional)</span></label>
                    <input type="text" value={data.euin} placeholder="e.g. E123456"
                      onChange={e => set('euin', e.target.value)} className={CLS_INPUT} />
                    <p className="text-[11px] text-slate-400 mt-1">Employee Unique Identification Number</p>
                  </div>

                  {/* NISM section */}
                  <div>
                    <label className={CLS_LABEL}>NISM Certifications</label>

                    {/* Mandatory cert badge */}
                    <div className="bg-blue-50 border border-blue-100 rounded-xl p-4 mb-3 flex items-start gap-3">
                      <div className="w-5 h-5 rounded bg-blue-500 flex items-center justify-center flex-shrink-0 mt-0.5">
                        <Check className="w-3 h-3 text-white" />
                      </div>
                      <div>
                        <p className="text-sm font-semibold text-blue-800">NISM-Series-V-A: Mutual Fund Distributors (MFD)</p>
                        <p className="text-xs text-blue-500 mt-0.5">Mandatory — required for ARN registration</p>
                      </div>
                    </div>

                    {/* NISM certificate number and expiry */}
                    <div className="grid grid-cols-2 gap-4 mb-3">
                      <div>
                        <label className={CLS_LABEL}>NISM Certificate Number <span className="text-red-400">*</span></label>
                        <input type="text" value={data.nismCertificateNumber} placeholder="e.g. NISM-2024-123456"
                          onChange={e => set('nismCertificateNumber', e.target.value)}
                          className={CLS_INPUT + (errors.nismCertificateNumber ? ' border-red-300 ring-1 ring-red-200' : '')} />
                        {errors.nismCertificateNumber && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.nismCertificateNumber}</p>}
                      </div>
                      <div>
                        <label className={CLS_LABEL}>NISM Expiry Date <span className="text-red-400">*</span></label>
                        <input type="date" value={data.nismExpiryDate}
                          min={new Date().toISOString().split('T')[0]}
                          onChange={e => set('nismExpiryDate', e.target.value)}
                          className={CLS_INPUT + (errors.nismExpiryDate ? ' border-red-300 ring-1 ring-red-200' : '')} />
                        {errors.nismExpiryDate && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.nismExpiryDate}</p>}
                      </div>
                    </div>

                    {/* Toggle for additional */}
                    <label className="flex items-center gap-3 cursor-pointer p-3.5 border border-slate-200 rounded-xl hover:bg-slate-50 transition-colors mb-3">
                      <input type="checkbox" checked={data.hasExtraNism}
                        onChange={e => set('hasExtraNism', e.target.checked)}
                        className="w-4 h-4 accent-blue-600" />
                      <div>
                        <p className="text-sm font-medium text-slate-700">I hold additional NISM Certifications</p>
                        <p className="text-xs text-slate-400 mt-0.5">Select if you have qualifications beyond MFD</p>
                      </div>
                    </label>

                    <AnimatePresence>
                      {data.hasExtraNism && (
                        <motion.div
                          initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
                          className="overflow-hidden"
                        >
                          <div className="border border-slate-200 rounded-xl p-4 space-y-3 bg-slate-50/50">
                            <p className="text-xs font-bold text-slate-400 uppercase tracking-wider">Select all that apply</p>
                            {NISM_EXTRA.map(cert => (
                              <label key={cert.code} className="flex items-center gap-3 cursor-pointer group">
                                <input type="checkbox" checked={data.extraNismCerts.includes(cert.code)}
                                  onChange={() => toggleNism(cert.code)}
                                  className="w-4 h-4 accent-blue-600 flex-shrink-0" />
                                <span className="text-sm text-slate-700 group-hover:text-slate-900 transition-colors leading-snug">{cert.label}</span>
                              </label>
                            ))}
                          </div>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </div>
                </div>
              )}

              {/* ━━━━━━━━━━━ STEP 3 — ADDRESS DETAILS ━━━━━━━━━━━ */}
              {step === 3 && (
                <div className="space-y-6">
                  {/* Address type tabs */}
                  <div className="flex gap-1.5 bg-slate-100 p-1 rounded-xl">
                    {([
                      { id: 'current',   label: 'Current',   icon: <MapPin    className="w-3.5 h-3.5" />, badge: 'Required' },
                      { id: 'permanent', label: 'Permanent', icon: <MapPin    className="w-3.5 h-3.5" />, badge: 'Optional' },
                      { id: 'office',    label: 'Office',    icon: <Briefcase className="w-3.5 h-3.5" />, badge: 'Optional' },
                    ] as const).map(tab => (
                      <button key={tab.id} onClick={() => setAddrTab(tab.id)}
                        className={`flex-1 flex items-center justify-center gap-1.5 py-2 text-xs font-semibold rounded-lg transition-all ${
                          addrTab === tab.id ? 'bg-white shadow-sm text-slate-900' : 'text-slate-500 hover:text-slate-700'
                        }`}
                      >
                        {tab.icon} {tab.label}
                        <span className={`text-[9px] px-1.5 py-0.5 rounded-full font-bold ${
                          tab.badge === 'Required' ? 'bg-red-50 text-red-400' : 'bg-slate-200 text-slate-400'
                        }`}>{tab.badge}</span>
                      </button>
                    ))}
                  </div>

                  <AnimatePresence mode="wait">
                    <motion.div key={addrTab} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}>
                      {/* Permanent — same-as-current toggle */}
                      {addrTab === 'permanent' && (
                        <label className="flex items-center gap-3 cursor-pointer mb-5 p-3.5 bg-blue-50 rounded-xl border border-blue-100">
                          <input type="checkbox" checked={data.permanentSameAsCurrent}
                            onChange={e => set('permanentSameAsCurrent', e.target.checked)}
                            className="w-4 h-4 accent-blue-600" />
                          <span className="text-sm font-medium text-blue-800">Same as Current Address</span>
                        </label>
                      )}

                      {/* Office — add toggle */}
                      {addrTab === 'office' && !data.includeOffice && (
                        <button onClick={() => set('includeOffice', true)}
                          className="w-full border-2 border-dashed border-slate-200 rounded-xl py-8 text-sm font-medium text-slate-400 hover:border-blue-300 hover:text-blue-500 transition-colors flex flex-col items-center gap-2">
                          <Briefcase className="w-5 h-5" />
                          Add Office Address (Optional)
                        </button>
                      )}

                      {/* Show form */}
                      {(addrTab === 'current') && renderAddrFields('current')}
                      {(addrTab === 'permanent' && !data.permanentSameAsCurrent) && renderAddrFields('permanent')}
                      {(addrTab === 'permanent' &&  data.permanentSameAsCurrent) && (
                        <div className="bg-slate-50 rounded-xl p-5 border border-slate-200 space-y-0.5">
                          <p className="text-xs font-semibold text-slate-400 mb-3">Permanent address — same as current</p>
                          {[data.currentAddress.line1, data.currentAddress.line2,
                            [data.currentAddress.city, data.currentAddress.state, data.currentAddress.pinCode].filter(Boolean).join(', '),
                            data.currentAddress.country,
                          ].filter(Boolean).map((line, i) => <p key={i} className="text-sm text-slate-700">{line}</p>)}
                        </div>
                      )}
                      {(addrTab === 'office' && data.includeOffice) && (
                        <div>
                          <div className="flex items-center justify-between mb-4">
                            <p className="text-xs font-bold text-slate-500 uppercase tracking-wider">Office Address</p>
                            <button onClick={() => set('includeOffice', false)} className="text-xs text-red-400 hover:text-red-600 flex items-center gap-1">
                              <X className="w-3 h-3" /> Remove
                            </button>
                          </div>
                          {renderAddrFields('office')}
                        </div>
                      )}
                    </motion.div>
                  </AnimatePresence>
                </div>
              )}

              {/* ━━━━━━━━━━━ STEP 4 — BANK DETAILS ━━━━━━━━━━━ */}
              {step === 4 && (
                <div className="space-y-5">
                  {/* PAN name match hint */}
                  <div className="flex items-start gap-2.5 bg-amber-50 border border-amber-100 rounded-xl px-4 py-3">
                    <Info className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
                    <p className="text-xs text-amber-800 leading-relaxed">
                      Account holder name should match your PAN card name:{' '}
                      <span className="font-semibold">{data.firstName} {data.lastName}</span>
                    </p>
                  </div>

                  {/* Account holder */}
                  <div>
                    <label className={CLS_LABEL}>Account Holder Name (as per bank) <span className="text-red-400">*</span></label>
                    <input type="text" value={data.accountHolderName}
                      placeholder="Full name as printed on bank account"
                      onChange={e => set('accountHolderName', e.target.value)}
                      className={CLS_INPUT + (errors.accountHolderName ? ' border-red-300 ring-1 ring-red-200' : '')} />
                    {errors.accountHolderName && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.accountHolderName}</p>}
                  </div>

                  {/* IFSC — auto-fetches bank + branch */}
                  <div>
                    <label className={CLS_LABEL}>IFSC Code <span className="text-red-400">*</span></label>
                    <div className="relative">
                      <input type="text" value={data.ifscCode} placeholder="e.g. HDFC0000001" maxLength={11}
                        onChange={e => handleIfsc(e.target.value)}
                        className={CLS_INPUT + ' uppercase tracking-widest font-mono pr-36' + (errors.ifscCode ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {data.branch && (
                        <span className="absolute right-3 top-2.5 text-[10px] font-bold text-green-600 bg-green-50 px-2 py-0.5 rounded-full pointer-events-none">
                          ✓ Branch auto-filled
                        </span>
                      )}
                    </div>
                    {errors.ifscCode
                      ? <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.ifscCode}</p>
                      : <p className="text-[11px] text-slate-400 mt-1">Format: 4 letters + 0 + 6 chars. Entering IFSC auto-fills bank & branch.</p>}
                  </div>

                  {/* Bank name — searchable combobox */}
                  <div>
                    <label className={CLS_LABEL}>Bank Name <span className="text-red-400">*</span></label>
                    <div className="relative">
                      <input type="text"
                        value={bankQuery || data.bankName}
                        placeholder="Search or select bank…"
                        onChange={e => { setBankQuery(e.target.value); setShowBankDD(true); if (e.target.value !== data.bankName) set('bankName', ''); }}
                        onFocus={() => setShowBankDD(true)}
                        onBlur={() => setTimeout(() => setShowBankDD(false), 150)}
                        className={CLS_INPUT + (errors.bankName ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {showBankDD && filteredBanks.length > 0 && (
                        <div className="absolute top-full mt-1 left-0 right-0 bg-white border border-slate-200 rounded-xl shadow-xl z-30 overflow-hidden">
                          {filteredBanks.map(b => (
                            <button key={b} onMouseDown={e => e.preventDefault()}
                              onClick={() => { set('bankName', b); setBankQuery(b); setShowBankDD(false); }}
                              className={`w-full text-left px-4 py-2.5 text-sm hover:bg-slate-50 transition-colors ${data.bankName === b ? 'bg-blue-50 text-blue-700 font-medium' : 'text-slate-700'}`}>
                              {b}
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                    {errors.bankName && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.bankName}</p>}
                  </div>

                  {/* Branch (auto-filled) */}
                  <div>
                    <label className={CLS_LABEL}>Branch <span className="normal-case font-normal text-slate-400">(Auto-filled · Optional)</span></label>
                    <input type="text" value={data.branch} placeholder="Branch name"
                      onChange={e => set('branch', e.target.value)} className={CLS_INPUT} />
                  </div>

                  {/* Account number + confirm */}
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className={CLS_LABEL}>Account Number <span className="text-red-400">*</span></label>
                      <input type="text" value={data.accountNumber} placeholder="9 – 18 digits" maxLength={18}
                        onChange={e => set('accountNumber', e.target.value.replace(/\D/g, '').slice(0, 18))}
                        className={CLS_INPUT + ' font-mono' + (errors.accountNumber ? ' border-red-300 ring-1 ring-red-200' : '')} />
                      {errors.accountNumber && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.accountNumber}</p>}
                    </div>
                    <div>
                      <label className={CLS_LABEL}>Confirm Account Number <span className="text-red-400">*</span></label>
                      <div className="relative">
                        <input type="text" value={data.confirmAccountNumber} placeholder="Re-enter" maxLength={18}
                          onChange={e => set('confirmAccountNumber', e.target.value.replace(/\D/g, '').slice(0, 18))}
                          className={CLS_INPUT + ' font-mono pr-8' + (errors.confirmAccountNumber ? ' border-red-300 ring-1 ring-red-200' : '')} />
                        {data.accountNumber && data.confirmAccountNumber && (
                          <span className={`absolute right-3 top-2.5 font-bold text-sm ${data.accountNumber === data.confirmAccountNumber ? 'text-green-500' : 'text-red-400'}`}>
                            {data.accountNumber === data.confirmAccountNumber ? '✓' : '✗'}
                          </span>
                        )}
                      </div>
                      {errors.confirmAccountNumber && <p className={CLS_ERR}><AlertCircle className="w-3 h-3" />{errors.confirmAccountNumber}</p>}
                    </div>
                  </div>

                  {/* Account type */}
                  <div>
                    <label className={CLS_LABEL}>Account Type</label>
                    <div className="flex gap-3">
                      {(['Savings', 'Current'] as const).map(t => (
                        <label key={t} className={`flex-1 flex items-center gap-3 p-3.5 border rounded-xl cursor-pointer transition-all ${
                          data.accountType === t ? 'border-blue-400 bg-blue-50' : 'border-slate-200 hover:bg-slate-50'
                        }`}>
                          <div className={`w-4 h-4 rounded-full border-2 flex items-center justify-center flex-shrink-0 ${data.accountType === t ? 'border-blue-500' : 'border-slate-300'}`}>
                            {data.accountType === t && <div className="w-2 h-2 rounded-full bg-blue-500" />}
                          </div>
                          <div>
                            <p className="text-sm font-semibold text-slate-700">{t}</p>
                            <p className="text-xs text-slate-400">{t === 'Savings' ? 'Personal account' : 'Business account'}</p>
                          </div>
                          <input type="radio" className="sr-only" checked={data.accountType === t} onChange={() => set('accountType', t)} />
                        </label>
                      ))}
                    </div>
                  </div>

                  {/* Cancelled cheque upload */}
                  <div>
                    <label className={CLS_LABEL}>Cancelled Cheque / Passbook <span className="normal-case font-normal text-slate-400">(Optional)</span></label>
                    <div className="border-2 border-dashed border-slate-200 rounded-xl p-6 text-center hover:border-blue-300 hover:bg-blue-50/30 transition-all cursor-pointer group">
                      <Upload className="w-6 h-6 text-slate-300 group-hover:text-blue-400 mx-auto mb-2 transition-colors" />
                      <p className="text-sm text-slate-400 group-hover:text-blue-600 transition-colors font-medium">Click to upload or drag & drop</p>
                      <p className="text-xs text-slate-300 mt-1">PDF, JPG, PNG — up to 5 MB</p>
                    </div>
                  </div>
                </div>
              )}

              {/* ━━━━━━━━━━━ STEP 5 — AGREEMENTS ━━━━━━━━━━━ */}
              {step === 5 && (
                <div className="space-y-4">
                  <div className="flex items-start gap-2.5 bg-amber-50 border border-amber-100 rounded-xl px-4 py-3">
                    <Info className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
                    <p className="text-xs text-amber-800 leading-relaxed">
                      <span className="font-bold">Note:</span> The agreements below are placeholder drafts. Detailed legal text, OTP-based signing, and e-sign via Digio will be added in a future release.
                    </p>
                  </div>

                  {[
                    { id: 'agreeDistributor', title: 'Distributor Agreement',                desc: 'Governs the distributor relationship, commissions, and obligations.'  },
                    { id: 'agreeRevenue',     title: 'Revenue Sharing Terms',               desc: 'Trail & upfront commission schedule, payout cycles and thresholds.'   },
                    { id: 'agreePlatform',    title: 'Platform Terms & Conditions',         desc: 'Usage rules, data protection, and platform access policies.'           },
                    { id: 'agreeSebi',        title: 'SEBI / AMFI Compliance Confirmation', desc: 'Confirms your ARN, NISM certification, and regulatory obligations.'    },
                  ].map(agr => {
                    const checked = data[agr.id as keyof FormData] as boolean;
                    const isOpen  = openAgr === agr.id;
                    return (
                      <div key={agr.id}
                        className={`border rounded-xl overflow-hidden transition-all ${errors[agr.id] ? 'border-red-300 bg-red-50/20' : 'border-slate-200'}`}>
                        <div className="flex items-start gap-3 p-4">
                          <input type="checkbox" id={agr.id} checked={checked}
                            onChange={e => set(agr.id as keyof FormData, e.target.checked as any)}
                            className="w-4 h-4 accent-blue-600 flex-shrink-0 mt-0.5 cursor-pointer" />
                          <div className="flex-1 min-w-0">
                            <label htmlFor={agr.id} className="block text-sm font-semibold text-slate-800 cursor-pointer">
                              I agree to the <span className="text-[#0B1B3E]">{agr.title}</span>
                              <span className="text-red-400 ml-1">*</span>
                            </label>
                            <p className="text-xs text-slate-400 mt-0.5">{agr.desc}</p>
                          </div>
                          <button onClick={() => setOpenAgr(isOpen ? null : agr.id)}
                            className="flex items-center gap-1 text-[11px] font-semibold text-blue-500 hover:text-blue-700 flex-shrink-0 transition-colors">
                            {isOpen ? 'Hide' : 'Read'}
                            {isOpen ? <ChevronUp className="w-3.5 h-3.5" /> : <ChevronDown className="w-3.5 h-3.5" />}
                          </button>
                        </div>

                        <AnimatePresence>
                          {isOpen && (
                            <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
                              className="overflow-hidden">
                              <div className="px-4 pb-4 pt-1 border-t border-slate-100">
                                <pre className="text-xs text-slate-600 leading-relaxed whitespace-pre-wrap font-sans bg-slate-50 rounded-lg p-4 border border-slate-100 max-h-52 overflow-y-auto">
                                  {AGREEMENT_TEXT[agr.id]}
                                </pre>
                              </div>
                            </motion.div>
                          )}
                        </AnimatePresence>

                        {errors[agr.id] && (
                          <p className="px-4 pb-3 text-xs text-red-500 flex items-center gap-1">
                            <AlertCircle className="w-3 h-3" /> This agreement is required to proceed
                          </p>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}

            </motion.div>
          </AnimatePresence>

          {/* ── Investor-approval pending banner (R2) ── */}
          {sentToInvestor && (
            <div className="mt-10 flex items-start gap-3 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3.5">
              <MailCheck className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
              <p className="text-xs text-amber-800 leading-relaxed">
                <span className="font-bold">Investor approval is pending.</span>{' '}
                We have emailed an approval link to{' '}
                <span className="font-semibold">{pendingEmail}</span>. The remaining steps unlock once the investor approves.
              </p>
            </div>
          )}

          {/* ── Navigation ── */}
          <div className={`flex gap-3 pt-6 border-t border-slate-100 ${sentToInvestor ? 'mt-6' : 'mt-10'}`}>
            {step > 1 && (
              <button onClick={goBack}
                className="px-6 py-3 bg-slate-100 text-slate-700 font-medium text-sm rounded-xl hover:bg-slate-200 transition-colors flex items-center gap-2">
                <ChevronLeft className="w-4 h-4" /> Back
              </button>
            )}
            <button onClick={goNext} disabled={isSubmitting || sentToInvestor}
              className="flex-1 py-3 bg-[#0B1B3E] text-white font-semibold text-sm rounded-xl hover:bg-[#1A3066] transition-colors flex items-center justify-center gap-2 shadow-sm disabled:opacity-60 disabled:cursor-not-allowed">
              {step === 1
                ? (sentToInvestor
                    ? (<><Lock className="w-4 h-4" /> Awaiting investor approval</>)
                    : (isSubmitting ? 'Sending…' : 'Send to Investor'))
                : step === 5
                  ? (isSubmitting ? 'Submitting…' : 'Submit Application')
                  : 'Save & Continue'}
              {step > 1 && step < 5 && <ChevronRight className="w-4 h-4" />}
            </button>
          </div>
          {submitError && (
            <p className={CLS_ERR + ' justify-center mt-2'}>
              <AlertCircle className="w-3 h-3" />{submitError}
            </p>
          )}
          <p className="text-center text-xs text-slate-400 mt-4">
            Fields marked <span className="text-red-400 font-bold">*</span> are mandatory
          </p>
        </div>
      </div>

      {/* ─── Investor approval pending modal (R2) ─────────────────────────────── */}
      <AnimatePresence>
        {showPendingModal && (
          <motion.div
            className="fixed inset-0 z-[70] flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm"
            role="dialog"
            aria-modal="true"
            aria-labelledby="investor-pending-title"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setShowPendingModal(false)}
          >
            <motion.div
              className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 text-left shadow-2xl"
              initial={{ opacity: 0, y: 18, scale: 0.97 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 12, scale: 0.98 }}
              transition={{ duration: 0.18 }}
              onClick={e => e.stopPropagation()}
            >
              <div className="flex items-start gap-3">
                <div className="mt-0.5 flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-xl bg-[#0B1B3E]/5 text-[#0B1B3E]">
                  <MailCheck className="h-5 w-5" aria-hidden="true" />
                </div>
                <div className="min-w-0">
                  <h2 id="investor-pending-title" className="text-base font-semibold text-slate-900">
                    Investor approval is pending
                  </h2>
                  <p className="mt-2 text-sm leading-6 text-slate-600">
                    We have sent an approval link to{' '}
                    <span className="font-semibold text-slate-800">{pendingEmail}</span>.
                    The investor must approve via that emailed link before onboarding continues — the
                    remaining steps stay locked until then.
                  </p>
                </div>
              </div>

              <div className="mt-5 flex items-start gap-2.5 rounded-xl border border-amber-100 bg-amber-50 px-4 py-3">
                <Info className="h-4 w-4 flex-shrink-0 text-amber-500 mt-0.5" aria-hidden="true" />
                <p className="text-xs leading-relaxed text-amber-800">
                  Once the investor approves, you can return to complete steps 2–5 (credentials, address,
                  bank and agreements).
                </p>
              </div>

              <div className="mt-6 flex justify-end">
                <button
                  type="button"
                  onClick={() => setShowPendingModal(false)}
                  className="rounded-lg bg-[#0B1B3E] px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
                >
                  Got it
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
