import type { DashboardAction, OnboardingPipeline } from '../store/api/platizioApi';

export interface DashboardMetrics {
  totalAum: number;
  investorCount: number;
  newInvestors30d: number;
  sipAmount: number;
  sipCount: number;
  failedSips: number;
  todayOrders: number;
  aumSub: Array<{ label: string; value: string; change: string; up: boolean }>;
  pendingSub: Array<{ label: string; value: number }>;
  leadSub: Array<{ label: string; value: number; color: string }>;
  donutData: Array<{ name: string; value: number; color: string }>;
  recentActivity: Array<{ date: Date; action: string; name: string; dot: string; time: string }>;
  lifeEventReminders: DashboardAction[];
  onboarding: OnboardingPipeline;
}

const emptyOnboarding: OnboardingPipeline = {
  kycPending: [],
  bankPending: [],
  readyToInvest: [],
};

export const emptyDashboardMetrics = (): DashboardMetrics => ({
  totalAum: 0,
  investorCount: 0,
  newInvestors30d: 0,
  sipAmount: 0,
  sipCount: 0,
  failedSips: 0,
  todayOrders: 0,
  aumSub: [],
  pendingSub: [],
  leadSub: [],
  donutData: [],
  recentActivity: [],
  lifeEventReminders: [],
  onboarding: emptyOnboarding,
});

const formatTime = (date: Date) => {
  const diff = Math.floor((Date.now() - date.getTime()) / 60000);
  if (diff < 60) return `${diff} min ago`;
  if (diff < 1440) return `${Math.floor(diff / 60)} hrs ago`;
  return `${Math.floor(diff / 1440)} days ago`;
};

export function buildDashboardMetrics(input: {
  orders: any[];
  investors: any[];
  schemes: any[];
  leads: any[];
  actions: DashboardAction[];
  onboarding: OnboardingPipeline;
}): DashboardMetrics {
  const { orders, investors, schemes, leads, actions, onboarding } = input;

  let totalAum = 0;
  let sipAmount = 0;
  let sipCount = 0;
  let failedSips = 0;
  let todayOrders = 0;
  let failedOrders = 0;
  let todaySuccessful = 0;
  let todayPending = 0;
  let todayFailed = 0;
  let mfAum = 0;
  let sifAum = 0;

  const schemeMap = new Map(schemes.map((scheme) => [scheme.id, scheme]));
  const today = new Date().toDateString();
  const activities: Array<{ date: Date; action: string; name: string; dot: string }> = [];

  orders.forEach((order: any) => {
    if (order.orderStatus === 'COMPLETED') {
      totalAum += order.amount || 0;
      const scheme = schemeMap.get(order.productSchemeId);
      const rawCat = (
        order.productCategory ||
        order.category ||
        order.product_category ||
        scheme?.productCategory ||
        scheme?.category ||
        scheme?.product_category ||
        scheme?.assetClass ||
        'OTHER'
      )
        .toString()
        .toUpperCase();

      const cat = rawCat.includes('MF') || rawCat.includes('MUTUAL') ? 'MF' : 'SIF';
      if (cat === 'MF') mfAum += order.amount || 0;
      else sifAum += order.amount || 0;
    }

    if (order.transactionType === 'SIP') {
      sipCount++;
      if (order.orderStatus === 'COMPLETED') sipAmount += order.amount || 0;
      if (order.orderStatus === 'FAILED') failedSips++;
    }

    if (new Date(order.createdAt || Date.now()).toDateString() === today) {
      todayOrders++;
      if (order.orderStatus === 'COMPLETED' || order.orderStatus === 'SUCCESSFUL') todaySuccessful++;
      else if (order.orderStatus === 'FAILED') todayFailed++;
      else todayPending++;
    }

    if (order.orderStatus === 'FAILED') failedOrders++;

    activities.push({
      date: new Date(order.createdAt || Date.now()),
      action: `Order ${order.orderStatus}`,
      name: `Order #${String(order.id).substring(0, 6)}`,
      dot:
        order.orderStatus === 'COMPLETED'
          ? 'bg-blue-500'
          : order.orderStatus === 'FAILED'
            ? 'bg-red-500'
            : 'bg-amber-500',
    });
  });

  let kycPending = 0;
  let bankPending = 0;
  investors.forEach((investor: any) => {
    if (investor.kycStatus !== 'COMPLETED') kycPending++;
    if (investor.bankVerificationStatus !== 'VERIFIED') bankPending++;

    activities.push({
      date: new Date(investor.createdAt || Date.now()),
      action: 'Investor Added',
      name: investor.fullName || 'Unknown',
      dot: 'bg-green-500',
    });
  });

  let newLeads = 0;
  let inProgressLeads = 0;
  let convertedLeads = 0;
  leads.forEach((lead: any) => {
    if (lead.status === 'NEW') newLeads++;
    else if (lead.status === 'CONVERTED_TO_INVESTOR' || lead.status === 'INVESTMENT_COMPLETED') convertedLeads++;
    else inProgressLeads++;
  });

  activities.sort((a, b) => b.date.getTime() - a.date.getTime());
  const recentActivity = activities.slice(0, 5).map((activity) => ({
    ...activity,
    time: formatTime(activity.date),
  }));

  const allLifeEventReminders = actions.filter(
    (action) => action.category === 'Life Event' || action.category === 'Maturing',
  );

  const cutoffMs = Date.now() - 30 * 24 * 60 * 60 * 1000;
  const newInvestors30d = investors.reduce((acc: number, investor: any) => {
    const timestamp = new Date(investor.createdAt ?? '').getTime();
    return Number.isFinite(timestamp) && timestamp >= cutoffMs ? acc + 1 : acc;
  }, 0);

  return {
    totalAum,
    investorCount: investors.length,
    newInvestors30d,
    sipAmount,
    sipCount,
    failedSips,
    todayOrders,
    aumSub: [
      { label: 'Mutual Funds', value: `₹${(mfAum / 100000).toFixed(2)} L`, change: '', up: true },
      { label: 'Specialised Funds', value: `₹${(sifAum / 100000).toFixed(2)} L`, change: '', up: true },
    ],
    pendingSub: [
      { label: 'KYC Pending', value: kycPending },
      { label: 'Bank Link Pending', value: bankPending },
      { label: 'Txn Failed', value: failedOrders },
      { label: 'SIP Failed', value: failedSips },
      { label: 'Life Events', value: allLifeEventReminders.length },
    ],
    leadSub: [
      { label: 'New Leads', value: newLeads, color: 'bg-blue-400' },
      { label: 'In Progress', value: inProgressLeads, color: 'bg-amber-400' },
      { label: 'Converted', value: convertedLeads, color: 'bg-green-400' },
    ],
    donutData: [
      { name: 'Successful', value: todaySuccessful, color: '#22c55e' },
      { name: 'Pending', value: todayPending, color: '#eab308' },
      { name: 'Failed', value: todayFailed, color: '#ef4444' },
    ],
    recentActivity,
    lifeEventReminders: allLifeEventReminders.slice(0, 5),
    onboarding,
  };
}
