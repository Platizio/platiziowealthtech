package com.platizio.wealthtech.controller;

import com.platizio.wealthtech.service.InvestorActionService;
import com.platizio.wealthtech.service.InvestorActionService.InvestorActionPage;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvestorActionController {

    private static final Locale INDIA = new Locale.Builder().setLanguage("en").setRegion("IN").build();

    private final InvestorActionService investorActionService;

    public InvestorActionController(InvestorActionService investorActionService) {
        this.investorActionService = investorActionService;
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
        }
    }

    private String render(InvestorActionPage page) {
        String button = page.confirmationAllowed()
                ? """
                    <form method="post" action="/investor-actions/%s/confirm">
                      <button type="submit">Confirm Purchase</button>
                    </form>
                    """.formatted(escape(page.token()))
                : "";

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
                    }
                    button {
                      border: 0;
                      border-radius: 6px;
                      background: var(--ok);
                      color: white;
                      font-size: 16px;
                      font-weight: 700;
                      padding: 14px 22px;
                      cursor: pointer;
                    }
                    button:hover { background: #0b5f59; }
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
                      <div class="status">%s</div>
                    </div>
                    <section class="panel">
                      <div class="notice">%s</div>
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
                            <div><dt>Order ID</dt><dd>%s</dd></div>
                          </dl>
                        </div>
                      </div>
                      <div class="actions">%s</div>
                    </section>
                  </main>
                </body>
                </html>
                """.formatted(
                escape(label(page.orderStatus().name())),
                escape(page.message()),
                escape(page.investorName()),
                escape(defaultText(page.investorEmail())),
                escape(page.schemeName()),
                escape(defaultText(page.amcName())),
                escape(money(page.amount())),
                escape(defaultText(page.units() == null ? null : page.units().toPlainString())),
                escape(label(page.transactionType())),
                escape(defaultText(page.paymentMode())),
                escape(defaultText(page.mandateMode())),
                escape(page.orderId().toString()),
                button
        );
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

    private String label(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace('_', ' ');
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
