import React from 'react';
import { AlertTriangle } from 'lucide-react';
import type { CybrillaKycWarning } from '../utils/kycPreVerification';

type Props = {
  warnings: CybrillaKycWarning[];
  title?: string;
  className?: string;
};

export default function CybrillaKycWarnings({
  warnings,
  title = 'Cybrilla KYC warnings',
  className = '',
}: Props) {
  if (warnings.length === 0) return null;

  return (
    <div
      className={`rounded-xl border border-red-200 bg-red-50 p-4 ${className}`}
      role="alert"
      aria-live="polite"
    >
      <div className="flex items-start gap-3">
        <AlertTriangle className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-600" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-red-900">{title}</p>
          <ul className="mt-2 space-y-2">
            {warnings.map(warning => (
              <li key={warning.id} className="text-xs leading-5 text-red-800">
                <span className="font-semibold">{warning.label}</span>
                {warning.code && (
                  <span className="ml-1.5 rounded bg-red-100 px-1.5 py-0.5 font-mono text-[10px] uppercase text-red-700">
                    {warning.code}
                  </span>
                )}
                <p className="mt-0.5 text-red-700">{warning.reason}</p>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
