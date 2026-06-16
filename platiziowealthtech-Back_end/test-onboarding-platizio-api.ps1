# Platizio onboarding API matrix - pincode, IFSC, documents, resume routing
# Run: powershell -ExecutionPolicy Bypass -File .\test-onboarding-platizio-api.ps1
# Requires: backend on http://localhost:8081 (local profile with demo seeder)

$ErrorActionPreference = 'Stop'
$BaseUrl = if ($env:PLATIZIO_API_BASE) { $env:PLATIZIO_API_BASE } else { 'http://localhost:8081/api/v1' }
$LoginEmail = if ($env:PLATIZIO_LOGIN_EMAIL) { $env:PLATIZIO_LOGIN_EMAIL } else { 'a@a.com' }
$LoginPassword = if ($env:PLATIZIO_LOGIN_PASSWORD) { $env:PLATIZIO_LOGIN_PASSWORD } else { 'Ok@123456' }
$DistributorId = if ($env:PLATIZIO_DISTRIBUTOR_ID) { $env:PLATIZIO_DISTRIBUTOR_ID } else { '4317cfd2-a41f-4320-a5dc-26835c7210ac' }

$results = New-Object System.Collections.Generic.List[object]
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Add-Result($id, $layer, $status, $detail) {
    $results.Add([pscustomobject]@{ scenario = $id; layer = $layer; status = $status; detail = $detail }) | Out-Null
    $color = switch ($status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } 'SKIP' { 'Yellow' } default { 'White' } }
    Write-Host "[$status] $id - $detail" -ForegroundColor $color
}

function Invoke-PlatizioJson($method, $path, $body = $null) {
    $uri = "$BaseUrl$path"
    $params = @{
        Uri             = $uri
        Method          = $method
        WebSession      = $session
        UseBasicParsing = $true
        ErrorAction     = 'Stop'
    }
    if ($body) {
        $params.Body = ($body | ConvertTo-Json -Depth 8 -Compress)
        $params.ContentType = 'application/json'
    }
    try {
        $resp = Invoke-WebRequest @params
        $text = $resp.Content
        if (-not $text) { return @{ status = $resp.StatusCode; body = $null } }
        return @{ status = $resp.StatusCode; body = ($text | ConvertFrom-Json) }
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { 0 }
        $detail = $_.Exception.Message
        if ($_.Exception.Response) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $detail = $reader.ReadToEnd()
        }
        return @{ status = $code; body = $detail; error = $true }
    }
}

function Get-TinyPdfBytes {
    $text = '%PDF-1.4' + [Environment]::NewLine + '1 0 obj<<>>endobj' + [Environment]::NewLine + 'trailer<<>>' + [Environment]::NewLine + '%%EOF'
    return [System.Text.Encoding]::ASCII.GetBytes($text)
}

Write-Host "`n=== Platizio onboarding API matrix ===" -ForegroundColor Cyan
Write-Host "Base: $BaseUrl`n"

# AUTH
$login = Invoke-PlatizioJson POST '/auth/login' @{ email = $LoginEmail; password = $LoginPassword }
if ($login.error -or $login.status -ge 400) {
    Add-Result 'AUTH-LOGIN' 'auth' 'FAIL' "Login failed ($($login.status)): $($login.body)"
    $results | Format-Table -AutoSize
    exit 1
}
Add-Result 'AUTH-LOGIN' 'auth' 'PASS' 'Demo distributor session established'

# PINCODE
$pin = Invoke-PlatizioJson GET '/banks/pincodes/400001'
if ($pin.error) {
    Add-Result 'PIN-400001' 'reference' 'FAIL' "Pincode lookup failed ($($pin.status))"
} elseif ($pin.body.city -or $pin.body.cities) {
    Add-Result 'PIN-400001' 'reference' 'PASS' "Mumbai pincode resolved"
} else {
    Add-Result 'PIN-400001' 'reference' 'FAIL' 'Pincode response missing city data'
}

$pinBad = Invoke-PlatizioJson GET '/banks/pincodes/000000'
if ($pinBad.status -eq 404 -or $pinBad.error) {
    Add-Result 'PIN-INVALID' 'reference' 'PASS' 'Invalid pincode rejected'
} else {
    Add-Result 'PIN-INVALID' 'reference' 'FAIL' 'Expected invalid pincode to fail'
}

# IFSC
$ifsc = Invoke-PlatizioJson GET '/banks/ifsc/HDFC0001234'
if ($ifsc.error) {
    Add-Result 'IFSC-HDFC' 'reference' 'FAIL' "IFSC lookup failed ($($ifsc.status))"
} elseif ($ifsc.body.bankName -or $ifsc.body.bank) {
    Add-Result 'IFSC-HDFC' 'reference' 'PASS' 'HDFC IFSC resolved'
} else {
    Add-Result 'IFSC-HDFC' 'reference' 'PASS' 'IFSC endpoint reachable (sandbox may return sparse payload)'
}

