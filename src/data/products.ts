// ─── Shared product data ────────────────────────────────────────────────────
// Single source of truth consumed by both ProductMgmt (admin) and Ledger
// (distributor).  App.tsx holds this as React state so changes made by the
// admin are immediately reflected in the distributor catalog.

export interface Product {
  id:          string | number;
  name:        string;
  assetClass:  'MF' | 'SIF';
  category:    string;
  return1y:    string;   // e.g. "+28.4%"
  minInvest:   string;   // e.g. "₹1,000"
  nav:         string;   // e.g. "₹245.60"
  visibility:  string;   // 'All Tiers' | 'Silver & Above' | 'Gold & Above' | 'Platinum Only'
  riskLevel:   string;   // 'Low' | 'Moderate' | 'High' | 'Very High'
  status:      'Active' | 'Inactive';
  amc:         string;
  externalSchemeCode?: string;
  externalIsin?:       string;
  productType?:        string;
}

// ── Distributor tier (simulated for Aditya Sharma) ──────────────────────────
export const DISTRIBUTOR_TIER = 'Gold';

const TIER_ORDER = ['Bronze', 'Silver', 'Gold', 'Platinum'];

/** Returns true when a product should be visible to the given distributor tier */
export function isVisibleToTier(visibility: string, tier: string): boolean {
  const idx = TIER_ORDER.indexOf(tier);
  if (visibility === 'All Tiers')       return true;
  if (visibility === 'Silver & Above')  return idx >= 1;
  if (visibility === 'Gold & Above')    return idx >= 2;
  if (visibility === 'Platinum Only')   return idx >= 3;
  return true;
}

// ── Seed data ────────────────────────────────────────────────────────────────
export const initialProducts: Product[] = [
  { id:  1, name: 'HDFC Large & Mid Cap Fund',   assetClass: 'MF',  category: 'Equity',    return1y: '+28.4%', minInvest: '₹100',        nav: '₹245.60',   visibility: 'All Tiers',      riskLevel: 'Very High', status: 'Active', amc: 'HDFC AMC'       },
  { id:  2, name: 'Parag Parikh Flexi Cap Fund', assetClass: 'MF',  category: 'Equity',    return1y: '+32.1%', minInvest: '₹1,000',      nav: '₹68.32',    visibility: 'All Tiers',      riskLevel: 'Very High', status: 'Active', amc: 'PPFAS AMC'      },
  { id:  3, name: 'ICICI Prudential Bluechip',   assetClass: 'MF',  category: 'Equity',    return1y: '+21.5%', minInvest: '₹100',        nav: '₹89.15',    visibility: 'All Tiers',      riskLevel: 'High',      status: 'Active', amc: 'ICICI Pru AMC'  },
  { id:  4, name: 'SBI Liquid Fund',             assetClass: 'MF',  category: 'Debt',      return1y: '+6.8%',  minInvest: '₹500',        nav: '₹3,451.20', visibility: 'All Tiers',      riskLevel: 'Low',       status: 'Active', amc: 'SBI AMC'        },
  { id:  5, name: 'Quant Small Cap Fund',        assetClass: 'MF',  category: 'Equity',    return1y: '+45.2%', minInvest: '₹5,000',      nav: '₹182.40',   visibility: 'Gold & Above',   riskLevel: 'Very High', status: 'Active', amc: 'Quant AMC'      },
  { id:  6, name: 'Kotak Corporate Bond Fund',   assetClass: 'MF',  category: 'Debt',      return1y: '+7.2%',  minInvest: '₹500',        nav: '₹3,182.10', visibility: 'All Tiers',      riskLevel: 'Moderate',  status: 'Active', amc: 'Kotak AMC'      },
  { id:  7, name: 'DSP ELSS Tax Saver Fund',     assetClass: 'MF',  category: 'ELSS',      return1y: '+24.8%', minInvest: '₹500',        nav: '₹112.45',   visibility: 'All Tiers',      riskLevel: 'High',      status: 'Active', amc: 'DSP AMC'        },
  { id:  8, name: 'Mirae Asset Hybrid Equity',   assetClass: 'MF',  category: 'Hybrid',    return1y: '+18.3%', minInvest: '₹1,000',      nav: '₹95.80',    visibility: 'Silver & Above', riskLevel: 'High',      status: 'Active', amc: 'Mirae AMC'      },
  { id:  9, name: 'Platizio SIF Growth Fund',     assetClass: 'SIF', category: 'Strategic', return1y: '+18.5%', minInvest: '₹10,00,000',  nav: '₹1,240.00', visibility: 'Platinum Only',  riskLevel: 'High',      status: 'Active', amc: 'Platizio'       },
  { id: 10, name: 'Platizio SIF Income Fund',     assetClass: 'SIF', category: 'Strategic', return1y: '+12.2%', minInvest: '₹5,00,000',   nav: '₹880.50',   visibility: 'Gold & Above',   riskLevel: 'Moderate',  status: 'Active', amc: 'Platizio'       },
];
