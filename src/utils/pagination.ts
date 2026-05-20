export type PageMeta = {
  totalPages: number;
  totalElements: number;
};

export const getPageContent = <T = any>(payload: any): T[] => {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.content)) return payload.content;
  if (Array.isArray(payload?.data?.content)) return payload.data.content;
  if (Array.isArray(payload?.data)) return payload.data;
  return [];
};

export const getPageMeta = (payload: any, fallbackCount: number): PageMeta => {
  const source = payload?.content ? payload : payload?.data?.content ? payload.data : payload;
  const totalElements = Number(source?.totalElements ?? fallbackCount);
  const totalPages = Number(source?.totalPages ?? Math.max(Math.ceil(totalElements / Math.max(source?.size || fallbackCount || 1, 1)), 1));

  return {
    totalElements,
    totalPages: Math.max(totalPages, 1),
  };
};
