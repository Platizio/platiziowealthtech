package com.platizio.wealthtech.integration;

import com.platizio.wealthtech.domain.Investor;
import com.platizio.wealthtech.domain.InvestorBankAccount;
import com.platizio.wealthtech.domain.ProductScheme;
import com.platizio.wealthtech.domain.TransactionOrder;
import java.util.List;

public interface CybrillaClient {

    /**
     * B-68: the result of a paginated scheme fetch. `complete=true` means the
     * client traversed every page successfully and the `schemes` list is the
     * canonical current catalogue; `complete=false` means the fetch terminated
     * before consuming the full catalogue (e.g. a per-call page cap was hit).
     *
     * Callers that maintain "active set vs catalogue" state — like
     * ProductService.refreshFromCybrilla — MUST skip deactivation of
     * locally-known schemes whenever `complete=false`, otherwise a partial
     * fetch can incorrectly mark legitimate schemes inactive.
     *
     * The `incompleteReason` is a short machine-readable tag (e.g.
     * "max_pages_reached") suitable for structured logging.
     */
    record SchemeFetchResult(List<ProductScheme> schemes, boolean complete, String incompleteReason) {
        public static SchemeFetchResult complete(List<ProductScheme> schemes) {
            return new SchemeFetchResult(schemes, true, null);
        }
        public static SchemeFetchResult partial(List<ProductScheme> schemes, String reason) {
            return new SchemeFetchResult(schemes, false, reason);
        }
    }

    String createInvestorProfile(Investor investor);
    void captureBankAccount(Investor investor, InvestorBankAccount bankAccount);
    SchemeFetchResult fetchProductSchemes();
    String createOrder(TransactionOrder order, Investor investor);
    String generateInvestorActionUrl(TransactionOrder order);
    String createRedemption(TransactionOrder order);
    void cancelOrder(TransactionOrder order);
}
