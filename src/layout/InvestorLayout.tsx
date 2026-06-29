import React, { useEffect, useState } from 'react';
import { Outlet, NavLink, useNavigate, useLocation } from 'react-router-dom';
import {
  LayoutDashboard, ClipboardCheck, Wallet, FileCheck, UserCircle2,
  LogOut, Menu, ShieldCheck, Sparkles, Users,
} from 'lucide-react';
import type { InvestorUser } from '../types/investorAuth';

/**
 * Investor-portal chrome. Deliberately mirrors the distributor {@link AppLayout}
 * (same white w-64 sidebar, "P" logo, NavItem active states, #F1F5F9 content,
 * sticky h-16 header) so the two portals feel like one product — only the nav
 * items, footer and header actions differ.
 */
const INVESTOR_NAV = [
  { id: '/investor/dashboard',   icon: <LayoutDashboard className="w-4 h-4" />, label: 'Dashboard' },
  { id: '/investor/approvals',   icon: <ClipboardCheck className="w-4 h-4" />,  label: 'Approvals' },
  { id: '/investor/withdrawals', icon: <Wallet className="w-4 h-4" />,          label: 'Withdraw' },
  { id: '/investor/nominations', icon: <Users className="w-4 h-4" />,           label: 'Nominees' },
  { id: '/investor/onboarding',  icon: <FileCheck className="w-4 h-4" />,       label: 'KYC Approval' },
];

const INVESTOR_NAV_ACCOUNT = [
  { id: '/investor/profile', icon: <UserCircle2 className="w-4 h-4" />, label: 'Profile' },
];

export default function InvestorLayout({
  investor,
  onSignOut,
}: {
  investor: InvestorUser;
  onSignOut: () => void;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  // Close the sidebar on navigation (mobile UX) — mirrors AppLayout.
  useEffect(() => {
    setSidebarOpen(false);
  }, [location.pathname]);

  const displayName = investor.fullName || investor.email || 'Investor';
  const initials =
    displayName !== 'Investor'
      ? displayName.split(' ').map((n) => n[0]).join('').substring(0, 2).toUpperCase()
      : 'IN';
  const linked = investor.investorLinked === true;

  return (
    <div className="flex h-screen w-full overflow-hidden bg-[#F1F5F9] text-slate-900 font-sans selection:bg-blue-100 selection:text-blue-900">
      {/* Mobile overlay backdrop */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-40 bg-slate-900/40 md:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar */}
      <aside className={`
        w-64 bg-white border-r border-slate-200 flex flex-col flex-shrink-0
        fixed inset-y-0 left-0 z-50 transition-transform duration-200
        md:static md:inset-auto md:z-auto md:translate-x-0
        ${sidebarOpen ? 'translate-x-0' : '-translate-x-full'}
      `}>
        <div className="p-6 flex-1 overflow-y-auto">
          <button
            type="button"
            onClick={() => navigate('/investor/dashboard')}
            className="flex items-center gap-3 mb-8"
          >
            <div className="w-8 h-8 rounded-xl flex items-center justify-center text-white font-bold shadow-sm bg-[#0B1B3E]">P</div>
            <div className="text-left">
              <span className="font-bold text-xl tracking-tight text-[#0B1B3E] block leading-tight">Platizio</span>
              <span className="text-[10px] font-bold text-blue-600 bg-blue-50 px-1.5 py-0.5 rounded uppercase tracking-wider">
                Investor
              </span>
            </div>
          </button>

          <nav className="space-y-0.5">
            {INVESTOR_NAV.map((item) => (
              <NavItem key={item.id} to={item.id} icon={item.icon} label={item.label} />
            ))}
          </nav>

          <NavLink
            to="/investor/luxe"
            className="mt-2 flex items-center gap-3 rounded-lg border border-amber-200/70 bg-gradient-to-r from-amber-50 to-white px-4 py-2.5 text-sm font-semibold text-[#0B1B3E] transition-all hover:-translate-y-0.5 hover:shadow-sm"
          >
            <Sparkles className="h-4 w-4 text-amber-500" />
            <span className="flex-1">Luxe view</span>
            <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider text-amber-600">New</span>
          </NavLink>

          <div className="border-t border-slate-100 my-3" />

          <nav className="space-y-0.5">
            {INVESTOR_NAV_ACCOUNT.map((item) => (
              <NavItem key={item.id} to={item.id} icon={item.icon} label={item.label} />
            ))}
          </nav>
        </div>

        {/* Sidebar footer */}
        <div className="p-5 border-t border-slate-100 flex-shrink-0">
          <div className="bg-slate-50 px-4 py-3 rounded-xl border border-slate-100 mb-4">
            <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">Account</p>
            <p className={`text-sm font-semibold flex items-center gap-1.5 ${linked ? 'text-green-600' : 'text-amber-600'}`}>
              <span className="relative flex h-2 w-2">
                <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${linked ? 'bg-green-400' : 'bg-amber-400'}`} />
                <span className={`relative inline-flex rounded-full h-2 w-2 ${linked ? 'bg-green-500' : 'bg-amber-500'}`} />
              </span>
              {linked ? 'Linked to portfolio' : 'Pending link'}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-full bg-slate-200 flex items-center justify-center text-xs font-bold text-slate-600">
              {initials}
            </div>
            <div className="min-w-0">
              <p className="text-sm font-semibold text-slate-800 truncate">{displayName}</p>
              <button
                type="button"
                onClick={onSignOut}
                className="text-xs text-slate-500 cursor-pointer hover:text-red-500 transition-colors flex items-center gap-1"
              >
                <LogOut className="w-3 h-3" /> Sign out
              </button>
            </div>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 flex flex-col min-w-0">
        <header className="h-16 bg-white/80 backdrop-blur-md border-b border-slate-200 flex items-center gap-3 px-4 md:px-8 flex-shrink-0 sticky top-0 z-40">
          <button
            className="md:hidden p-2 rounded-lg text-slate-500 hover:bg-slate-100 transition-colors flex-shrink-0"
            onClick={() => setSidebarOpen((p) => !p)}
            aria-label="Toggle sidebar"
          >
            <Menu className="w-5 h-5" aria-hidden="true" />
          </button>

          <div className="hidden md:flex items-center gap-2 text-sm text-slate-500">
            <ShieldCheck className="w-4 h-4 text-[#0B1B3E]" />
            <span>Investor Portal</span>
          </div>

          <div className="flex-1" />

          <div className="flex items-center gap-3">
            <span className="hidden sm:inline text-sm font-medium text-slate-700">{displayName}</span>
            <button
              type="button"
              onClick={onSignOut}
              className="flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition-colors hover:bg-slate-50"
            >
              <LogOut className="h-3.5 w-3.5" /> Sign out
            </button>
          </div>
        </header>

        <div className="flex-1 overflow-auto bg-[#F1F5F9]">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

const NavItem: React.FC<{ to: string; icon: React.ReactNode; label: string }> = ({ to, icon, label }) => (
  <NavLink
    to={to}
    className={({ isActive }) => `w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-lg transition-colors text-left ${
      isActive ? 'bg-blue-50/80 text-blue-700' : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
    }`}
  >
    {({ isActive }) => (
      <>
        <span className={isActive ? 'text-blue-600' : 'text-slate-400'}>{icon}</span>
        <span className="flex-1">{label}</span>
      </>
    )}
  </NavLink>
);
