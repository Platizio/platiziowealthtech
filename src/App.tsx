import React, { useState, useEffect, Suspense, lazy } from 'react';
import { Routes, Route, Navigate, useNavigate } from 'react-router-dom';

// ── Eager imports ────────────────────────────────────────────────────────
// AppLayout wraps every authenticated route; lazy-loading it would defeat
// the bundle-split goal (it'd be requested on every nav anyway). LandingPage
// and LoginPage are the first paint for unauthenticated users — lazy-loading
// them would replace the initial UI with a spinner.
import AppLayout from './layout/AppLayout';
import LandingPage from './views/LandingPage';
import LoginPage from './views/LoginPage';
import { apiFetch, SESSION_EXPIRED_EVENT } from './config/api';

// ── Lazy-loaded routes (F-33) ────────────────────────────────────────────
// Each `lazy(() => import('./views/X'))` becomes its own bundle chunk under
// Vite/Rollup — chunks are auto-named from the import path, so we do NOT
// need /* webpackChunkName */ magic comments here (this project uses Vite,
// not Webpack; those comments would be dead code).
const PendingApproval     = lazy(() => import('./views/PendingApproval'));
const Onboarding          = lazy(() => import('./views/Onboarding'));
const Unauthorized        = lazy(() => import('./views/Unauthorized'));

// Distributor views
const Dashboard           = lazy(() => import('./views/Dashboard'));
const Investors           = lazy(() => import('./views/Investors'));
const Ledger              = lazy(() => import('./views/Ledger'));
const Transactions        = lazy(() => import('./views/Transactions'));
const Profile             = lazy(() => import('./views/Profile'));
const Earnings            = lazy(() => import('./views/Earnings'));
const Notifications       = lazy(() => import('./views/Notifications'));
const Leads               = lazy(() => import('./views/Leads'));
const AumBreakdown        = lazy(() => import('./views/AumBreakdown'));
const SipDashboard        = lazy(() => import('./views/SipDashboard'));
const ActionCenter        = lazy(() => import('./views/ActionCenter'));
const InvestorOnboarding  = lazy(() => import('./views/InvestorOnboarding'));
const InvestorTransaction = lazy(() => import('./views/InvestorTransaction'));
const Portfolio           = lazy(() => import('./views/Portfolio'));
const Reports             = lazy(() => import('./views/Reports'));
const Communications      = lazy(() => import('./views/Communications'));

// Admin views
const AdminOverview       = lazy(() => import('./views/AdminOverview'));
const DistributorMgmt     = lazy(() => import('./views/DistributorMgmt'));
const ProductMgmt         = lazy(() => import('./views/ProductMgmt'));
const InvestorMgmt        = lazy(() => import('./views/InvestorMgmt'));

/**
 * F-33: fallback shown while a lazy chunk is being fetched. Visually matches
 * the existing session-restore spinner so chunk loads don't introduce a new
 * loading style. Kept inline (not a separate file) — it's small and only
 * used here.
 */
function PageSkeleton() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin" />
    </div>
  );
}

const ADMIN_ROLES = new Set(['ADMIN', 'MASTER_DISTRIBUTOR']);

const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';

const normalizeAuthUser = (user: any) => {
  if (!user) return null;
  return {
    ...user,
    id: user.id || user.distributorId,
  };
};

