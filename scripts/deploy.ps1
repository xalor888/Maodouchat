<#
.Maodouchat 一键部署脚本（Windows / PowerShell）

用法（仓库根目录）：
  ./scripts/deploy.ps1                    # 交互式引导（推荐首次）
  ./scripts/deploy.ps1 -Host chat.example.com -Email you@example.com
  ./scripts/deploy.ps1 -Host chat.example.com -Email you@example.com -Relaxed -BootstrapAdmin
  ./scripts/deploy.ps1 -Doctor            # 只读预检（不修改任何文件）
  ./scripts/deploy.ps1 -Version           # 打印工具版本（git describe）后退出
  ./scripts/deploy.ps1 -DryRun            # 准备 .env + 打印配置摘要，不启动容器

自动完成：生成/补全 .env（自动生成全部强随机密钥）、启动容器、等待健康检查、
打印管理后台与后续指引。重复运行幂等，只补缺失键、不改已有值。
#>
[CmdletBinding()]
param(
    [Alias('Host')]
    [string]$Hostname,
    [string]$Email,
    [switch]$Relaxed,
    [switch]$NoBuild,
    [switch]$BootstrapAdmin,
    [switch]$Doctor,
    [switch]$Version,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$workspace = Split-Path -Parent $PSScriptRoot
Set-Location $workspace

# 1.329：-Version 打印工具版本后退出（与 deploy.sh --version 一致）
if ($Version) {
    $ver = & git describe --always --tags --dirty 2>$null
    if (-not $ver) { $ver = "unknown" }
    Write-Host "deploy.ps1 $ver"
    exit 0
}

function Fail([string]$msg) { Write-Error "FAIL: $msg"; exit 1 }
function Ok([string]$msg) { Write-Host "  OK: $msg" -ForegroundColor Green }
function Gen-Secret { (1..4 | ForEach-Object { [System.Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(12)).ToLowerInvariant() }) -join "" }

# ── 0. --doctor 预检（只读，不修改任何文件，检查后退出） ──
function Doctor-Checks {
    $script:status = 0
    $script:warn = 0
    function Doctor-Pass([string]$msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
    function Doctor-Warn([string]$msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow; $script:warn = 1 }
    function Doctor-Fail([string]$msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:status = 1 }
    function Get-EnvValue([string]$key) {
        if (-not (Test-Path .env)) { return "" }
        $line = Select-String -Path .env -Pattern "^${key}=.*" | Select-Object -First 1
        if ($line) { $line.Line.Substring($key.Length + 1) } else { "" }
    }

    Write-Host "== Maodouchat deploy doctor (preflight) =="

    docker compose version *> $null
    if ($LASTEXITCODE -eq 0) { Doctor-Pass "docker compose v2 available" } else { Doctor-Fail "docker compose v2 not available." }

    # 1.372：docker 守护进程在运行（compose version 不连 daemon，若 daemon 未起部署会直接失败）
    docker info *> $null
    if ($LASTEXITCODE -eq 0) { Doctor-Pass "docker daemon running" } else { Doctor-Fail "docker daemon not running. Start Docker (e.g. launch Docker Desktop / start the docker service)." }

    if (Test-Path .env) {
        Doctor-Pass ".env exists"
    } elseif (Test-Path .env.docker.example) {
        Doctor-Warn ".env missing - deploy will create it from .env.docker.example"
    } else {
        Doctor-Fail ".env missing and .env.docker.example missing"
    }

    if (Test-Path .env) {
        $jwt = Get-EnvValue "JWT_SECRET"
        if ([string]::IsNullOrEmpty($jwt) -or $jwt.Length -lt 32 -or $jwt -like "replace-with*") {
            Doctor-Fail "JWT_SECRET weak/placeholder (<32 chars or replace-with). deploy will auto-generate."
        } else { Doctor-Pass "JWT_SECRET strong ($($jwt.Length) chars)" }

        $pg = Get-EnvValue "POSTGRES_PASSWORD"
        if ([string]::IsNullOrEmpty($pg) -or $pg -like "replace-with*") {
            Doctor-Warn "POSTGRES_PASSWORD placeholder - deploy will auto-generate"
        } else { Doctor-Pass "POSTGRES_PASSWORD set" }

        $push = Get-EnvValue "PUSH_HMAC_SECRET"
        $relaxedVal = Get-EnvValue "RELAXED_VERIFICATION"
        if ([string]::IsNullOrEmpty($push) -or $push -like "replace-with*") {
            if ($relaxedVal -eq "true") { Doctor-Pass "PUSH_HMAC_SECRET blank (relaxed mode allows it)" }
            else { Doctor-Warn "PUSH_HMAC_SECRET placeholder - deploy will auto-generate" }
        } else { Doctor-Pass "PUSH_HMAC_SECRET set" }
    } else {
        Doctor-Warn "skipping .env secret checks (no .env yet)"
    }

    $hostVal = Get-EnvValue "PUBLIC_HOST"
    $emailVal = Get-EnvValue "ACME_EMAIL"
    if ([string]::IsNullOrEmpty($hostVal) -or $hostVal -like "replace-with*" -or $hostVal -eq "api.example.com") {
        Doctor-Warn "PUBLIC_HOST unset - run with -Host your.domain.com"
    } elseif ($hostVal -like "localhost*" -or $hostVal -like "127.0.0.1*") {
        Doctor-Pass "PUBLIC_HOST=$hostVal (localhost - HTTPS not required)"
    } else {
        Doctor-Pass "PUBLIC_HOST=$hostVal (DNS/HTTPS check happens at deploy time)"
    }
    if ([string]::IsNullOrEmpty($emailVal) -or $emailVal -like "replace-with*" -or $emailVal -eq "admin@example.com") {
        Doctor-Warn "ACME_EMAIL unset - run with -Email you@example.com"
    } else { Doctor-Pass "ACME_EMAIL=$emailVal" }

    if (Test-Path .env) {
        docker compose --env-file .env config -q *> $null
        if ($LASTEXITCODE -eq 0) { Doctor-Pass "docker compose config valid" } else { Doctor-Fail "docker compose config invalid (see errors above)" }
    }

    Write-Host ""
    if ($script:status -eq 1) { Write-Host "Doctor: FAIL - fix the [FAIL] items above, then re-run." -ForegroundColor Red; exit 1 }
    elseif ($script:warn -eq 1) { Write-Host "Doctor: OK (with warnings)." }
    else { Write-Host "Doctor: OK - ready to deploy." }
    exit 0
}

if ($Doctor) { Doctor-Checks }

# ── 1. 基础环境检查 ─────────────────────────
docker compose version *> $null
if ($LASTEXITCODE -ne 0) { Fail "docker compose v2 not available." }

Write-Host "== Maodouchat deploy =="

# ── 2. .env 准备（幂等） ────────────────────
if (-not (Test-Path .env)) {
    Copy-Item .env.docker.example .env
    Write-Host ".env created from .env.docker.example"
}

# ── 首次部署交互式引导（0.65）：无 -Host/-Email 且交互终端时逐项提问 ──
function Get-EnvValEarly([string]$key) {
    $line = Select-String -Path .env -Pattern "^${key}=.*" | Select-Object -First 1
    if ($line) { $line.Line.Substring($key.Length + 1) } else { "" }
}
if (-not $Hostname -and [Environment]::UserInteractive) {
    $eh = Get-EnvValEarly "PUBLIC_HOST"
    if ([string]::IsNullOrEmpty($eh) -or $eh -like "replace-with*" -or $eh -eq "api.example.com") {
        $Hostname = Read-Host "Public hostname (e.g. chat.example.com)"
        $Hostname = $Hostname.Trim()
    }
}
if (-not $Email -and [Environment]::UserInteractive) {
    $ee = Get-EnvValEarly "ACME_EMAIL"
    if ([string]::IsNullOrEmpty($ee) -or $ee -like "replace-with*" -or $ee -eq "admin@example.com") {
        $Email = Read-Host "ACME certificate email (you@example.com)"
        $Email = $Email.Trim()
    }
}
if (-not $Hostname -and -not $Email -and [Environment]::UserInteractive -and -not $Relaxed) {
    $ans = Read-Host "Enable relaxed self-hosted mode (no SMTP/TURN needed)? [Y/n]"
    if ($ans -notmatch "^[nN]") { $Relaxed = $true }
}

function Ensure-Key([string]$key, [string]$default) {
    if (-not (Select-String -Path .env -Pattern "^${key}=" -Quiet)) {
        Add-Content -Path .env -Value "${key}=${default}"
        Ok "added ${key}"
    }
}

function Replace-Key([string]$key, [string]$value) {
    $line = Select-String -Path .env -Pattern "^${key}=.*" | Select-Object -First 1
    $cur = if ($line) { $line.Line.Substring($key.Length + 1) } else { "" }
    if ([string]::IsNullOrEmpty($cur) -or $cur -like "replace-with*") {
        $content = Get-Content .env
        $content = $content | ForEach-Object {
            if ($_ -match "^${key}=") { "${key}=${value}" } else { $_ }
        }
        Set-Content -Path .env -Value $content -Encoding UTF8
        Ok "${key} set to a generated random value"
    }
}

if ($Hostname) {
    Ensure-Key "PUBLIC_HOST" ""
    Replace-Key "PUBLIC_HOST" $Hostname
}
if ($Email) {
    Ensure-Key "ACME_EMAIL" ""
    Replace-Key "ACME_EMAIL" $Email
}
Ensure-Key "BASE_URL" ""
if ($Hostname) { Replace-Key "BASE_URL" "https://${Hostname}" }

Replace-Key "JWT_SECRET" ((Gen-Secret) + (Gen-Secret))
Replace-Key "PUSH_HMAC_SECRET" ((Gen-Secret) + (Gen-Secret))
Replace-Key "POSTGRES_PASSWORD" (Gen-Secret)
Replace-Key "TURN_SHARED_SECRET" ((Gen-Secret) + (Gen-Secret))
if ($Hostname) {
    Replace-Key "TURN_REALM" "turn.${Host}"
    Replace-Key "TURN_URLS" "turn:turn.${Host}:3478?transport=udp,turn:turn.${Host}:3478?transport=tcp"
}
Ensure-Key "ADMIN_PATH" ""
Replace-Key "ADMIN_PATH" ("admin-" + (Gen-Secret).Substring(0, 14))

if ($Relaxed) {
    Replace-Key "RELAXED_VERIFICATION" "true"
    Replace-Key "DEV_LOG_CODES" "true"
    # 8.47 修复：不再清空用户已配置的 SMTP_HOST（relaxed 只放宽校验，不清空真实配置）
    Ensure-Key "SMTP_HOST" ""
    Ok "relaxed mode: SMTP/TURN optional, codes printed to logs"
}
if ($BootstrapAdmin) {
    Replace-Key "BOOTSTRAP_FIRST_USER_AS_ADMIN" "true"
    Ok "bootstrap-admin: the first registered user becomes owner admin"
}

function Get-EnvVal([string]$key) {
    $line = Select-String -Path .env -Pattern "^${key}=.*" | Select-Object -First 1
    if ($line) { $line.Line.Substring($key.Length + 1) } else { "" }
}

$publicHost = Get-EnvVal "PUBLIC_HOST"
$baseUrl = Get-EnvVal "BASE_URL"
$acmeEmail = Get-EnvVal "ACME_EMAIL"
if ([string]::IsNullOrEmpty($publicHost) -or $publicHost -like "replace-with*" -or $publicHost -eq "api.example.com") {
    Fail "PUBLIC_HOST not set. Run with -Host your.domain.com"
}
if ([string]::IsNullOrEmpty($acmeEmail) -or $acmeEmail -like "replace-with*" -or $acmeEmail -eq "admin@example.com") {
    Fail "ACME_EMAIL not set. Run with -Email you@example.com"
}
# 8.48：提前校验弱配置——JWT_SECRET 仍是占位或过短时服务端生产校验会启动失败，提前拦截
$jwtSecret = Get-EnvVal "JWT_SECRET"
if ([string]::IsNullOrEmpty($jwtSecret) -or $jwtSecret.Length -lt 32 -or $jwtSecret -like "replace-with*") {
    Fail "JWT_SECRET must be at least 32 random characters (regenerate or set a strong value in .env)"
}

# ── 3. 启动 ──────────────────────────────────
Write-Host "== Validating compose =="
docker compose --env-file .env config -q
if ($LASTEXITCODE -ne 0) { Fail "docker compose config invalid" }

# 1.353：-DryRun 打印配置摘要后退出（与 deploy.sh --dry-run 对齐），不启动容器
if ($DryRun) {
    Write-Host "== DRY RUN: .env prepared, not starting containers =="
    Write-Host "   PUBLIC_HOST   = $publicHost"
    Write-Host "   BASE_URL      = $baseUrl"
    Write-Host "   ACME_EMAIL    = $acmeEmail"
    Write-Host "   RELAXED       = $(Get-EnvVal 'RELAXED_VERIFICATION')"
    Write-Host "   BOOTSTRAP     = $(Get-EnvVal 'BOOTSTRAP_FIRST_USER_AS_ADMIN')"
    Write-Host "   ADMIN_PATH    = $(Get-EnvVal 'ADMIN_PATH')"
    Write-Host "   MASTER_ADMINS = $(Get-EnvVal 'MASTER_ADMINS')"
    Write-Host "   ALLOW_REG     = $(Get-EnvVal 'ALLOW_REGISTRATION')"
    Write-Host ""
    Write-Host " Review .env, then run without -DryRun to deploy."
    exit 0
}

Write-Host "== Starting stack =="
if ($NoBuild) { docker compose up -d } else { docker compose up -d --build }
if ($LASTEXITCODE -ne 0) { Fail "docker compose up failed" }

# ── 4. 健康等待 ──────────────────────────────
Write-Host "== Waiting for /health/ready (up to 300s) =="
$ready = $false
for ($i = 1; $i -le 30; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "https://${publicHost}/health/ready" -TimeoutSec 10 -UseBasicParsing
        if ($r.Content -match "ready") { $ready = $true; break }
    } catch { }
    if ($i % 6 -eq 0) { Write-Host "  ... still waiting ($($i * 10)s)" }
    Start-Sleep -Seconds 10
}
if (-not $ready) {
    Write-Host "FAIL: server not ready after 300s. Check logs:" -ForegroundColor Red
    docker compose logs --tail=80 server
    exit 1
}
Ok "https://${publicHost}/health/ready is ready"

# ── 5. 总结与指引 ────────────────────────────
Write-Host ""
Write-Host "==============================="
Write-Host " Maodouchat is UP:"
Write-Host "   API:   $baseUrl"
Write-Host "   Admin: $baseUrl/$(Get-EnvVal 'ADMIN_PATH')/admin  (MASTER_ADMINS only)"
Write-Host "   Web:   $baseUrl"
Write-Host ""
Write-Host " First-time admin setup:"
if ($BootstrapAdmin) {
    Write-Host "   1. Register your account in the app (first account = owner admin)."
    Write-Host "   2. After registering, edit .env: set BOOTSTRAP_FIRST_USER_AS_ADMIN=false"
    Write-Host "      then run: ./scripts/deploy.ps1 -NoBuild"
}
else {
    Write-Host "   1. Register your account in the app."
    Write-Host "   2. Get your user id: docker compose exec -T db psql -U maodouchat -d maodouchat -c `"SELECT id,email FROM users`""
    Write-Host "   3. Edit .env: MASTER_ADMINS=<your-user-id>, then run: ./scripts/deploy.ps1 -NoBuild"
}
Write-Host ""
Write-Host " Update:   git pull && ./scripts/deploy.ps1  (rebuild image so new code takes effect)"
Write-Host " Logs:     docker compose logs -f server"
Write-Host "==============================="

