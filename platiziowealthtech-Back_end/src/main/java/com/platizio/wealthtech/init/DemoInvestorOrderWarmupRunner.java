package com.platizio.wealthtech.init;

import com.platizio.wealthtech.service.InvestorService;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Pre-links Anita Verma's Finprim profile/MFIA on local boot so the first Ledger lumpsum does not
 * block the HTTP thread for the full provider onboarding round-trip.
 */
@Component
@Profile("local")
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoInvestorOrderWarmupRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(DemoInvestorOrderWarmupRunner.class);
    private static final UUID ANITA_DEMO_INVESTOR_ID = UUID.fromString("9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03");

    private final InvestorService investorService;

    public DemoInvestorOrderWarmupRunner(InvestorService investorService) {
        this.investorService = investorService;
    }

    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(8_000L);
                investorService.ensureMfInvestmentAccount(ANITA_DEMO_INVESTOR_ID);
                logger.info(
                        "demo_investor_warmup status='completed' investor_id='{}'",
                        ANITA_DEMO_INVESTOR_ID);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                logger.info("demo_investor_warmup status='interrupted' investor_id='{}'", ANITA_DEMO_INVESTOR_ID);
            } catch (RuntimeException ex) {
                logger.warn(
                        "demo_investor_warmup status='skipped' investor_id='{}' reason='{}'",
                        ANITA_DEMO_INVESTOR_ID,
                        ex.getMessage());
            }
        });
    }
}
