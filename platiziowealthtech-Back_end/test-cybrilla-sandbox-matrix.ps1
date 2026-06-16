# Tests Cybrilla sandbox POA pre-verification for every row in docs/sandbox-test-matrix-full.csv
# Run: powershell -ExecutionPolicy Bypass -File .\test-cybrilla-sandbox-matrix.ps1
#
# Requires: sandbox credentials in .env (same as backend)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$csvPath = Join-Path $root 'docs\sandbox-test-matrix-full.csv'
$envPath = Join-Path $root '.env'

function Read-DotEnv($path) {
    $map = @{}
    if (-not (Test-Path $path)) { return $map }
    Get-Content $path | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $k, $v = $_ -split '=', 2
        $map[$k.Trim()] = $v.Trim()
    }
    $map
}

function Get-Token($url, $clientId, $secret) {
    $body = @{ client_id = $clientId; client_secret = $secret; grant_type = 'client_credentials' }
    $resp = Invoke-RestMethod -Uri $url -Method POST -Body $body -ContentType 'application/x-www-form-urlencoded'
    return $resp.access_token
}

function New-PreVerificationPayload($pan, $firstName, $lastName, $dob) {
    $name = "$firstName $lastName".Trim()
    return (@{
        investor_identifier = $pan
        pan                 = @{ value = $pan }
        name                = @{ value = $name }
        date_of_birth       = @{ value = $dob }
    } | ConvertTo-Json -Compress)
}

function Invoke-PreVerification($token, $payload) {
    return Invoke-RestMethod -Uri 'https://api.sandbox.cybrilla.com/poa/pre_verifications' `
        -Method POST -Headers @{ Authorization = "Bearer $token" } `
        -Body $payload -ContentType 'application/json'
}

function Wait-PreVerification($token, $id, $maxAttempts = 8) {
    for ($i = 0; $i -lt $maxAttempts; $i++) {
        Start-Sleep -Seconds 2
        $pv = Invoke-RestMethod -Uri "https://api.sandbox.cybrilla.com/poa/pre_verifications/$id" `
            -Method GET -Headers @{ Authorization = "Bearer $token" }
        if ($pv.status -eq 'completed' -or $pv.status -eq 'failed') { return $pv }
    }
    return $pv
}

function Test-FpProfile($tenantToken, $tenantName, $pan, $name, $dob) {
    $headers = @{ Authorization = "Bearer $tenantToken"; 'x-tenant-id' = $tenantName }
    $body = (@{
        type = 'individual'
        tax_status = 'resident_individual'
        name = $name
        date_of_birth = $dob
        pan = $pan
        country_of_birth = 'IN'
        place_of_birth = 'Mumbai'
        nationality_country = 'IN'
        use_default_tax_residences = $true
        source_of_wealth = 'salary'
        income_slab = 'upto_1lakh'
        pep_details = 'not_applicable'
    } | ConvertTo-Json -Compress)
    try {
        $resp = Invoke-WebRequest -Uri 'https://s.finprim.com/v2/investor_profiles' `
            -Method POST -Headers $headers -Body $body -ContentType 'application/json'
        return @{ ok = $true; code = [int]$resp.StatusCode; detail = 'created' }
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { 0 }
        $detail = $_.Exception.Message
        if ($_.Exception.Response) {
            $r = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $r.ReadToEnd()
        }
        return @{ ok = $false; code = $code; detail = $detail }
    }
}

$env = Read-DotEnv $envPath
$poaClientId = $env['CYBRILLA_PRE_VERIFICATION_CLIENT_ID']
$poaSecret   = $env['CYBRILLA_PRE_VERIFICATION_CLIENT_SECRET']
$fpClientId  = $env['FINPRIM_TENANT_CLIENT_ID']
$fpSecret    = $env['FINPRIM_TENANT_CLIENT_SECRET']
$tenantName  = if ($env['FINPRIM_TENANT_NAME']) { $env['FINPRIM_TENANT_NAME'] } else { 'platizio' }

if (-not $poaClientId -or -not $fpClientId) {
    Write-Host 'Missing credentials in .env' -ForegroundColor Red
    exit 1
}

Write-Host "`n=== Auth ===" -ForegroundColor Cyan
$poaToken = Get-Token 'https://s.finprim.com/v2/auth/cybrillarta/token' $poaClientId $poaSecret
Write-Host "POA token: OK" -ForegroundColor Green
$fpToken = Get-Token "https://s.finprim.com/v2/auth/$tenantName/token" $fpClientId $fpSecret
Write-Host "FP tenant token: OK (x-tenant-id: $tenantName)" -ForegroundColor Green

