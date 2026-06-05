# Sandbox Testing Matrix

Confirm the current simulator rules in the official pages before adding tests:

- Cybrilla POA direct transaction API: https://poa.cybrilla.com/docs/api
- POA pre-verification API: https://poa.cybrilla.com/docs/additional-apis/pre-verifications
- FP gateway sandbox simulation: https://docs.fintechprimitives.com/fp-cybrillapoa-gateway/sandbox-simulation

## Gateway and Direct POA Order Cases

- PAN matching `XXXPX3751X`: KYC-compliant investor simulation
- PAN matching `XXXPX3753X`: KYC-unavailable investor simulation
- bank account ending `1193`: successful bank verification simulation
- bank account ending `1515`: failed bank verification simulation
- order amount ending `0`: successful order simulation
- order amount ending `1`: failed order simulation

Replace each `X` with an alphabetic character.

## POA PAN Validation Cases

The pre-verification page defines separate PAN-validation patterns:

- `XXXPINNNNX`: invalid PAN simulation
- `XXXPANNNNX`: Aadhaar-not-linked simulation
- `XXXPXNNNNX`: valid PAN simulation

Replace each `X` with an alphabetic character and each `N` with a digit.

The same page defines:

- name `Lord Voldemort`: name mismatch simulation
- date of birth `2000-01-01`: date-of-birth mismatch simulation

## Test Design

- Keep gateway KYC-status scenarios separate from POA PAN-validation scenarios.
- Persist and assert the top-level async state before asserting nested results.
- Cover webhook reconciliation and scheduled polling for completed results.
- Cover manual-review bank codes without marking the investor permanently failed.
