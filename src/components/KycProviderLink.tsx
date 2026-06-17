import React, { useState } from 'react';
import { ExternalLink } from 'lucide-react';

type Props = {
  href: string;
  label: string;
  icon?: React.ReactNode;
  disabled?: boolean;
  disabledReason?: string;
  className?: string;
};

export default function KycProviderLink({
  href,
  label,
  icon,
  disabled = false,
  disabledReason = 'Link disabled while the Platizio step is in progress.',
  className = 'mt-3 inline-flex items-center gap-1.5 text-xs font-semibold text-indigo-700 hover:text-indigo-800',
}: Props) {
  const [opened, setOpened] = useState(false);
  const locked = disabled || opened || !href;

  if (locked) {
    const title = opened
      ? 'Link already opened — use Refresh status after the investor completes this step.'
      : disabledReason;
    return (
      <span
        className={`${className} cursor-not-allowed opacity-50`}
        title={title}
        aria-disabled="true"
      >
        {icon}
        {label}
        <ExternalLink className="h-3.5 w-3.5" />
      </span>
    );
  }

  return (
    <a
      href={href}
      target="_blank"
      rel="noreferrer"
      className={className}
      onClick={() => setOpened(true)}
    >
      {icon}
      {label}
      <ExternalLink className="h-3.5 w-3.5" />
    </a>
  );
}