$rows = Import-Csv $csvPath
$results = @()
$poaOnly = $rows | Where-Object { $_.expected_readiness_code -or $_.expected_pan_code -or $_.expected_name_code -or $_.expected_dob_code }

Write-Host "`n=== POA pre-verification matrix ($($poaOnly.Count) rows) ===" -ForegroundColor Cyan
foreach ($row in $poaOnly) {
    $payload = New-PreVerificationPayload $row.pan $row.first_name $row.last_name $row.dob
    $status = 'ERROR'
    $readiness = ''
    $panCode = ''
    $nameCode = ''
    $dobCode = ''
    $pass = $false
    $detail = ''
    try {
        $accepted = Invoke-PreVerification $poaToken $payload
        $done = Wait-PreVerification $poaToken $accepted.id
        $status = $done.status
        $readiness = if ($done.readiness.code) { $done.readiness.code } elseif ($done.readiness.status) { $done.readiness.status } else { '' }
        $panCode = $done.pan.code
        if (-not $panCode -and $done.pan.status) { $panCode = $done.pan.status }
        $nameCode = $done.name.code
        if (-not $nameCode -and $done.name.status) { $nameCode = $done.name.status }
        $dobCode = $done.'date_of_birth'.code
        if (-not $dobCode -and $done.date_of_birth.status) { $dobCode = $done.date_of_birth.status }

        $pass = $true
        if ($row.expected_readiness_code -and $readiness -ne $row.expected_readiness_code) { $pass = $false }
        if ($row.expected_pan_code -and $panCode -ne $row.expected_pan_code) { $pass = $false }
        if ($row.expected_name_code -and $nameCode -ne $row.expected_name_code) { $pass = $false }
        if ($row.expected_dob_code -and $dobCode -ne $row.expected_dob_code) { $pass = $false }
    } catch {
        $detail = $_.Exception.Message
        $pass = $false
    }

    $color = if ($pass) { 'Green' } else { 'Red' }
    $mark = if ($pass) { 'PASS' } else { 'FAIL' }
    Write-Host ("{0,-22} {1,-12} PAN={2,-12} readiness={3,-18} pan={4,-10} name={5,-10} dob={6}" -f `
        $row.scenario_id, $mark, $row.pan, $readiness, $panCode, $nameCode, $dobCode) -ForegroundColor $color
    if ($detail) { Write-Host "  $detail" -ForegroundColor Yellow }

    $results += [PSCustomObject]@{
        scenario_id = $row.scenario_id
        pan = $row.pan
        result = $mark
        readiness = $readiness
        pan_code = $panCode
        name_code = $nameCode
        dob_code = $dobCode
        expected_readiness = $row.expected_readiness_code
        expected_pan = $row.expected_pan_code
    }
}

Write-Host "`n=== FP investor profile (KYC-ready PANs only) ===" -ForegroundColor Cyan
$profileTests = @(
    @{ id = 'DEMO-A-FAST'; pan = 'AAAPA3751A'; name = 'Rajesh Kumar'; dob = '1990-05-15' }
    @{ id = 'KYC-READY-ALT1'; pan = 'GYAPS3751D'; name = 'Amit Patel'; dob = '1990-05-15' }
)
foreach ($t in $profileTests) {
    $r = Test-FpProfile $fpToken $tenantName $t.pan $t.name $t.dob
    $mark = if ($r.ok) { 'PASS' } else { 'FAIL' }
    $color = if ($r.ok) { 'Green' } else { 'Red' }
    Write-Host ("{0,-18} {1} HTTP {2} PAN={3}" -f $t.id, $mark, $r.code, $t.pan) -ForegroundColor $color
    if (-not $r.ok) { Write-Host "  $($r.detail.Substring(0, [Math]::Min(200, $r.detail.Length)))" -ForegroundColor Yellow }
}

$outPath = Join-Path $root 'docs\sandbox-test-results.csv'
$results | Export-Csv -Path $outPath -NoTypeInformation
Write-Host "`nResults saved to docs\sandbox-test-results.csv" -ForegroundColor Cyan
$passed = ($results | Where-Object { $_.result -eq 'PASS' }).Count
Write-Host "POA matrix: $passed / $($results.Count) passed" -ForegroundColor $(if ($passed -eq $results.Count) { 'Green' } else { 'Yellow' })
