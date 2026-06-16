# Sandbox demo scenarios — copy-paste ready

Use with **Platizio → Investor Onboarding**. All PANs use Cybrilla simulator rules (4th character = `P`).

**Shared defaults for all rows:**
- Mobile: `9876543210`
- Email: `demo+{scenario}@platizio.test`
- DOB: `1990-05-15` (avoid `2000-01-01` unless testing DOB mismatch)
- Address: `221B Baker Street`, Mumbai, Maharashtra, `400001`
- IFSC: `HDFC0001234`
- Gender: Male | Occupation: Salaried | Income: ₹1–5 L

---

## ★ Demo Path A — Fast transaction (KYC already done)

| Field | Value |
|-------|-------|
| First / Last name | Rajesh / Kumar |
| PAN | **AAAPA3751A** |
| Bank account | **1234567891193** (ends 1193) |
| Order amount | **₹5000** (ends in 0) |

**Expected:** readiness `verified` → skip fresh KYC → bank verified → order succeeds.

---

## ★ Demo Path B — Full KYC (kyc_unavailable) — **recommended for demo**

| Field | Value |
|-------|-------|
| First / Last name | Priya / Sharma |
| PAN | **BBBPB3753B** |
| Bank account | **1234567891193** |
| Order amount | **₹5000** |

**Expected flow:**
1. Pre-verification → `readiness.code = kyc_unavailable`
2. Create KYC request (`kycr_...`)
3. Start Aadhaar → Digilocker redirect (sandbox may allow simulate)
4. eSign redirect
5. KYC status → COMPLETED
6. Bank → order

**Sandbox shortcut:** After KYC request created, **Simulate success** button (Investors → Compliance tab) if Digilocker not available live.

---

## kyc_unavailable variants (same outcome)

| Label | PAN | Notes |
|-------|-----|-------|
| Primary | BBBPB3753B | Standard |
| Alt 1 | CCCPC3753C | Same pattern XXXPX3753X |
| Alt 2 | DDDPD3753D | Same pattern |
| Alt 3 | EEEPX3753E | Same pattern |

---

## KYC-ready variants (skip fresh KYC)

| Label | PAN |
|-------|-----|
| Primary | AAAPA3751A |
| Alt 1 | GYAPS3751D | Official POA doc example |
| Alt 2 | FFFPF3751F |

---

## PAN validation failures

| Scenario | PAN | Name | DOB | Expected `pan.code` |
|----------|-----|------|-----|---------------------|
| Invalid PAN | **DDDPI1234D** | Any normal | 1990-05-15 | `invalid` |
| Aadhaar not linked | **EEEAP1234E** | Any normal | 1990-05-15 | `aadhaar_not_linked` (5th char `A`) |
| Name mismatch | GYAPS3751D | **Lord Voldemort** | 1990-05-15 | name `mismatch` |
| DOB mismatch | GYAPS3751D | Rajesh Kumar | **2000-01-01** | dob `mismatch` |

---

## Bank verification (FP sandbox — last 4 digits)

| Scenario | Account number | Expected |
|----------|----------------|----------|
| **Pass (demo)** | `123456789**1193**` | verified / very_high confidence |
| High confidence | `123456789**1285**` | verified |
| Low confidence | `123456789**1515**` | failed / low confidence |
| Zero confidence | `123456789**1600**` | failed |
| Digital failure | `12345678**3157**` | failed `digital_verification_failure` |
| Expiry (15 min) | any other pattern | pending → failed `expiry` |

---

## Order simulation

| Scenario | Amount | Expected |
|----------|--------|----------|
| **Success** | ₹500**0**, ₹100**0** | RTA success |
| **Failure** | ₹500**1**, ₹100**1** | RTA failure |

**Schemes:** ABSL and Ipru AMC available in sandbox catalogue.

---

## Readiness codes (reference)

| `readiness.code` | Meaning | Platizio action |
|------------------|---------|-----------------|
| `verified` | KYC compliant | Skip fresh KYC → bank → order |
| **`kyc_unavailable`** | No KRA record | Full KYC application |
| `kyc_incomplete` | Partial KYC | Update/complete KYC |
| `upstream_error` | Provider error | Retry pre-verification |
| `unknown` | Non-compliant, unknown reason | Manual review |
