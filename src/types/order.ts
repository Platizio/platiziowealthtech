export type OrderStatus =
  | 'DRAFT'
  | 'PENDING_INVESTOR_ACTION'
  | 'PAYMENT_PENDING'
  | 'SUBMITTED'
  | 'PROCESSING'
  | 'SUCCESSFUL'
  | 'FAILED'
  | 'CANCELLED'
  | 'RETRY_AVAILABLE'
  | string;

export interface TransactionOrder {
  id: string;
  investorId?: string;
  distributorId?: string;
  productSchemeId?: string;
  productSchemeName?: string;
  productSchemeExternalCode?: string;
  productSchemeIsin?: string;
  productSchemeAmcName?: string;
  transactionType?: string;
  amount?: number;
  units?: number;
  allotmentNav?: number;
  allotmentDate?: string;
  folioNumber?: string;
  stampDuty?: number;
  netInvested?: number;
  orderStatus?: OrderStatus;
  externalOrderId?: string;
  investorActionToken?: string;
  investorActionUrl?: string;
  paymentMode?: string;
  mandateMode?: string;
  sipFrequency?: string;
  sipStartDate?: string;
  sipInstalments?: number;
  failureReason?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface TransactionListItem {
  id: string;
  investor: string;
  investorId?: string;
  fund: string;
  type: string;
  amount: string;
  rawAmount: number;
  status: string;
  rawOrderStatus?: string;
  date: string;
  rawCreatedAt: string;
  pan: string;
  mandate: string;
  investorActionUrl?: string;
  failureReason?: string;
  productSchemeId?: string;
  schemeKnown?: boolean;
  units?: number;
  allotmentNav?: number;
  allotmentDate?: string;
  folioNumber?: string;
  stampDuty?: number;
  netInvested?: number;
}
