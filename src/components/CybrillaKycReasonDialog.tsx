import React, { useEffect, useRef } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { AlertTriangle, CheckCircle2, Info, ShieldCheck, X } from 'lucide-react';
import type { CybrillaKycWarning } from '../utils/kycPreVerification';
import { preVerificationStatusClasses } from '../utils/kycPreVerification';

export type KycReasonFieldRow = {
  field: string;
  label: string;
  status: string;
  code?: string;
  reason?: string;
};

export type CybrillaKycReasonDialogContent = {
  title: string;
  variant: 'error' | 'warning' | 'success' | 'info';
  summary?: string;
  warnings?: CybrillaKycWarning[];
  fieldRows?: KycReasonFieldRow[];
};

type Props = {
  open: boolean;
  content: CybrillaKycReasonDialogContent | null;
  onClose: () => void;
};

const variantStyles = {
  error: {
    icon: AlertTriangle,
    iconWrap: 'bg-red-50 text-red-600',
    title: 'text-red-950',
    summary: 'text-red-800',
    border: 'border-red-100',
  },
  warning: {
    icon: AlertTriangle,
    iconWrap: 'bg-amber-50 text-amber-600',
    title: 'text-amber-950',
    summary: 'text-amber-800',
    border: 'border-amber-100',
  },
  success: {
    icon: CheckCircle2,
    iconWrap: 'bg-green-50 text-green-600',
    title: 'text-green-950',
    summary: 'text-green-800',
    border: 'border-green-100',
  },
  info: {
    icon: Info,
    iconWrap: 'bg-blue-50 text-blue-600',
    title: 'text-slate-900',
    summary: 'text-slate-700',
    border: 'border-slate-200',
  },
} as const;

export default function CybrillaKycReasonDialog({ open, content, onClose }: Props) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    closeButtonRef.current?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!content) return null;

  const styles = variantStyles[content.variant];
  const Icon = styles.icon;
  const warnings = content.warnings ?? [];
  const fieldRows = (content.fieldRows ?? []).filter(
    row => row.reason || row.code || (row.status && row.status !== 'pending'),
  );

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-[70] flex items-center justify-center bg-slate-950/45 px-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          aria-labelledby="cybrilla-kyc-reason-title"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onClose}
        >
          <motion.div
            className={`w-full max-w-lg rounded-2xl border bg-white p-6 text-left shadow-2xl ${styles.border}`}
            initial={{ opacity: 0, y: 18, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 12, scale: 0.98 }}
            transition={{ duration: 0.18 }}
            onClick={event => event.stopPropagation()}
          >
            <div className="flex items-start justify-between gap-4">
              <div className="flex min-w-0 items-start gap-3">
                <div className={`mt-0.5 flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-xl ${styles.iconWrap}`}>
                  <Icon className="h-5 w-5" aria-hidden="true" />
                </div>
                <div className="min-w-0">
                  <h2 id="cybrilla-kyc-reason-title" className={`text-base font-semibold ${styles.title}`}>
                    {content.title}
                  </h2>
                  {content.summary && (
                    <p className={`mt-2 text-sm leading-6 ${styles.summary}`}>
                      {content.summary}
                    </p>
                  )}
                </div>
              </div>
              <button
                ref={closeButtonRef}
                type="button"
                onClick={onClose}
                className="rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-700"
                aria-label="Close Platizio KYC details"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {warnings.length > 0 && (
              <div className="mt-5 rounded-xl border border-red-100 bg-red-50/70 p-4">
                <p className="text-xs font-semibold uppercase tracking-wide text-red-900">Platizio messages</p>
                <ul className="mt-3 space-y-3">
                  {warnings.map(warning => (
                    <li key={warning.id} className="text-sm leading-5 text-red-900">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-semibold">{warning.label}</span>
                        {warning.code && (
                          <span className="rounded bg-red-100 px-1.5 py-0.5 font-mono text-[10px] uppercase text-red-700">
                            {warning.code}
                          </span>
                        )}
                        {warning.status && (
                          <span className="rounded bg-white px-1.5 py-0.5 text-[10px] font-bold uppercase text-red-600">
                            {warning.status}
                          </span>
                        )}
                      </div>
                      <p className="mt-1 text-red-800">{warning.reason}</p>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {fieldRows.length > 0 && (
              <div className="mt-5">
                <p className="mb-2 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-slate-500">
                  <ShieldCheck className="h-3.5 w-3.5" />
                  Pre-verification checks
                </p>
                <div className="space-y-2">
                  {fieldRows.map(row => (
                    <div key={row.field} className="rounded-xl border border-slate-100 bg-slate-50 p-3">
                      <div className="flex items-center justify-between gap-3">
                        <p className="text-xs font-semibold text-slate-600">{row.label}</p>
                        <span className={`rounded-full border px-2 py-0.5 text-[10px] font-bold uppercase ${preVerificationStatusClasses(row.status)}`}>
                          {row.status || 'pending'}
                        </span>
                      </div>
                      {(row.code || row.reason) && (
                        <p className="mt-2 text-xs leading-5 text-slate-700">
                          {[row.code, row.reason].filter(Boolean).join(' — ')}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="mt-6 flex justify-end">
              <button
                type="button"
                onClick={onClose}
                className="rounded-lg bg-[#0B1B3E] px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-[#1A3066]"
              >
                Got it
              </button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
