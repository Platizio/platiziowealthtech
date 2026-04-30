import React, { useState, useEffect } from 'react';
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom';

// Auth / pre-app screens
import LandingPage from './views/LandingPage';
import LoginPage from './views/LoginPage';
import PendingApproval from './views/PendingApproval';
import Onboarding from './views/Onboarding';

// Distributor views
import Dashboard from './views/Dashboard';
import Investors from './views/Investors';
import Ledger from './views/Ledger';
import Transactions from './views/Transactions';
import Profile from './views/Profile';
import Earnings from './views/Earnings';
import Notifications from './views/Notifications';
import Leads from './views/Leads';
import AumBreakdown from './views/AumBreakdown';
import SipDashboard from './views/SipDashboard';
import ActionCenter from './views/ActionCenter';
import InvestorOnboarding from './views/InvestorOnboarding';
import InvestorTransaction from './views/InvestorTransaction';

// Admin views
import AdminOverview from './views/AdminOverview';
import DistributorMgmt from './views/DistributorMgmt';
import ProductMgmt from './views/ProductMgmt';
import InvestorMgmt from './views/InvestorMgmt';

import AppLayout from './layout/AppLayout';

export default function App() {
  const [userSession, setUserSession] = useState<any>(null);
  const navigate = useNavigate();

  useEffect(() => {
    const savedUser = sessionStorage.getItem('userSession');
    if (savedUser) {
      try {
        const user = JSON.parse(savedUser);
        setUserSession(user);
      } catch (e) {
        console.error("Failed to parse saved user session");
      }
    }
  }, []);

  const getUserData = () => {
    if (!userSession) return null;
    return Array.isArray(userSession) ? userSession[0] : userSession;
  };

  const userData = getUserData();

  const handleLoginSuccess = (user?: any) => {
    console.log("App.tsx -> handleLoginSuccess called. Received user:", user);
    if (user) {
      setUserSession(user);
      sessionStorage.setItem('userSession', JSON.stringify(user));
    }
    navigate('/distributor/dashboard');
  };

  const handleSignOut = () => {
    console.log("App.tsx -> handleSignOut called. Clearing user session.");
    setUserSession(null);
    sessionStorage.clear();
    navigate('/');
  };

  // Provide user session context to Layout and views if needed.
  // We can pass userData down to views via props in the Route elements.

  return (
    <Routes>
      {/* ── Pre-app screens ─────────────────────────────────────────────────────── */}
      <Route path="/" element={<LandingPage onLogin={() => navigate('/login')} onSignUp={() => navigate('/onboarding')} />} />
      <Route path="/login" element={<LoginPage onLogin={handleLoginSuccess} onSignUp={() => navigate('/onboarding')} onBack={() => navigate('/')} />} />
      <Route path="/onboarding" element={<Onboarding onComplete={() => navigate('/pending')} onBack={() => navigate('/')} />} />
      <Route path="/pending" element={<PendingApproval onGoToLogin={() => navigate('/login')} />} />

      {/* ── App Layout ────────────────────────────────────────────────────────── */}
      {userSession && (
        <Route element={<AppLayout userData={userData} onSignOut={handleSignOut} />}>
          
          {/* Distributor Routes */}
          <Route path="/distributor/dashboard" element={<Dashboard onNavigate={(v) => navigate(`/distributor/${v}`)} />} />
          <Route path="/distributor/investors" element={<Investors onInvest={(inv) => navigate('/distributor/investor-transaction', { state: { investor: inv } })} userData={userData} />} />
          <Route path="/distributor/ledger" element={<Ledger />} />
          <Route path="/distributor/transactions" element={<Transactions />} />
          <Route path="/distributor/leads" element={<Leads userData={userData} onStartOnboarding={(prospect) => navigate('/distributor/investor-onboarding', { state: { prospect } })} />} />
          <Route path="/distributor/earnings" element={<Earnings />} />
          <Route path="/distributor/notifications" element={<Notifications />} />
          <Route path="/distributor/profile" element={<Profile userData={userData} />} />
          <Route path="/distributor/aum-breakdown" element={<AumBreakdown onBack={() => navigate('/distributor/dashboard')} />} />
          <Route path="/distributor/sip-dashboard" element={<SipDashboard onBack={() => navigate('/distributor/dashboard')} />} />
          <Route path="/distributor/action-center" element={<ActionCenter onBack={() => navigate('/distributor/dashboard')} />} />
          
          {/* Pass state dynamically or let the components grab it from location state */}
          <Route path="/distributor/investor-onboarding" element={<InvestorOnboardingWrapper />} />
          <Route path="/distributor/investor-transaction" element={<InvestorTransactionWrapper />} />

          {/* Admin Routes */}
          <Route path="/admin/overview" element={<AdminOverview />} />
          <Route path="/admin/distributor-mgmt" element={<DistributorMgmt userData={userData} />} />
          <Route path="/admin/product-mgmt" element={<ProductMgmtWrapper />} />
          <Route path="/admin/investor-mgmt" element={<InvestorMgmt />} />

        </Route>
      )}

      {/* Fallback route */}
      <Route path="*" element={<Navigate to={userSession ? "/distributor/dashboard" : "/"} replace />} />
    </Routes>
  );
}

// Helper wrappers for components that were relying on App.tsx state
import { useLocation } from 'react-router-dom';

function InvestorOnboardingWrapper() {
  const location = useLocation();
  const navigate = useNavigate();
  const prospect = location.state?.prospect || null;
  return <InvestorOnboarding prospect={prospect} onComplete={() => navigate('/distributor/investors')} onBack={() => navigate('/distributor/leads')} />;
}

function InvestorTransactionWrapper() {
  const location = useLocation();
  const navigate = useNavigate();
  const investor = location.state?.investor || null;
  if (!investor) return <Navigate to="/distributor/investors" replace />;
  return <InvestorTransaction investor={investor} onComplete={() => navigate('/distributor/investors')} onBack={() => navigate('/distributor/investors')} />;
}

function ProductMgmtWrapper() {
  // ProductMgmt used to get products state from App.tsx. 
  // We can either initialize it here or refactor ProductMgmt to fetch its own data.
  // For now, initializing empty state to not break the component signature.
  const [products, setProducts] = useState<any[]>([]);
  return <ProductMgmt products={products} setProducts={setProducts} />;
}