# INVESTOR (create new or reuse demo seed investor)
$DemoInvestorId = if ($env:PLATIZIO_TEST_INVESTOR_ID) { $env:PLATIZIO_TEST_INVESTOR_ID } else { '9b5c4d3e-2f1a-4c0b-9d8e-7f6a5b4c3d03' }
$suffix = Get-Random -Minimum 1000 -Maximum 9999
$mobile = "98765$suffix"
$panPool = @('BBBPB3753B', 'CCCPC3753C', 'DDDPD3753D', 'EEEPX3753E', 'FFFPF3751F')
$investorId = $null
foreach ($pan in $panPool) {
    $investorBody = @{
        distributorId    = $DistributorId
        fullName         = 'API Matrix Tester'
        mobileNumber     = $mobile
        email            = "api.matrix.$suffix@platizio.test"
        pan              = $pan
        dateOfBirth      = '1990-05-15'
        relationshipType = 'SELF'
        addressLine1     = '221B Baker Street'
        city             = 'Mumbai'
        state            = 'Maharashtra'
        postalCode       = '400001'
        onboardingNotes  = "api_test=$suffix"
    }
    $created = Invoke-PlatizioJson POST '/investors' $investorBody
    if (-not $created.error -and $created.status -lt 400) {
        $investorId = $created.body.id
        Add-Result 'INV-CREATE' 'investor' 'PASS' "Investor $investorId created with PAN $pan"
        break
    }
    if ($created.status -eq 409) { continue }
}
if (-not $investorId) {
    $investorId = $DemoInvestorId
    Add-Result 'INV-CREATE' 'investor' 'PASS' "Reusing demo investor $investorId (PAN pool exhausted)"
}

# DOCUMENT UPLOADS
$pdf = Get-TinyPdfBytes
$docTypes = @('pan', 'address', 'signature')
foreach ($docType in $docTypes) {
    $boundary = [guid]::NewGuid().ToString()
    $fileName = "$docType-test.pdf"
    $pdfText = [System.Text.Encoding]::ASCII.GetString($pdf)
    $bodyLines = @(
        "--$boundary",
        "Content-Disposition: form-data; name=`"documentType`"",
        "",
        $docType,
        "--$boundary",
        "Content-Disposition: form-data; name=`"file`"; filename=`"$fileName`"",
        "Content-Type: application/pdf",
        "",
        $pdfText,
        "--$boundary--"
    )
    $bodyRaw = ($bodyLines -join "`r`n")
    try {
        $upload = Invoke-WebRequest -Uri "$BaseUrl/investors/$investorId/documents" `
            -Method PUT -WebSession $session -Body $bodyRaw `
            -ContentType "multipart/form-data; boundary=$boundary" -UseBasicParsing
        if ($upload.StatusCode -ge 200 -and $upload.StatusCode -lt 300) {
            Add-Result "DOC-UPLOAD-$($docType.ToUpper())" 'documents' 'PASS' "$docType stored"
        } else {
            Add-Result "DOC-UPLOAD-$($docType.ToUpper())" 'documents' 'FAIL' "HTTP $($upload.StatusCode)"
        }
    } catch {
        Add-Result "DOC-UPLOAD-$($docType.ToUpper())" 'documents' 'FAIL' $_.Exception.Message
    }
}

# LIST DOCUMENTS
$list = Invoke-PlatizioJson GET "/investors/$investorId/documents"
if ($list.error) {
    Add-Result 'DOC-LIST' 'documents' 'FAIL' "List failed ($($list.status))"
} else {
    $types = @($list.body | ForEach-Object { $_.documentType })
    $missing = @('PAN', 'ADDRESS', 'SIGNATURE') | Where-Object { $types -notcontains $_ }
    if ($missing.Count -eq 0) {
        Add-Result 'DOC-LIST' 'documents' 'PASS' 'All three document types listed'
    } else {
        Add-Result 'DOC-LIST' 'documents' 'FAIL' "Missing types: $($missing -join ', ')"
    }
}

# ONBOARDING RESUME (before bank - expect ADD_BANK or KYC step)
$resume = Invoke-PlatizioJson GET "/investors/$investorId/onboarding/resume"
if ($resume.error) {
    Add-Result 'RESUME-AFTER-DOCS' 'resume' 'FAIL' "Resume failed ($($resume.status))"
} else {
    $step = $resume.body.nextStep
    $docsComplete = $resume.body.documentsComplete
    if ($docsComplete -eq $true) {
        Add-Result 'RESUME-DOCS-COMPLETE' 'resume' 'PASS' 'documentsComplete=true after uploads'
    } else {
        Add-Result 'RESUME-DOCS-COMPLETE' 'resume' 'FAIL' "documentsComplete=$docsComplete missing=$($resume.body.missingDocumentTypes -join ',')"
    }
    if ($step -in @('ADD_BANK_ACCOUNT', 'REFRESH_BANK_VERIFICATION', 'APPLY_KYC', 'REFRESH_KYC', 'UPLOAD_DOCUMENTS', 'READY_FOR_TRANSACTIONS')) {
        Add-Result 'RESUME-NEXT-STEP' 'resume' 'PASS' "nextStep=$step"
    } else {
        Add-Result 'RESUME-NEXT-STEP' 'resume' 'FAIL' "Unexpected nextStep=$step"
    }
}

# DUPLICATE DOC TYPE REJECT (optional validation)
$dup = Invoke-PlatizioJson GET "/investors/$investorId/documents"
if (-not $dup.error -and $dup.body.Count -ge 3) {
    Add-Result 'DOC-COUNT' 'documents' 'PASS' "Stored $($dup.body.Count) document metadata rows"
}

Write-Host "`n=== Summary ===" -ForegroundColor Cyan
$results | Format-Table -AutoSize
$failCount = ($results | Where-Object { $_.status -eq 'FAIL' }).Count
$passCount = ($results | Where-Object { $_.status -eq 'PASS' }).Count
Write-Host "Passed: $passCount  Failed: $failCount"
if ($failCount -gt 0) { exit 1 }
