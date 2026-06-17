/** Cybrilla sandbox demo scenarios — official simulator patterns only. */

export type SandboxDemoPath = 'fast' | 'full-kyc' | 'negative';

export type SandboxDemoScenario = {
  id: string;
  label: string;
  description: string;
  path: SandboxDemoPath;
  priority: 'high' | 'medium' | 'low';
  firstName: string;
  lastName: string;
  pan: string;
  dob: string;
  mobile: string;
  email: string;
  addressLine1: string;
  city: string;
  state: string;
  postalCode: string;
  bankAccount: string;
  ifsc: string;
  expectedReadiness: string;
  expectedFlow: string;
  orderHint?: string;
};

const base = {
  dob: '1990-05-15',
  mobile: '9876543210',
  addressLine1: '221B Baker Street',
  city: 'Mumbai',
  state: 'Maharashtra',
  postalCode: '400001',
  ifsc: 'HDFC0001234',
  bankAccount: '1234567891193',
};

export const SANDBOX_DEMO_SCENARIOS: SandboxDemoScenario[] = [
  {
    id: 'DEMO-B-FULL-KYC',
    label: '★ Full demo — kyc_unavailable',
    description: 'Primary Platizio demo: pre-verification → fresh KYC → Digilocker → eSign → bank → order.',
    path: 'full-kyc',
    priority: 'high',
    firstName: 'Priya',
    lastName: 'Sharma',
    pan: 'BBBPB3753B',
    email: 'demo.kyc@platizio.test',
    mobile: '9876543211',
    expectedReadiness: 'kyc_unavailable',
    expectedFlow: 'Pre-verification → KYC request → Aadhaar → eSign → Bank → Order ₹5000',
    orderHint: 'Use ₹5000 (ends in 0) for success',
    ...base,
  },
  {
    id: 'DEMO-A-FAST',
    label: '★ Fast transaction — KYC ready',
    description: 'Skip fresh KYC; go straight to bank verification and purchase.',
    path: 'fast',
    priority: 'high',
    firstName: 'Rajesh',
    lastName: 'Kumar',
    pan: 'AAAPA3751A',
    email: 'demo.fast@platizio.test',
    mobile: '9876543210',
    expectedReadiness: 'verified',
    expectedFlow: 'Pre-verification verified → Bank → Order ₹5000',
    orderHint: 'Use ₹5000 (ends in 0) for success',
    ...base,
  },
  {
    id: 'KYC-READY-ALT',
    label: 'KYC ready (POA doc PAN)',
    description: 'Official POA example PAN GYAPS3751D — readiness verified.',
    path: 'fast',
    priority: 'medium',
    firstName: 'Amit',
    lastName: 'Patel',
    pan: 'GYAPS3751D',
    email: 'demo.ready1@platizio.test',
    mobile: '9876543212',
    expectedReadiness: 'verified',
    expectedFlow: 'Same as fast path',
    ...base,
  },
  {
    id: 'KYC-UNAVAIL-ALT',
    label: 'kyc_unavailable (alt PAN)',
    description: 'Another XXXPX3753X pattern — same full KYC path as primary demo.',
    path: 'full-kyc',
    priority: 'medium',
    firstName: 'Anita',
    lastName: 'Desai',
    pan: 'CCCPC3753C',
    email: 'demo.unavail1@platizio.test',
    mobile: '9876543214',
    expectedReadiness: 'kyc_unavailable',
    expectedFlow: 'Full KYC path',
    ...base,
  },
  {
    id: 'PAN-INVALID',
    label: 'Invalid PAN (XXXPINNNNX)',
    description: 'Pre-verification should fail pan.code = invalid.',
    path: 'negative',
    priority: 'low',
    firstName: 'Rohan',
    lastName: 'Gupta',
    pan: 'DDDPI1234D',
    email: 'demo.invalid@platizio.test',
    mobile: '9876543217',
    expectedReadiness: '—',
    expectedFlow: 'Pre-verification pan invalid',
    ...base,
  },
  {
    id: 'PAN-AADHAAR-NOT-LINKED',
    label: 'Aadhaar not linked (XXXPANNNNX)',
    description: 'Pre-verification pan.code = aadhaar_not_linked.',
    path: 'negative',
    priority: 'low',
    firstName: 'Kavita',
    lastName: 'Joshi',
    pan: 'EEEAP1234E',
    email: 'demo.nolink@platizio.test',
    mobile: '9876543218',
    expectedReadiness: '—',
    expectedFlow: 'Pre-verification aadhaar_not_linked',
    ...base,
  },
  {
    id: 'NAME-MISMATCH',
    label: 'Name mismatch',
    description: 'Use valid PAN + name Lord Voldemort → name mismatch.',
    path: 'negative',
    priority: 'low',
    firstName: 'Lord',
    lastName: 'Voldemort',
    pan: 'GYAPS3751D',
    email: 'demo.name@platizio.test',
    mobile: '9876543219',
    expectedReadiness: '—',
    expectedFlow: 'Pre-verification name mismatch',
    ...base,
  },
  {
    id: 'DOB-MISMATCH',
    label: 'DOB mismatch',
    description: 'Valid PAN + DOB 2000-01-01 → dob mismatch.',
    path: 'negative',
    priority: 'low',
    firstName: 'Suresh',
    lastName: 'Nair',
    pan: 'GYAPS3751D',
    dob: '2000-01-01',
    email: 'demo.dob@platizio.test',
    mobile: '9876543220',
    expectedReadiness: '—',
    expectedFlow: 'Pre-verification dob mismatch',
    ...base,
  },
  {
    id: 'BAV-FAIL',
    label: 'Bank verification fail',
    description: 'KYC-ready investor + account ending 1515 → low confidence fail.',
    path: 'negative',
    priority: 'low',
    firstName: 'Divya',
    lastName: 'Rao',
    pan: 'AAAPA3751A',
    email: 'demo.bav1515@platizio.test',
    mobile: '9876543223',
    bankAccount: '1234567891515',
    expectedReadiness: 'verified',
    expectedFlow: 'Bank BAV failed (1515)',
    ...base,
  },
];

export const SANDBOX_BANK_HINTS = [
  { suffix: '1193', outcome: 'Pass — use for demo' },
  { suffix: '1285', outcome: 'Verified, high confidence' },
  { suffix: '1515', outcome: 'Failed, low confidence' },
  { suffix: '1600', outcome: 'Failed, zero confidence' },
  { suffix: '3157', outcome: 'Digital verification failure' },
] as const;

export const SANDBOX_ORDER_HINTS = [
  { amount: '₹5000', outcome: 'Success (ends in 0)' },
  { amount: '₹5001', outcome: 'Failure (ends in 1)' },
] as const;
