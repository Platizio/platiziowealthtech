# Tests the documented Cybrilla/FinPrim APIs directly with sandbox credentials.
# Run from the backend folder:  powershell -ExecutionPolicy Bypass -File .\test-cybrilla-fund-schemes.ps1
#
# It performs:
#   1) FinPrim tenant OAuth token (client_credentials)  -> documented auth API
#   2) GET /api/oms/fund_schemes                         -> mutual fund catalogue (OMS)
#   3) GET /v2/sif_scheme_plans/cybrillapoa              -> SIF catalogue (POA gateway; 404 if not enabled)
#   4) Cybrilla POA pre-verification OAuth token         -> documented POA auth API
# and prints status + a trimmed body for each.

$ErrorActionPreference = 'Stop'

# --- Config (matches your backend .env) ---
$finprimBase     = 'https://s.finprim.com'
$tenantName      = 'platizio'
$tenantClientId  = 'platizio_test_063f864dbde445edb17bde3123d94554'
$tenantSecret    = '9UBIHNq9adrfrjaKaW4vGk2hs6JyVlUE'
$tenantTokenUrl  = "$finprimBase/v2/auth/$tenantName/token"

$poaTokenUrl     = 'https://s.finprim.com/v2/auth/cybrillarta/token'
$poaClientId     = 'mfdptnr_platizio_test_f05dcf7fc8ab433da2555f87538c8a31'
$poaSecret       = 'zTUolJqtdd4nqpQP0KJ1qTelnWOr1qos'

function Get-Token($url, $clientId, $secret, $label) {
    Write-Host "`n=== $label token ===" -ForegroundColor Cyan
    Write-Host "POST $url"
    $body = @{ client_id = $clientId; client_secret = $secret; grant_type = 'client_credentials' }
    try {
        $resp = Invoke-RestMethod -Uri $url -Method POST -Body $body -ContentType 'application/x-www-form-urlencoded'
        $tok = $resp.access_token
        if ([string]::IsNullOrWhiteSpace($tok)) { Write-Host "NO access_token in response" -ForegroundColor Red; return $null }
        Write-Host ("OK  expires_in=" + $resp.expires_in + "  token=" + $tok.Substring(0, [Math]::Min(18, $tok.Length)) + "...") -ForegroundColor Green
        return $tok
    } catch {
        Write-Host "TOKEN FAILED" -ForegroundColor Red
        Write-Host $_.Exception.Message
        if ($_.Exception.Response) {
            $r = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            Write-Host $r.ReadToEnd()
        }
        return $null
    }
}

# 1) FinPrim tenant token
$tenantToken = Get-Token $tenantTokenUrl $tenantClientId $tenantSecret 'FinPrim tenant'

# 2) Fund schemes (the product_schemes source)
if ($tenantToken) {
    Write-Host "`n=== GET fund_schemes ===" -ForegroundColor Cyan
    $url = "$finprimBase/api/oms/fund_schemes?page=0&size=5"
    Write-Host "GET $url   (x-tenant-id: $tenantName)"
    try {
        $headers = @{ Authorization = "Bearer $tenantToken"; 'x-tenant-id' = $tenantName }
        $resp = Invoke-WebRequest -Uri $url -Method GET -Headers $headers
        Write-Host ("HTTP " + [int]$resp.StatusCode) -ForegroundColor Green
        $content = $resp.Content
        Write-Host $content.Substring(0, [Math]::Min(1500, $content.Length))
    } catch {
        Write-Host "FUND_SCHEMES FAILED" -ForegroundColor Red
        if ($_.Exception.Response) {
            Write-Host ("HTTP " + [int]$_.Exception.Response.StatusCode.value__) -ForegroundColor Red
            $r = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            Write-Host $r.ReadToEnd()
        } else { Write-Host $_.Exception.Message }
    }
}

# 3) SIF scheme plans (same pagination model as exports/cybrilla-poa-fund-schemes-*.json for MF)
if ($tenantToken) {
    Write-Host "`n=== GET sif_scheme_plans (cybrillapoa) ===" -ForegroundColor Cyan
    $url = "$finprimBase/v2/sif_scheme_plans/cybrillapoa?expand=sif_scheme,sif_fund&page=0&size=5"
    Write-Host "GET $url   (x-tenant-id: $tenantName)"
    try {
        $headers = @{ Authorization = "Bearer $tenantToken"; 'x-tenant-id' = $tenantName }
        $resp = Invoke-WebRequest -Uri $url -Method GET -Headers $headers
        Write-Host ("HTTP " + [int]$resp.StatusCode) -ForegroundColor Green
        $content = $resp.Content
        Write-Host $content.Substring(0, [Math]::Min(1500, $content.Length))
    } catch {
        Write-Host "SIF_SCHEME_PLANS FAILED (404 is OK if SIF is not enabled on tenant)" -ForegroundColor Yellow
        if ($_.Exception.Response) {
            Write-Host ("HTTP " + [int]$_.Exception.Response.StatusCode.value__) -ForegroundColor Yellow
            $r = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            Write-Host $r.ReadToEnd()
        } else { Write-Host $_.Exception.Message }
    }
}

# 4) POA pre-verification token (confirms the 2nd 30-min token audience)
Get-Token $poaTokenUrl $poaClientId $poaSecret 'Cybrilla POA pre-verification' | Out-Null

Write-Host "`nDone." -ForegroundColor Cyan
