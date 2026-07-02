-- V73: nominee identity documents (investor-self uploads).
--
-- Extends the existing in-DB document store (investor_documents) so each nominee
-- can carry one identity document, uploaded by the INVESTOR from their own
-- verification flow:
--   * nominee_id      — links a document to one investor_nominees row (null for
--                       the existing investor-level KYC/PAN/ADDRESS/SIGNATURE docs)
--   * uploaded_by FK  — dropped: the uploader may now be an investor account, not
--                       only a distributor
--   * distributor_id  — relaxed to nullable: an investor whose distributor link is
--                       still pending (V63) can already upload nominee documents
--   * document_type   — check widened with NOMINEE_ID
--   * uniqueness      — the old one-doc-per-(investor, type) constraint is split
--                       into two partial unique indexes: investor-level docs keep
--                       the one-per-type guarantee, and each nominee gets at most
--                       one document per type.

alter table investor_documents add column if not exists nominee_id uuid;

alter table investor_documents drop constraint if exists fk_investor_documents_uploaded_by;

alter table investor_documents alter column distributor_id drop not null;

alter table investor_documents drop constraint if exists chk_investor_documents_document_type;
alter table investor_documents add constraint chk_investor_documents_document_type
    check (document_type in ('KYC', 'PAN', 'ADDRESS', 'SIGNATURE', 'NOMINEE_ID'));

alter table investor_documents drop constraint if exists uq_investor_documents_investor_type;
create unique index if not exists uq_investor_documents_investor_type
    on investor_documents (investor_id, document_type) where nominee_id is null;
create unique index if not exists uq_investor_documents_nominee_type
    on investor_documents (investor_id, nominee_id, document_type) where nominee_id is not null;

create index if not exists idx_investor_documents_nominee on investor_documents (nominee_id);