export default function App() {
  const [userSession, setUserSession] = useState<any>(null);
  const [loadingSession, setLoadingSession] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    const restoreSession = async () => {
      const isProtectedPath = window.location.pathname.startsWith('/admin') || window.location.pathname.startsWith('/distributor');
      if (!isProtectedPath) {
        setLoadingSession(false);
        return;
      }

      try {
        let res = await apiFetch('/auth/me', { skipAuthRedirect: true });
        if (res.status === 401) {
          const refreshRes = await apiFetch('/auth/refresh', { method: 'POST', skipAuthRedirect: true });
          if (refreshRes.ok) {
            res = await apiFetch('/auth/me', { skipAuthRedirect: true });
          }
        }

        if (res.status === 401 || res.status === 403) {
          if (window.location.pathname.startsWith('/admin') || window.location.pathname.startsWith('/distributor')) {
            navigate('/login?reason=session_expired', { replace: true });
          }
          setUserSession(null);
          return;
        }
        if (res.ok) {
          const user = await res.json();
          setUserSession(normalizeAuthUser(user));
          return;
        }
        throw new Error('Failed to restore session');
      } catch (err) {
        console.error('Session restore failed:', err);
        setUserSession(null);
      } finally {
        setLoadingSession(false);
      }
    };

    restoreSession();
  }, [navigate]);

  useEffect(() => {
    const handleSessionExpired = () => {
      setUserSession(null);
      navigate('/login?reason=session_expired', { replace: true });
    };

    window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired);
  }, [navigate]);

  const getUserData = () => {
    if (!userSession) return null;
    return Array.isArray(userSession) ? userSession[0] : userSession;
  };

  const userData = getUserData();
  const canAccessAdmin = ADMIN_ROLES.has(normalizeRole(userData?.role));

  const handleLoginSuccess = (user?: any) => {
    console.log("App.tsx -> handleLoginSuccess called. Received user:", user);
    const normalizedUser = normalizeAuthUser(user);
    if (normalizedUser?.id) {
      setUserSession(normalizedUser);
    }
    navigate('/distributor/dashboard');
  };

  const clearAuthContext = () => {
    setUserSession(null);
    window.sessionStorage.removeItem('authToken');
    window.sessionStorage.removeItem('userSession');
    window.localStorage.removeItem('authToken');
    window.localStorage.removeItem('userSession');
  };

  const handleSignOut = async () => {
    console.log("App.tsx -> handleSignOut called. Invalidating server session.");
    try {
      const response = await apiFetch('/auth/logout', { method: 'POST', skipAuthRedirect: true });
      if (!response.ok) {
        console.error('Logout request failed:', response.status);
      }
    } catch (err) {
      console.error('Logout request failed:', err);
    } finally {
      clearAuthContext();
      navigate('/login', { replace: true });
    }
  };

  if (loadingSession) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin"></div>
      </div>
    );
  }

  return (
    // F-33: Suspense catches the loading state for any lazy route below.
    // PageSkeleton renders briefly on first navigation to a not-yet-fetched
    // chunk; subsequent navigations to the same route reuse the cached chunk.
    <Suspense fallback={<PageSkeleton />}>
    <Routes>
      {/* ── Pre-app screens ─────────────────────────────────────────────────────── */}
      <Route path="/" element={<LandingPage onLogin={() => navigate('/login')} onSignUp={() => navigate('/onboarding')} />} />
      <Route path="/login" element={<LoginPage onLogin={handleLoginSuccess} onSignUp={() => navigate('/onboarding')} onBack={() => navigate('/')} />} />
      <Route path="/onboarding" element={<Onboarding onComplete={() => navigate('/pending')} onBack={() => navigate('/')} />} />
      <Route path="/pending" element={<PendingApproval onGoToLogin={() => navigate('/login')} />} />
      {/* F-30: shown to authenticated users whose role lacks access (e.g. a
          non-admin hitting an /admin/* path). Top-level so it works regardless
          of session state (e.g. user follows a stale admin link from chat). */}
      <Route path="/unauthorized" element={<Unauthorized />} />

      {/* ── App Layout ────────────────────────────────────────────────────────── */}
      {userSession && (
        <Route element={<AppLayout userData={userData} onSignOut={handleSignOut} />}>
          
          {/* Distributor Routes */}
          <Route path="/distributor/dashboard" element={<Dashboard userData={userData} onNavigate={(v) => navigate(`/distributor/${v}`)} />} />
          <Route path="/distributor/investors" element={<Investors onInvest={(inv) => navigate('/distributor/investor-transaction', { state: { investor: inv } })} userData={userData} />} />
          <Route path="/distributor/ledger" element={<Ledger userData={userData} />} />
          <Route path="/distributor/transactions" element={<Transactions userData={userData} />} />
          <Route path="/distributor/leads" element={<Leads userData={userData} onStartOnboarding={(prospect) => navigate('/distributor/investor-onboarding', { state: { prospect } })} />} />
          <Route path="/distributor/earnings" element={<Earnings />} />
          <Route path="/distributor/notifications" element={<Notifications userData={userData} />} />
          <Route path="/distributor/profile" element={<Profile userData={userData} />} />
          <Route path="/distributor/aum-breakdown"   element={<AumBreakdown onBack={() => navigate('/distributor/dashboard')} userData={userData} />} />
          <Route path="/distributor/sip-dashboard"   element={<SipDashboard onBack={() => navigate('/distributor/dashboard')} userData={userData} />} />
          <Route path="/distributor/action-center"   element={<ActionCenter onBack={() => navigate('/distributor/dashboard')} />} />
          <Route path="/distributor/portfolio"       element={<Portfolio userData={userData} />} />
          <Route path="/distributor/reports"         element={<Reports userData={userData} />} />
          <Route path="/distributor/communications"  element={<Communications userData={userData} />} />
          
          {/* Pass state dynamically or let the components grab it from location state */}
          <Route path="/distributor/investor-onboarding" element={<InvestorOnboardingWrapper userData={userData} />} />
          <Route path="/distributor/investor-transaction" element={<InvestorTransactionWrapper />} />

          {/* Admin Routes */}
          {canAccessAdmin ? (
            <>
              <Route path="/admin/overview" element={<AdminOverview />} />
              <Route path="/admin/distributor-mgmt" element={<DistributorMgmt userData={userData} />} />
              <Route path="/admin/product-mgmt" element={<ProductMgmtWrapper userData={userData} />} />
              <Route path="/admin/investor-mgmt" element={<InvestorMgmt userData={userData} />} />
            </>
          ) : (
            // F-30: explicit /unauthorized feedback instead of a silent bounce
            // to /distributor/dashboard, so the user understands why their
            // navigation was blocked.
            <Route path="/admin/*" element={<Navigate to="/unauthorized" replace />} />
          )}

        </Route>
      )}

      {/* Fallback route */}
      <Route path="*" element={<Navigate to={userSession ? "/distributor/dashboard" : "/"} replace />} />
    </Routes>
    </Suspense>
  );
}

// Helper wrappers for components that were relying on App.tsx state
import { useLocation } from 'react-router-dom';

function InvestorOnboardingWrapper({ userData }: { userData?: any }) {
  const location = useLocation();
  const navigate = useNavigate();
  const prospect = location.state?.prospect || null;
  return <InvestorOnboarding prospect={prospect} userData={userData} onComplete={() => navigate('/distributor/investors')} onBack={() => navigate('/distributor/leads')} />;
}

function InvestorTransactionWrapper() {
  const location = useLocation();
  const navigate = useNavigate();
  const investor = location.state?.investor || null;
  if (!investor) return <Navigate to="/distributor/investors" replace />;
  return <InvestorTransaction investor={investor} onComplete={() => navigate('/distributor/investors')} onBack={() => navigate('/distributor/investors')} />;
}

function ProductMgmtWrapper({ userData }: { userData?: any }) {
  // ProductMgmt used to get products state from App.tsx. 
  // We can either initialize it here or refactor ProductMgmt to fetch its own data.
  // For now, initializing empty state to not break the component signature.
  const [products, setProducts] = useState<any[]>([]);
  return <ProductMgmt products={products} setProducts={setProducts} userData={userData} />;
}
