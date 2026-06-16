# Starts Platizio backend on port 8081 (override with $env:SERVER_PORT).
# Port 8080 is often taken by Apache/httpd on Windows - backend defaults to 8081.
#
# Usage:
#   .\start-backend.ps1           # start only if port is free
#   .\start-backend.ps1 -Restart  # stop whatever holds the port, then start (use after code/DB changes)

param(
    [switch]$Restart
)

$ErrorActionPreference = "Stop"
$Port = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8081 }
$Root = $PSScriptRoot

function Stop-PortListener {
    param([int]$ListenPort)
    $pids = Get-NetTCPConnection -LocalPort $ListenPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        Where-Object { $_ -gt 0 }
    foreach ($procId in $pids) {
        Write-Host "Stopping process $procId listening on port $ListenPort ..."
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
    if ($pids) {
        Start-Sleep -Seconds 2
    }
}

function Test-BackendListening {
    param([int]$ListenPort)
    return [bool](Get-NetTCPConnection -LocalPort $ListenPort -State Listen -ErrorAction SilentlyContinue)
}

function Test-PlatizioBackend {
    param([int]$ListenPort)
    $probeUrls = @(
        "http://localhost:$ListenPort/actuator/health",
        "http://localhost:$ListenPort/api/v1/investors/00000000-0000-0000-0000-000000000001/kyc-flow/status",
        "http://localhost:$ListenPort/swagger-ui/index.html"
    )
    foreach ($url in $probeUrls) {
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 600) {
                return $true
            }
        } catch {
            if ($_.Exception.Response) {
                $code = [int]$_.Exception.Response.StatusCode
                # 401/403/404 from Spring means our app is up (auth/route), not a dead port.
                if ($code -in 401, 403, 404, 405) {
                    return $true
                }
            }
        }
    }
    return $false
}

if (Test-BackendListening -ListenPort $Port) {
    if ($Restart) {
        Write-Host "Restart requested - freeing port $Port ..."
        Stop-PortListener -ListenPort $Port
    } elseif (Test-PlatizioBackend -ListenPort $Port) {
        Write-Host "Platizio backend is already running on http://localhost:$Port"
        Write-Host "Use .\start-backend.ps1 -Restart to reload code/migrations."
        exit 0
    } else {
        Write-Host "Port $Port is in use by another process. Run .\start-backend.ps1 -Restart or free the port manually."
        exit 1
    }
}

if (Test-BackendListening -ListenPort $Port) {
    Write-Host "Port $Port is still in use. Close the other application and retry."
    exit 1
}

Set-Location $Root
$env:SERVER_PORT = $Port
Write-Host "Starting Platizio backend on http://localhost:$Port ..."
& .\mvnw.cmd spring-boot:run
