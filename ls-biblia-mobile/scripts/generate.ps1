<#
.SYNOPSIS
    Gera o APK ou o Android App Bundle (AAB) do aplicativo.

.EXAMPLE
    .\scripts\generate.ps1

.EXAMPLE
    .\scripts\generate.ps1 -Variant Release -Clean

.EXAMPLE
    .\scripts\generate.ps1 -Format Aab
#>

[CmdletBinding()]
param(
    [ValidateSet("Apk", "Aab")]
    [string] $Format = "Apk",

    [ValidateSet("Debug", "Release")]
    [string] $Variant,

    [switch] $Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Variant)) {
    if ($Format -eq "Aab") {
        $Variant = "Release"
    }
    else {
        $Variant = "Debug"
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
$variantName = $Variant.ToLowerInvariant()

if ($Format -eq "Aab") {
    $buildTask = ":app:bundle$Variant"
    $artifactDirectory = Join-Path $projectRoot "app\build\outputs\bundle\$variantName"
    $artifactFilter = "*.aab"
}
else {
    $buildTask = ":app:assemble$Variant"
    $artifactDirectory = Join-Path $projectRoot "app\build\outputs\apk\$variantName"
    $artifactFilter = "*.apk"
}

$artifactName = $Format.ToUpperInvariant()

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle Wrapper nao encontrado em: $gradleWrapper"
}

Push-Location $projectRoot

try {
    if ($Clean) {
        Write-Host "Limpando o projeto..." -ForegroundColor Cyan
        & $gradleWrapper clean --console=plain

        if ($LASTEXITCODE -ne 0) {
            throw "A limpeza do projeto falhou (codigo $LASTEXITCODE)."
        }
    }

    Write-Host "Gerando $artifactName $Variant..." -ForegroundColor Cyan
    & $gradleWrapper $buildTask --console=plain

    if ($LASTEXITCODE -ne 0) {
        throw "A geracao do $artifactName falhou (codigo $LASTEXITCODE)."
    }
}
finally {
    Pop-Location
}

$artifacts = @()
if (Test-Path -LiteralPath $artifactDirectory -PathType Container) {
    $artifacts = @(
        Get-ChildItem -LiteralPath $artifactDirectory -Filter $artifactFilter -File -Recurse |
        Sort-Object LastWriteTime -Descending
    )
}

if ($artifacts.Count -eq 0) {
    throw "O Gradle terminou sem erros, mas nenhum $artifactName foi encontrado em: $artifactDirectory"
}

Write-Host "`n$artifactName gerado com sucesso:" -ForegroundColor Green
foreach ($artifact in $artifacts) {
    Write-Host "  $($artifact.FullName)"
}

if ($Format -eq "Aab" -and $Variant -eq "Release") {
    Write-Warning "Antes de enviar ao Google Play, confirme que o AAB esta assinado com sua chave de upload."
}
