package com.platizio.wealthtech.service;

import com.platizio.wealthtech.domain.Investor;
import java.util.UUID;

public interface BankVerificationStarter {
    Investor startBankVerificationAfterKycCompletion(Investor investor, UUID actorId, String trigger);
}
