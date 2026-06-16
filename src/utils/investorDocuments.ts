import { apiClient, apiFetch } from '../config/api';

export type InvestorDocumentKey = 'pan' | 'address' | 'signature';

export type SavedInvestorDocument = {
  id: string;
  documentType: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedAt?: string;
};

const DOCUMENT_KEY_BY_TYPE: Record<string, InvestorDocumentKey> = {
  PAN: 'pan',
  ADDRESS: 'address',
  SIGNATURE: 'signature',
};

export const documentKeyFromType = (documentType: string): InvestorDocumentKey | null => {
  const normalized = String(documentType || '').trim().toUpperCase();
  return DOCUMENT_KEY_BY_TYPE[normalized] || null;
};

export const documentTypeFromKey = (key: InvestorDocumentKey): string => key.toUpperCase();

export const listInvestorDocuments = async (investorId: string): Promise<SavedInvestorDocument[]> => {
  const response = await apiFetch(`/investors/${investorId}/documents`);
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(typeof body === 'string' ? body : body?.message || `Document list failed with HTTP ${response.status}`);
  }
  if (!Array.isArray(body)) return [];
  return body.map((item: any) => ({
    id: String(item?.id || ''),
    documentType: String(item?.documentType || ''),
    fileName: String(item?.fileName || ''),
    contentType: String(item?.contentType || ''),
    sizeBytes: Number(item?.sizeBytes || 0),
    uploadedAt: item?.uploadedAt ? String(item.uploadedAt) : undefined,
  }));
};

export const uploadInvestorDocument = async (
  investorId: string,
  key: InvestorDocumentKey,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<SavedInvestorDocument> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('documentType', documentTypeFromKey(key));

  const response = await apiClient.put(`/investors/${investorId}/documents`, formData, {
    onUploadProgress: event => {
      if (!onProgress) return;
      const total = event.total || file.size || event.loaded || 1;
      onProgress(Math.min(99, Math.round((event.loaded * 100) / total)));
    },
  });

  onProgress?.(100);
  const document = response.data?.document || response.data;
  return {
    id: String(document?.id || ''),
    documentType: String(document?.documentType || documentTypeFromKey(key)),
    fileName: String(document?.fileName || file.name),
    contentType: String(document?.contentType || file.type || ''),
    sizeBytes: Number(document?.sizeBytes || file.size),
    uploadedAt: document?.uploadedAt ? String(document.uploadedAt) : undefined,
  };
};

export const savedDocumentsByKey = (documents: SavedInvestorDocument[]) => {
  const map: Partial<Record<InvestorDocumentKey, SavedInvestorDocument>> = {};
  documents.forEach(doc => {
    const key = documentKeyFromType(doc.documentType);
    if (key) map[key] = doc;
  });
  return map;
};
