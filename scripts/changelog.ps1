#Requires -Version 5.1
<#
.SYNOPSIS
    Monta o changelog em Markdown a partir dos commits desde a versão anterior.

.DESCRIPTION
    Agrupa os commits pelos tipos do padrão do projeto (.github/commit.md).
    Commits fora do padrão vão para "Outras mudanças" — nada é descartado
    silenciosamente, exceto o commit de bump gerado pelo `npm version`, cuja
    mensagem é só o número da versão.

    Sem -OutFile o Markdown é impresso na tela, o que serve para conferir as
    notas antes de publicar (`npm run release:notes`).

.PARAMETER Tag
    Tag sendo publicada. Padrão: "v" + a versão do package.json.

.PARAMETER From
    Tag inicial do intervalo. Padrão: a maior tag existente que não seja -Tag.

.PARAMETER OutFile
    Caminho para gravar o Markdown. Sem isso, escreve na saída padrão.

.EXAMPLE
    .\scripts\changelog.ps1
    .\scripts\changelog.ps1 -From v1.0.0 -OutFile dist\release-notes.md
#>
[CmdletBinding()]
param(
    [string]$Tag,
    [string]$From,
    [string]$OutFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. ([System.IO.Path]::Combine($PSScriptRoot, 'common.ps1'))

$repoRoot = Get-RepoRoot
$previousEncoding = Set-Utf8Console
Push-Location $repoRoot
try {
    if (-not $Tag) {
        $Tag = 'v' + (Get-PackageJson -RepoRoot $repoRoot).version
    }

    # ---------------------------------------------------------------------
    # Intervalo de commits
    # ---------------------------------------------------------------------
    if (-not $From) {
        # A maior tag por ordem de versão que não seja a que está sendo
        # publicada. `-v:refname` ordena semanticamente (v1.10.0 > v1.9.0),
        # coisa que a ordem alfabética erraria.
        $tags = @(
            (Invoke-Git -Arguments @('tag', '--list', 'v*', '--sort=-v:refname') -Silent).Lines |
                Where-Object { $_ -and $_.Trim() -ne $Tag }
        )
        if ($tags.Count -gt 0) { $From = $tags[0].Trim() }
    }

    if ($From) {
        $range = "$From..HEAD"
        Write-Host "    Commits de $From até $Tag" -ForegroundColor DarkGray
    }
    else {
        $range = 'HEAD'
        Write-Host "    Primeira release: usando todo o histórico" -ForegroundColor DarkGray
    }

    # ---------------------------------------------------------------------
    # Coleta dos commits (assunto + hash curto, separados por TAB)
    # ---------------------------------------------------------------------
    $log = (Invoke-Git -Arguments @('log', '--no-merges', '--reverse', '--format=%s%x09%h', $range) -Silent).Lines

    $commits = @()
    foreach ($line in $log) {
        if (-not $line) { continue }
        $parts = $line -split "`t", 2
        if ($parts.Count -lt 2) { continue }
        $commits += [pscustomobject]@{ Subject = $parts[0].Trim(); Hash = $parts[1].Trim() }
    }

    # ---------------------------------------------------------------------
    # Agrupamento
    # ---------------------------------------------------------------------
    $sections = @(
        [pscustomobject]@{ Title = '✨ Novidades';           Types = @('feat') }
        [pscustomobject]@{ Title = '🐛 Correções';           Types = @('fix') }
        [pscustomobject]@{ Title = '♻️ Melhorias internas';  Types = @('perf', 'refactor') }
        [pscustomobject]@{ Title = '📚 Documentação';        Types = @('docs') }
        [pscustomobject]@{ Title = '🧹 Manutenção';          Types = @('test', 'chore', 'build', 'ci', 'style', 'revert') }
    )

    $knownTypes = @($sections | ForEach-Object { $_.Types } | Sort-Object -Unique)
    # Casa "tipo:", "tipo(escopo):" e "tipo!:" (breaking change).
    $knownPattern = "^($($knownTypes -join '|'))(\([^)]*\))?!?:\s*"
    # Mensagem que o `npm version` gera: apenas o numero da versao.
    $bumpPattern = '^v?\d+\.\d+\.\d+$'

    $blocks = @()

    foreach ($section in $sections) {
        $pattern = "^($($section.Types -join '|'))(\([^)]*\))?!?:\s*"
        $items = @(
            $commits |
                Where-Object { $_.Subject -match $pattern } |
                ForEach-Object { "- $($_.Subject -replace $pattern, '') ($($_.Hash))" }
        )
        if ($items.Count -gt 0) {
            $blocks += "### $($section.Title)`n`n" + ($items -join "`n")
        }
    }

    $others = @(
        $commits |
            Where-Object { $_.Subject -notmatch $knownPattern -and $_.Subject -notmatch $bumpPattern } |
            ForEach-Object { "- $($_.Subject) ($($_.Hash))" }
    )
    if ($others.Count -gt 0) {
        $blocks += "### 📦 Outras mudanças`n`n" + ($others -join "`n")
    }

    if ($blocks.Count -gt 0) {
        $markdown = ($blocks -join "`n`n") + "`n"
    }
    else {
        $markdown = "_Sem commits novos desde a versão anterior._`n"
    }

    # ---------------------------------------------------------------------
    # Saída
    # ---------------------------------------------------------------------
    if ($OutFile) {
        $full = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($repoRoot, $OutFile))
        $dir = [System.IO.Path]::GetDirectoryName($full)
        if (-not (Test-Path -LiteralPath $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
        Write-Utf8File -Path $full -Content $markdown
        Write-Host "    Changelog gravado em $full" -ForegroundColor DarkGray
    }
    else {
        Write-Output $markdown
    }
}
finally {
    Pop-Location
    Restore-ConsoleEncoding -Encoding $previousEncoding
}
