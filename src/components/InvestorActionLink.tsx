import React, { useState } from 'react';
import { AlertCircle, CheckCircle2, Copy, ExternalLink, Link2 } from 'lucide-react';
import {
  buildInvestorActionUrl,
  formatOrderStatusLabel,
  isPaymentRedirectUrl,
  orderNeedsInvestorLink,
} from '../utils/investorAction';

type Props = {
  orderId?: string;
  orderStatus?: string;
  investorActionUrl?: string;
  title?: string;
  description?: string;
  compact?: boolean;
};

export default function InvestorActionLink({
  orderId,
  orderStatus,
  investorActionUrl,
  title,
  description,
  compact = false,
}: Props) {
  const [copied, setCopied] = useState(false);
  const actionUrl = buildInvestorActionUrl(investorActionUrl);
  const showPanel = orderNeedsInvestorLink(orderStatus, investorActionUrl);

  if (!showPanel) return null;

  const isPaymentLink = isPaymentRedirectUrl(actionUrl || undefined);
  const panelTitle =
    title ||
    (isPaymentLink ? 'Complete payment' : 'Investor confirmation required');
  const panelDescription =
    description ||
    (isPaymentLink
      ? 'Open the payment page so the investor can authorize this purchase. Share the link if they are on another device.'
      : 'Send this link to the investor. They must confirm the order and authorize payment on the page served by Platizio.');

  const copyLink = async () => {
    if (!actionUrl) return;
    try {
      await navigator.clipboard.writeText(actionUrl);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      window.prompt('Copy this link for the investor:', actionUrl);
    }
  };

  return (
    <div
      className={`rounded-2xl border ${
        isPaymentLink ? 'border-orange-200 bg-orange-50' : 'border-amber-200 bg-amber-50'
      } ${compact ? 'p-4' : 'p-5'}`}
    >
      <div className="flex gap-3">
        <AlertCircle
          className={`mt-0.5 h-5 w-5 flex-shrink-0 ${
            isPaymentLink ? 'text-orange-600' : 'text-amber-600'
          }`}
        />
        <div className="min-w-0 flex-1">
          <p
            className={`text-sm font-semibold ${
              isPaymentLink ? 'text-orange-900' : 'text-amber-900'
            }`}
          >
            {panelTitle}
          </p>
          <p
            className={`mt-1 text-xs leading-relaxed ${
              isPaymentLink ? 'text-orange-800' : 'text-amber-800'
            }`}
          >
            {panelDescription}
          </p>

          {orderId && (
            <p className="mt-2 text-[11px] font-mono text-slate-600">
              Order ID: {orderId}
            </p>
          )}
          {orderStatus && (
            <p className="mt-1 text-[11px] text-slate-600">
              Status: {formatOrderStatusLabel(orderStatus)}
            </p>
          )}

          {actionUrl ? (
            <div className="mt-4 flex flex-wrap gap-2">
              <a
                href={actionUrl}
                target="_blank"
                rel="noopener noreferrer"
                className={`inline-flex items-center gap-2 rounded-lg px-4 py-2 text-xs font-semibold text-white transition-colors ${
                  isPaymentLink
                    ? 'bg-orange-600 hover:bg-orange-700'
                    : 'bg-amber-600 hover:bg-amber-700'
                }`}
              >
                <ExternalLink className="h-3.5 w-3.5" />
                {isPaymentLink ? 'Open Payment Page' : 'Open Investor Link'}
              </a>
              <button
                type="button"
                onClick={copyLink}
                className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-2 text-xs font-semibold text-slate-700 transition-colors hover:bg-slate-50"
              >
                {copied ? (
                  <>
                    <CheckCircle2 className="h-3.5 w-3.5 text-green-600" /> Copied
                  </>
                ) : (
                  <>
                    <Copy className="h-3.5 w-3.5" /> Copy Link
                  </>
                )}
              </button>
            </div>
          ) : (
            <p className="mt-3 text-xs text-slate-600">
              Investor action link is not available yet. Refresh the order in Transactions after a moment.
            </p>
          )}

          {!compact && actionUrl && (
            <div className="mt-3 flex items-start gap-2 rounded-xl border border-white/60 bg-white/70 px-3 py-2">
              <Link2 className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-slate-400" />
              <p className="break-all text-[11px] text-slate-600">{actionUrl}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
