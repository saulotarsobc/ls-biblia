#Requires -Version 5.1
<#
    Funções compartilhadas pelos scripts de release.

    Este arquivo NÃO é um ponto de entrada: ele é carregado via dot-source
    pelos outros scripts (`. "$PSScriptRoot\common.ps1"`).
#>

Set-StrictMode -Version Latest

# ---------------------------------------------------------------------------
# Saída no console
# ---------------------------------------------------------------------------

function Write-Step {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Info {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "    $Message" -ForegroundColor DarkGray
}

function Write-Ok {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "    $Message" -ForegroundColor Green
}

function Write-Warn {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host "    ! $Message" -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Execução de programas externos
# ---------------------------------------------------------------------------

<#
    Roda um executável externo e trata o código de saída.

    O PowerShell não lança exceção quando um .exe falha — só atualiza
    $LASTEXITCODE. Sem isso, um `git push` que falha passaria despercebido e o
    script seguiria publicando. O stderr é redirecionado para o stdout porque
    o git escreve mensagens normais lá (ex.: "To https://github.com/..."), o
    que viraria erro com $ErrorActionPreference = 'Stop'.
#>
function Invoke-Native {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [switch]$Silent,        # não ecoa a saída no console
        [switch]$AllowFailure   # devolve o código em vez de abortar
    )

    $lines = New-Object 'System.Collections.Generic.List[string]'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $FilePath @Arguments 2>&1 | ForEach-Object {
            $line = if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { [string]$_ }
            $lines.Add($line)
            if (-not $Silent) { Write-Host "    $line" -ForegroundColor DarkGray }
        }
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        $cmd = "$FilePath $($Arguments -join ' ')"
        throw "Comando falhou (exit $exitCode): $cmd`n$($lines -join "`n")"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines    = $lines.ToArray()
    }
}

function Invoke-Git {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$Silent,
        [switch]$AllowFailure
    )
    return Invoke-Native -FilePath 'git' -Arguments $Arguments -Silent:$Silent -AllowFailure:$AllowFailure
}

function Assert-Command {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Hint
    )
    $found = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $found) {
        throw "'$Name' não foi encontrado no PATH. $Hint"
    }
}

# ---------------------------------------------------------------------------
# Projeto
# ---------------------------------------------------------------------------

function Get-RepoRoot {
    # Os scripts vivem em <raiz>/scripts, então a raiz é o diretório pai.
    return (Resolve-Path ([System.IO.Path]::Combine($PSScriptRoot, '..'))).Path
}

function Get-PackageJson {
    param([string]$RepoRoot = (Get-RepoRoot))
    $path = [System.IO.Path]::Combine($RepoRoot, 'package.json')
    if (-not (Test-Path -LiteralPath $path)) {
        throw "package.json não encontrado em $RepoRoot"
    }
    return (Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

<#
    Extrai owner/repo do bloco `build.publish` do package.json — é a mesma
    fonte que o electron-builder usa, então o script e o publisher nunca
    apontam para repositórios diferentes.
#>
function Get-PublishTarget {
    param([Parameter(Mandatory)]$PackageJson)

    $publish = $null
    if ($PackageJson.PSObject.Properties.Name -contains 'build') {
        if ($PackageJson.build.PSObject.Properties.Name -contains 'publish') {
            $publish = @($PackageJson.build.publish)[0]
        }
    }
    if (-not $publish) {
        throw "package.json não tem 'build.publish' configurado — sem isso o electron-builder não sabe onde publicar."
    }

    return [pscustomobject]@{
        Owner = $publish.owner
        Repo  = $publish.repo
    }
}

<#
    Descobre o token do GitHub, em ordem de preferência:
      1. variável de ambiente GH_TOKEN / GITHUB_TOKEN
      2. arquivos locais electron-builder.env ou .env (ambos no .gitignore)
      3. o login do GitHub CLI (`gh auth token`)

    O item 3 é o que faz o script funcionar sem nenhuma configuração extra
    em uma máquina que já tenha rodado `gh auth login`.
#>
function Resolve-GitHubToken {
    param([string]$RepoRoot = (Get-RepoRoot))

    foreach ($name in @('GH_TOKEN', 'GITHUB_TOKEN')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if ($value -and $value.Trim()) {
            return [pscustomobject]@{ Token = $value.Trim(); Source = "variável de ambiente $name" }
        }
    }

    foreach ($file in @('electron-builder.env', '.env')) {
        $path = [System.IO.Path]::Combine($RepoRoot, $file)
        if (-not (Test-Path -LiteralPath $path)) { continue }
        foreach ($line in (Get-Content -LiteralPath $path)) {
            if ($line -match '^\s*(?:export\s+)?GH_TOKEN\s*=\s*(.+?)\s*$') {
                # Remove aspas simples ou duplas ao redor do valor.
                $token = $Matches[1].Trim().Trim('"').Trim("'")
                if ($token) {
                    return [pscustomobject]@{ Token = $token; Source = $file }
                }
            }
        }
    }

    $gh = Get-Command 'gh' -ErrorAction SilentlyContinue
    if ($gh) {
        $result = Invoke-Native -FilePath 'gh' -Arguments @('auth', 'token') -Silent -AllowFailure
        if ($result.ExitCode -eq 0 -and $result.Lines.Count -gt 0) {
            $token = ($result.Lines[0]).Trim()
            if ($token) {
                return [pscustomobject]@{ Token = $token; Source = 'gh auth token' }
            }
        }
    }

    throw @"
Nenhum token do GitHub encontrado.

Resolva de uma das formas:
  * gh auth login                      (recomendado — nada mais a configurar)
  * `$env:GH_TOKEN = 'ghp_xxx'          (só para a sessão atual)
  * criar electron-builder.env com GH_TOKEN="ghp_xxx"

O token precisa de escopo 'repo' para criar releases e subir assets.
"@
}

<#
    Compara a versão do Node em execução com o .nvmrc. Só avisa — versões
    diferentes normalmente funcionam, mas explicam builds que só quebram
    em uma das máquinas.
#>
function Test-NodeVersion {
    param([string]$RepoRoot = (Get-RepoRoot))

    $nvmrc = [System.IO.Path]::Combine($RepoRoot, '.nvmrc')
    if (-not (Test-Path -LiteralPath $nvmrc)) { return }

    $expected = (Get-Content -LiteralPath $nvmrc -Raw).Trim()
    if (-not $expected) { return }

    $actual = (Invoke-Native -FilePath 'node' -Arguments @('--version') -Silent).Lines[0].Trim()
    $expectedMajor = ($expected -replace '^v', '').Split('.')[0]
    $actualMajor = ($actual -replace '^v', '').Split('.')[0]

    if ($expectedMajor -ne $actualMajor) {
        Write-Warn "Node em uso: $actual, mas .nvmrc pede $expected."
    }
    else {
        Write-Info "Node $actual (.nvmrc: $expected)"
    }
}

# Escreve UTF-8 sem BOM: o `gh` e o GitHub esperam UTF-8, e o BOM apareceria
# como lixo no início do corpo da release.
function Write-Utf8File {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Content
    )
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}
