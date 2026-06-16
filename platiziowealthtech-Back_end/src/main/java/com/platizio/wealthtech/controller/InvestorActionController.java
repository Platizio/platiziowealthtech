package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.integration.CybrillaApiException;
import com.platizio.wealthtech.integration.CybrillaUnavailableException;
import com.platizio.wealthtech.domain.OrderStatus;
import com.platizio.wealthtech.service.InvestorActionService;
import com.platizio.wealthtech.service.InvestorActionService.InvestorActionPage;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvestorActionController {

    private static final Locale INDIA = new Locale.Builder().setLanguage("en").setRegion("IN").build();

    private final InvestorActionService investorActionService;
    private final String frontendOrigin;

    public InvestorActionController(
            InvestorActionService investorActionService,
            @Value("${app.frontend.origin:http://localhost:3000}") String frontendOrigin) {
        this.investorActionService = investorActionService;
        this.frontendOrigin = frontendOrigin;
    }

    @GetMapping(
            value = {"/investor-actions/{token}", "/investor-action/{token}"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> showActionPage(@PathVariable String token) {
        try {
            return ResponseEntity.ok(render(investorActionService.getPage(token)));
        } catch (EntityNotFoundException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(renderError(ex.getMessage()));
        }
    }

    @PostMapping(
            value = {"/investor-actions/{token}/confirm", "/investor-action/{token}/confirm"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> confirmPurchase(@PathVariable String token) {
        try {
            return ResponseEntity.ok(render(investorActionService.confirmPurchase(token)));
        } catch (EntityNotFoundException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(renderError(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(renderError(ex.getMessage()));
        } catch (CybrillaUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(renderError(ex.getMessage()));
        } catch (CybrillaApiException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(renderError(ex.getMessage()));
        }
    }

    @GetMapping(
            value = {"/investor-actions/{token}/payment-complete", "/investor-action/{token}/payment-complete"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> paymentCompleteGet(
            @PathVariable String token,
            @RequestParam(required = false) String paymentId,
            @RequestParam(required = false) String status
    ) {
        return paymentComplete(token, paymentId, status);
    }

    @PostMapping(
            value = {"/investor-actions/{token}/payment-complete", "/investor-action/{token}/payment-complete"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> paymentCompletePost(
            @PathVariable String token,
            @RequestParam(required = false) String paymentId,
            @RequestParam(required = false) String status
    ) {
        return paymentComplete(token, paymentId, status);
    }

    private ResponseEntity<String> paymentComplete(String token, String paymentId, String status) {
        try {
            return ResponseEntity.ok(render(investorActionService.handlePaymentPostback(token, paymentId, status)));
        } catch (EntityNotFoundException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(renderError(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(renderError(ex.getMessage()));
        } catch (CybrillaUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(renderError(ex.getMessage()));
        } catch (CybrillaApiException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(renderError(ex.getMessage()));
        }
    }

    @PostMapping(
            value = {"/investor-actions/{token}/sandbox/simulate-payment", "/investor-action/{token}/sandbox/simulate-payment"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> simulateSandboxPayment(@PathVariable String token) {
        try {
            return ResponseEntity.ok(render(investorActionService.simulateSandboxPayment(token)));
        } catch (EntityNotFoundException | IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(renderError(ex.getMessage()));
        } catch (CybrillaUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(renderError(ex.getMessage()));
        } catch (CybrillaApiException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(renderError(ex.getMessage()));
        }
    }

    @PostMapping(
            value = {"/investor-actions/{token}/prepare-investor", "/investor-action/{token}/prepare-investor"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> prepareInvestor(@PathVariable String token) {
        try {
            return ResponseEntity.ok(render(investorActionService.prepareInvestorForRetry(token)));
        } catch (EntityNotFoundException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(renderError(ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(renderError(ex.getMessage()));
        } catch (CybrillaUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(renderError(ex.getMessage()));
        } catch (CybrillaApiException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(renderError(ex.getMessage()));
        }
    }

    @PostMapping(
            value = {"/investor-actions/{token}/sandbox/simulate-mandate", "/investor-action/{token}/sandbox/simulate-mandate"},
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> simulateSandboxMandate(@PathVariable String token) {
        try {
            return ResponseEntity.ok(render(investorActionService.simulateSandboxMandateApproval(token)));
        } catch (EntityNotFoundException | IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(renderError(ex.getMessage()));
        } catch (CybrillaUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(renderError(ex.getMessage()));
        } catch (CybrillaApiException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(renderError(ex.getMessage()));
        }
    }

    private String render(InvestorActionPage page) {
        String actions = renderActionButtons(page);
        String statusClass = page.orderStatus() == OrderStatus.FAILED ? "status-failed" : "status";

        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Investor Action - Platizio</title>
                  <style>
                    :root {
                      color-scheme: light;
                      --ink: #102027;
                      --muted: #52636d;
                      --line: #d7e0e5;
                      --panel: #ffffff;
                      --brand: #155e75;
                      --brand-dark: #0e4f63;
                      --soft: #eef7f9;
                      --ok: #0f766e;
                    }
                    * { box-sizing: border-box; }
                    body {
                      margin: 0;
                      min-height: 100vh;
                      font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                      color: var(--ink);
                      background: #f5f8fa;
                    }
                    main {
                      width: min(860px, calc(100vw - 32px));
                      margin: 0 auto;
                      padding: 48px 0;
                    }
                    .header {
                      display: flex;
                      justify-content: space-between;
                      gap: 24px;
                      align-items: flex-start;
                      margin-bottom: 24px;
                    }
                    .brand {
                      font-size: 14px;
                      font-weight: 700;
                      color: var(--brand);
                      text-transform: uppercase;
                      letter-spacing: .04em;
                    }
                    h1 {
                      margin: 8px 0 8px;
                      font-size: 32px;
                      line-height: 1.15;
                    }
                    .status {
                      padding: 10px 14px;
                      border-radius: 6px;
                      background: var(--soft);
                      color: var(--brand-dark);
                      font-size: 14px;
                      font-weight: 700;
                      white-space: nowrap;
                    }
                    .status-failed {
                      padding: 10px 14px;
                      border-radius: 6px;
                      background: #fef2f2;
                      color: #991b1b;
                      font-size: 14px;
                      font-weight: 700;
                      white-space: nowrap;
                    }
                    .action-hint {
                      padding: 0 24px 8px;
                      color: var(--muted);
                      font-size: 14px;
                      line-height: 1.5;
                    }
                    .btn-secondary {
                      background: #475569;
                    }
                    .btn-secondary:hover { background: #334155; }
                    .btn-outline {
                      background: transparent;
                      color: var(--brand-dark);
                      border: 1px solid var(--line);
                    }
                    .btn-outline:hover { background: #f1f5f9; }
                    .panel {
                      background: var(--panel);
                      border: 1px solid var(--line);
                      border-radius: 8px;
                      box-shadow: 0 14px 40px rgba(15, 52, 65, .08);
                      overflow: hidden;
                    }
                    .notice {
                      padding: 18px 24px;
                      border-bottom: 1px solid var(--line);
                      color: var(--brand-dark);
                      background: var(--soft);
                      font-weight: 600;
                    }
                    .content {
                      display: grid;
                      grid-template-columns: 1fr 1fr;
                      gap: 0;
                    }
                    .section {
                      padding: 24px;
                      border-bottom: 1px solid var(--line);
                    }
                    .section:nth-child(odd) {
                      border-right: 1px solid var(--line);
                    }
                    h2 {
                      margin: 0 0 16px;
                      font-size: 15px;
                      text-transform: uppercase;
                      color: var(--muted);
                      letter-spacing: .04em;
                    }
                    dl {
                      margin: 0;
                      display: grid;
                      gap: 14px;
                    }
                    dt {
                      color: var(--muted);
                      font-size: 13px;
                      margin-bottom: 3px;
                    }
                    dd {
                      margin: 0;
                      font-size: 16px;
                      font-weight: 650;
                      overflow-wrap: anywhere;
                    }
                    .actions {
                      padding: 24px;
                      display: flex;
                      justify-content: flex-end;
                      align-items: center;
                    }
                    .actions { gap: 12px; flex-wrap: wrap; }
                    .pay-link, button {
                      border: 0;
                      border-radius: 6px;
                      background: var(--ok);
                      color: white;
                      font-size: 16px;
                      font-weight: 700;
                      padding: 14px 22px;
                      cursor: pointer;
                      text-decoration: none;
                      display: inline-block;
                    }
                    button.simulate-btn { background: #0f4c81; }
                    button:hover { background: #0b5f59; }
                    button.simulate-btn:hover { background: #0c3d68; }
                    .debug-panel {
                      margin: 0 24px 24px;
                      padding: 16px;
                      border: 1px dashed #94a3b8;
                      border-radius: 8px;
                      background: #f8fafc;
                      font-size: 12px;
                      color: #334155;
                    }
                    .debug-panel summary {
                      cursor: pointer;
                      font-weight: 700;
                      color: #0f4c81;
                      margin-bottom: 8px;
                    }
                    .debug-panel pre {
                      margin: 8px 0 0;
                      white-space: pre-wrap;
                      word-break: break-word;
                      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                      font-size: 11px;
                      max-height: 280px;
                      overflow: auto;
                    }
                    @media (max-width: 720px) {
                      main { padding: 28px 0; }
                      .header { display: block; }
                      .status { display: inline-block; margin-top: 12px; }
                      .content { grid-template-columns: 1fr; }
                      .section:nth-child(odd) { border-right: 0; }
                      h1 { font-size: 26px; }
                      .actions { justify-content: stretch; }
                      button { width: 100%%; }
                    }
                  </style>
                </head>
                <body>
                  <main>
                    <div class="header">
                      <div>
                        <div class="brand">Platizio Investor Action</div>
                        <h1>Review Purchase</h1>
                      </div>
                      <div class="%s">%s</div>
                    </div>
                    <section class="panel">
                      <div class="notice">%s</div>
                      %s
                      <div class="content">
                        <div class="section">
                          <h2>Investor</h2>
                          <dl>
                            <div><dt>Name</dt><dd>%s</dd></div>
                            <div><dt>Email</dt><dd>%s</dd></div>
                          </dl>
                        </div>
                        <div class="section">
                          <h2>Scheme</h2>
                          <dl>
                            <div><dt>Name</dt><dd>%s</dd></div>
                            <div><dt>AMC</dt><dd>%s</dd></div>
                          </dl>
                        </div>
                        <div class="section">
                          <h2>Purchase</h2>
                          <dl>
                            <div><dt>Amount</dt><dd>%s</dd></div>
                            <div><dt>Units</dt><dd>%s</dd></div>
                            <div><dt>Type</dt><dd>%s</dd></div>
                          </dl>
                        </div>
                        <div class="section">
                          <h2>Payment</h2>
                          <dl>
                            <div><dt>Mode</dt><dd>%s</dd></div>
                            <div><dt>Mandate</dt><dd>%s</dd></div>
                            <div><dt>Mandate Status</dt><dd>%s</dd></div>
                            <div><dt>Order ID</dt><dd>%s</dd></div>
                          </dl>
                        </div>
                      </div>
                      <div class="actions">%s</div>
                      <details class="debug-panel">
                        <summary>Technical details (support)</summary>
                        <pre id="lumpsum-debug-json"></pre>
                      </details>
                    </section>
                  </main>
                  <script>
                    (function () {
                      const LUMPSUM_PAYMENT_DEBUG = %s;
                      const pre = document.getElementById('lumpsum-debug-json');
                      if (pre) {
                        pre.textContent = JSON.stringify(LUMPSUM_PAYMENT_DEBUG, null, 2);
                      }
                    })();
                  </script>
                </body>
                </html>
                """.formatted(
                statusClass,
                escape(label(page.orderStatus().name())),
                escape(page.message()),
                renderActionHint(page),
                escape(page.investorName()),
                escape(defaultText(page.investorEmail())),
                escape(page.schemeName()),
                escape(defaultText(page.amcName())),
                escape(money(page.amount())),
                escape(defaultText(page.units() == null ? null : page.units().toPlainString())),
                escape(label(page.transactionType())),
                escape(defaultText(page.paymentMode())),
                escape(mandateLabel(page)),
                escape(defaultText(page.mandateStatus())),
                escape(page.orderId().toString()),
                actions,
                page.debugJson() == null || page.debugJson().isBlank() ? "{}" : page.debugJson()
        );
    }

    private String renderActionHint(InvestorActionPage page) {
        if (page.orderStatus() == OrderStatus.FAILED) {
            return """
                    <p class="action-hint">This Finprim purchase cannot be confirmed or paid once review has failed. \
                    Repair the investor profile, then create a <strong>new</strong> order from the distributor Ledger.</p>
                    """;
        }
        if (page.confirmationAllowed()) {
            return """
                    <p class="action-hint">Step 1 of 3: Confirm your purchase. \
                    Platizio will collect consent and open secure payment on Fintech Primitives.</p>
                    """;
        }
        if (page.orderStatus() == OrderStatus.PAYMENT_PENDING) {
            if (isUpiPaymentUrl(page.paymentRedirectUrl())) {
                return """
                        <p class="action-hint">Step 2 of 3: Open your UPI app and complete the payment \
                        (or use sandbox simulate below).</p>
                        """;
            }
            return """
                    <p class="action-hint">Step 2 of 3: Complete payment on the secure Fintech Primitives page \
                    (or use sandbox simulate below).</p>
                    """;
        }
        return "";
    }

    private String renderActionButtons(InvestorActionPage page) {
        StringBuilder actions = new StringBuilder();
        if (page.orderStatus() == OrderStatus.FAILED) {
            actions.append("""
                    <form method="post" action="/investor-actions/%s/prepare-investor">
                      <button type="submit" class="btn-secondary">Repair Investor Profile</button>
                    </form>
                    <a class="pay-link btn-outline" href="%s/distributor/ledger">Place New Order (Ledger)</a>
                    """.formatted(escape(page.token()), escape(frontendOrigin)));
            return actions.toString();
        }
        if (page.confirmationAllowed()) {
            actions.append("""
                    <form method="post" action="/investor-actions/%s/confirm">
                      <button type="submit">Confirm Purchase &amp; Continue</button>
                    </form>
                    """.formatted(escape(page.token())));
        }
        if (page.paymentRedirectUrl() != null && !page.paymentRedirectUrl().isBlank()) {
            actions.append("""
                    <a class="pay-link" href="%s">%s</a>
                    """.formatted(
                    escape(page.paymentRedirectUrl()),
                    isUpiPaymentUrl(page.paymentRedirectUrl()) ? "Open UPI App" : "Continue to Secure Payment"
            ));
        }
        if (page.sandboxMandateSimulationAllowed()) {
            actions.append("""
                    <form method="post" action="/investor-actions/%s/sandbox/simulate-mandate">
                      <button type="submit" class="simulate-btn">Simulate Mandate Approval (Sandbox)</button>
                    </form>
                    """.formatted(escape(page.token())));
        }
        if (page.sandboxPaymentSimulationAllowed()) {
            actions.append("""
                    <form method="post" action="/investor-actions/%s/sandbox/simulate-payment">
                      <button type="submit" class="simulate-btn">Simulate Payment Success (Sandbox)</button>
                    </form>
                    """.formatted(escape(page.token())));
        }
        return actions.toString();
    }

    private String renderError(String message) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Investor Action - Link Not Found</title>
                  <style>
                    body { margin: 0; min-height: 100vh; display: grid; place-items: center; font-family: Inter, ui-sans-serif, system-ui, sans-serif; background: #f5f8fa; color: #102027; }
                    section { width: min(560px, calc(100vw - 32px)); background: #fff; border: 1px solid #d7e0e5; border-radius: 8px; padding: 28px; box-shadow: 0 14px 40px rgba(15, 52, 65, .08); }
                    h1 { margin: 0 0 10px; font-size: 26px; }
                    p { margin: 0; color: #52636d; line-height: 1.5; }
                  </style>
                </head>
                <body><section><h1>Link not found</h1><p>%s</p></section></body>
                </html>
                """.formatted(escape(message == null ? "This investor action link is invalid or expired." : message));
    }

    private String money(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        return NumberFormat.getCurrencyInstance(INDIA).format(amount);
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String mandateLabel(InvestorActionPage page) {
        if (page.externalMandateId() != null && page.externalMandateId() > 0) {
            String mode = page.mandateMode() == null || page.mandateMode().isBlank() ? "MANDATE" : page.mandateMode();
            return mode + " #" + page.externalMandateId();
        }
        return defaultText(page.mandateMode());
    }

    private String label(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace('_', ' ');
    }

    private boolean isUpiPaymentUrl(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("upi://");
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
