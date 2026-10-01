param(
    [Parameter(Mandatory = $true)]
    [string]$OldApk,

    [Parameter(Mandatory = $true)]
    [string]$NewApk
)

$ErrorActionPreference = "Stop"

function Resolve-ApkSigner {
    $sdk = $env:ANDROID_SDK_ROOT
    if ([string]::IsNullOrWhiteSpace($sdk)) { $sdk = $env:ANDROID_HOME }
    if ([string]::IsNullOrWhiteSpace($sdk)) {
        throw "ANDROID_SDK_ROOT o ANDROID_HOME no está configurado."
    }

    $buildTools = Join-Path $sdk "build-tools"
    $candidate = Get-ChildItem $buildTools -Directory |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1

    if (-not $candidate) { throw "No encontré apksigner.bat dentro de Android SDK Build Tools." }
    return $candidate
}

function Get-CertSha256([string]$Apk, [string]$ApkSigner) {
    if (-not (Test-Path $Apk)) { throw "No existe el APK: $Apk" }

    $output = & $ApkSigner verify --print-certs $Apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "No pude verificar la firma de $Apk`n$output" }

    $line = $output | Where-Object { $_ -match "Signer #1 certificate SHA-256 digest:" } | Select-Object -First 1
    if (-not $line) { throw "No pude leer el SHA-256 del certificado de $Apk" }

    return (($line -split ":", 2)[1]).Trim().ToLowerInvariant()
}

$apksigner = Resolve-ApkSigner
$oldSha = Get-CertSha256 -Apk $OldApk -ApkSigner $apksigner
$newSha = Get-CertSha256 -Apk $NewApk -ApkSigner $apksigner

Write-Host "APK instalado/anterior: $oldSha"
Write-Host "APK Dronnk 2.0:       $newSha"

if ($oldSha -eq $newSha) {
    Write-Host "OK: las firmas coinciden. Android puede aceptar Dronnk 2.0 como actualización si el versionCode también es superior." -ForegroundColor Green
    exit 0
}

Write-Host "ERROR: las firmas NO coinciden. No instales este APK encima de la versión actual." -ForegroundColor Red
exit 2
