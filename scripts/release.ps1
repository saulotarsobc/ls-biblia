#Requires -Version 5.1
<#
.SYNOPSIS
    Publica uma nova versão do LS Bíblia no GitHub Releases, da máquina local.

.DESCRIPTION
    Faz, em ordem: verificações de ambiente, bump opcional da versão, testes,
    criação/atualização da tag, changelog, criação da Release e build +
    upload dos assets pelo electron-builder.

    Todas as etapas são idempotentes: rodar de novo com a mesma versão não
    duplica tag nem release, e os assets são substituídos.

.PARAMETER Bump
    Sobe a versão antes de publicar (`npm version patch|minor|major`).
    Sem isso, publica a versão que já está no package.json.

.PARAMETER SkipTests
    Pula `npm test`. Use só quando souber o que está fazendo.

.PARAMETER SkipVerify
    Pula a checagem final que baixa o latest.yml publicado sem autenticação.

.PARAMETER DryRun
    Mostra tudo o que seria feito sem criar tag, release ou publicar nada.

.PARAMETER Force
    Permite publicar com alterações não commitadas e mover uma tag que já
    aponte para outro commit.

.EXAMPLE
    npm run release          # publica a versão que está no package.json
    npm run release:dry      # simula tudo, sem alterar nada
    npm run release:notes    # só mostra o changelog que seria usado

    # Para passar parâmetros, chame o script direto — o npm 12 não repassa
    # flags de traço simples:
    pwsh .\scripts\release.ps1 -Bump patch
    pwsh .\scripts\release.ps1 -SkipTests -Force
#>
[CmdletBinding()]
param(
    [ValidateSet('patch', 'minor', 'major')]
    [string]$Bump,
    [switch]$SkipTests,
    [switch]$SkipVerify,
    [switch]$DryRun,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. ([System.IO.Path]::Combine($PSScriptRoot, 'common.ps1'))

$repoRoot = Get-RepoRoot
$previousEncoding = Set-Utf8Console
Push-Location $repoRoot

try {
    if ($DryRun) {
        Write-Host ""
        Write-Host "  MODO DRY-RUN — nada será criado nem publicado." -ForegroundColor Yellow
    }

    # =======================================================================
    # 1. Ambiente
    # =======================================================================
    Write-Step '1/8  Verificando o ambiente'

    Assert-Command -Name 'git'  -Hint 'Instale o Git: https://git-scm.com/downloads'
    Assert-Command -Name 'node' -Hint 'Instale o Node.js: https://nodejs.org'
    Assert-Command -Name 'npm'  -Hint 'O npm vem junto com o Node.js.'
    Assert-Command -Name 'gh'   -Hint 'Instale o GitHub CLI: winget install GitHub.cli'

    Test-NodeVersion -RepoRoot $repoRoot

    $branch = (Invoke-Git -Arguments @('rev-parse', '--abbrev-ref', 'HEAD') -Silent).Lines[0].Trim()
    Write-Info "Branch atual: $branch"

    # Publicar com a árvore suja significa gerar um instalador que não
    # corresponde a nenhum commit — a tag apontaria para um código diferente
    # do que foi empacotado.
    $dirty = @((Invoke-Git -Arguments @('status', '--porcelain') -Silent).Lines | Where-Object { $_.Trim() })
    if ($dirty.Count -gt 0) {
        if ($Force -or $DryRun) {
            Write-Warn "Há $($dirty.Count) alteração(ões) não commitada(s) — seguindo mesmo assim."
        }
        else {
            throw "Há alterações não commitadas. Commite antes de publicar (ou use -Force)."
        }
    }
    else {
        Write-Ok 'Árvore de trabalho limpa.'
    }

    $tokenInfo = Resolve-GitHubToken -RepoRoot $repoRoot
    Write-Ok "Token do GitHub obtido de: $($tokenInfo.Source)"

    # O electron-builder lê GH_TOKEN do ambiente; o gh também. Definir aqui
    # evita ter que exportar a variável manualmente antes de cada release.
    $env:GH_TOKEN = $tokenInfo.Token

    $target = Get-PublishTarget -PackageJson (Get-PackageJson -RepoRoot $repoRoot)
    Write-Info "Repositório de publicação: $($target.Owner)/$($target.Repo)"

    # =======================================================================
    # 2. Versão
    # =======================================================================
    Write-Step '2/8  Definindo a versão'

    if ($Bump) {
        if ($DryRun) {
            Write-Info "[dry-run] npm version $Bump"
        }
        else {
            # -m usa o padrão de commit do projeto (.github/commit.md).
            # O npm já cria o commit e a tag v<versão>.
            Invoke-Native -FilePath 'npm' -Arguments @('version', $Bump, '-m', 'chore: versão %s') | Out-Null
        }
    }

    $version = (Get-PackageJson -RepoRoot $repoRoot).version
    $tag = "v$version"
    Write-Ok "Versão a publicar: $version (tag $tag)"

    # =======================================================================
    # 3. Testes
    # =======================================================================
    Write-Step '3/8  Rodando os testes'

    if ($SkipTests) {
        Write-Warn 'Pulado por -SkipTests.'
    }
    elseif ($DryRun) {
        Write-Info '[dry-run] npm test'
    }
    else {
        Invoke-Native -FilePath 'npm' -Arguments @('test') | Out-Null
        Write-Ok 'Testes passaram.'
    }

    # =======================================================================
    # 4. Commits pendentes
    # =======================================================================
    Write-Step '4/8  Sincronizando a branch com o remoto'

    $upstream = Invoke-Git -Arguments @('rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}') -Silent -AllowFailure
    if ($upstream.ExitCode -ne 0) {
        Write-Warn "A branch '$branch' não tem upstream configurado; só a tag será enviada."
    }
    else {
        $ahead = [int]((Invoke-Git -Arguments @('rev-list', '--count', '@{u}..HEAD') -Silent).Lines[0].Trim())
        if ($ahead -gt 0) {
            if ($DryRun) {
                Write-Info "[dry-run] git push origin $branch  ($ahead commit(s) à frente)"
            }
            else {
                Write-Info "$ahead commit(s) à frente do remoto; enviando..."
                Invoke-Git -Arguments @('push', 'origin', $branch) | Out-Null
                Write-Ok 'Branch sincronizada.'
            }
        }
        else {
            Write-Ok 'Branch já está sincronizada.'
        }
    }

    # =======================================================================
    # 5. Tag
    # =======================================================================
    Write-Step "5/8  Preparando a tag $tag"

    $head = (Invoke-Git -Arguments @('rev-parse', 'HEAD') -Silent).Lines[0].Trim()
    $tagExists = (Invoke-Git -Arguments @('rev-parse', '-q', '--verify', "refs/tags/$tag") -Silent -AllowFailure).ExitCode -eq 0

    if ($tagExists) {
        $tagCommit = (Invoke-Git -Arguments @('rev-list', '-n', '1', $tag) -Silent).Lines[0].Trim()
        if ($tagCommit -eq $head) {
            Write-Ok "A tag $tag já existe e aponta para este commit."
        }
        elseif ($Force -or $DryRun) {
            Write-Warn "A tag $tag aponta para outro commit e será movida para $($head.Substring(0,7))."
            if (-not $DryRun) {
                Invoke-Git -Arguments @('tag', '-f', '-a', $tag, '-m', $tag) | Out-Null
                Invoke-Git -Arguments @('push', '--force', 'origin', "refs/tags/$tag") | Out-Null
            }
        }
        else {
            throw "A tag $tag já existe apontando para outro commit ($($tagCommit.Substring(0,7))). Use -Force para movê-la ou suba a versão com -Bump."
        }
    }
    else {
        if ($DryRun) {
            Write-Info "[dry-run] git tag -a $tag && git push origin refs/tags/$tag"
        }
        else {
            Invoke-Git -Arguments @('tag', '-a', $tag, '-m', $tag) | Out-Null
            Write-Ok "Tag $tag criada."
        }
    }

    # A tag pode existir localmente e não no remoto (ex.: criada pelo
    # `npm version` numa execução anterior que falhou depois).
    if (-not $DryRun) {
        Invoke-Git -Arguments @('push', 'origin', "refs/tags/$tag") -AllowFailure | Out-Null
        Write-Ok "Tag $tag presente no remoto."
    }

    # =======================================================================
    # 6. Changelog
    # =======================================================================
    Write-Step '6/8  Gerando o changelog'

    # dist/ está no .gitignore, então o arquivo não polui o repositório.
    $notesFile = [System.IO.Path]::Combine($repoRoot, 'dist', 'release-notes.md')
    & ([System.IO.Path]::Combine($PSScriptRoot, 'changelog.ps1')) -Tag $tag -OutFile $notesFile

    Write-Host ''
    Write-Host (Get-Content -LiteralPath $notesFile -Raw -Encoding UTF8)

    # =======================================================================
    # 7. Release no GitHub
    # =======================================================================
    Write-Step "7/8  Preparando a Release $tag"

    $exists = (Invoke-Native -FilePath 'gh' -Arguments @('release', 'view', $tag, '--repo', "$($target.Owner)/$($target.Repo)") -Silent -AllowFailure).ExitCode -eq 0

    if ($exists) {
        Write-Ok "A Release $tag já existe — o corpo atual foi preservado."
    }
    elseif ($DryRun) {
        Write-Info "[dry-run] gh release create $tag --draft --notes-file dist\release-notes.md --generate-notes"
    }
    else {
        # --generate-notes acrescenta as notas automáticas do GitHub (PRs e o
        # link "Full Changelog") abaixo do nosso changelog: a API pré-anexa o
        # corpo informado ao texto gerado.
        # --verify-tag aborta se a tag não chegou ao remoto, evitando que a
        # release seja criada num commit qualquer da branch padrão.
        Invoke-Native -FilePath 'gh' -Arguments @(
            'release', 'create', $tag,
            '--repo', "$($target.Owner)/$($target.Repo)",
            '--title', $tag,
            '--notes-file', $notesFile,
            '--generate-notes',
            '--draft',
            '--verify-tag'
        ) | Out-Null
        Write-Ok "Release $tag criada como rascunho até os assets serem validados."
    }

    # =======================================================================
    # 8. Build e upload dos assets
    # =======================================================================
    Write-Step '8/8  Build e publicação dos assets'

    if ($DryRun) {
        Write-Info '[dry-run] npm run release:publish'
    }
    else {
        # release:publish começa removendo dist/ e out/. Assim o instalador,
        # o blockmap e o latest.yml pertencem sempre à mesma build.
        # EP_GH_IGNORE_TIME é essencial: sem ela o electron-builder se recusa
        # a subir assets numa release publicada há mais de 2 horas e encerra
        # com sucesso, apenas logando um aviso — a publicação falharia em
        # silêncio ao reexecutar o script.
        $env:EP_GH_IGNORE_TIME = 'true'
        try {
            Invoke-Native -FilePath 'npm' -Arguments @('run', 'release:publish') | Out-Null
        }
        finally {
            Remove-Item Env:\EP_GH_IGNORE_TIME -ErrorAction SilentlyContinue
        }

        $artifacts = @(Get-ReleaseArtifacts -RepoRoot $repoRoot -Version $version)
        Assert-GitHubReleaseAssets `
            -Repository "$($target.Owner)/$($target.Repo)" `
            -Tag $tag `
            -ExpectedArtifacts $artifacts
        Write-Ok 'Instalador, blockmap e latest.yml enviados e conferidos.'

        # Só agora a versão fica visível no endpoint /releases/latest. Isso
        # impede que o auto-update encontre uma Release ainda incompleta e
        # não depende da heurística automática do GitHub para escolher Latest.
        Invoke-Native -FilePath 'gh' -Arguments @(
            'release', 'edit', $tag,
            '--repo', "$($target.Owner)/$($target.Repo)",
            '--draft=false',
            '--latest'
        ) | Out-Null
        Write-Ok "Release $tag publicada e marcada explicitamente como Latest."
    }

    # =======================================================================
    # Verificação final
    # =======================================================================
    if (-not $DryRun -and -not $SkipVerify) {
        Write-Step 'Verificando a cadeia de auto-update'

        # É exatamente o que o electron-updater faz na máquina do usuário:
        # baixa o latest.yml SEM autenticação. Se a release estiver como
        # rascunho ou o repositório for privado, isso dá 404 e o update
        # nunca chega a ninguém.
        $url = "https://github.com/$($target.Owner)/$($target.Repo)/releases/latest/download/latest.yml"
        $ok = $false
        $lastVerificationError = 'erro desconhecido'
        foreach ($attempt in 1..5) {
            try {
                $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30

                # Assets de release são servidos como application/octet-stream,
                # então .Content vem como byte[]. Um -match sobre array filtra
                # os elementos em vez de casar o padrão, e $Matches nunca é
                # preenchido — daí a versão sair vazia.
                $yaml = if ($response.Content -is [byte[]]) {
                    [System.Text.Encoding]::UTF8.GetString($response.Content)
                }
                else {
                    [string]$response.Content
                }

                $published = ''
                if ($yaml -match '(?m)^version:\s*(.+)$') {
                    $published = $Matches[1].Trim()
                }
                if ($published -eq $version) {
                    Write-Ok "latest.yml acessível e apontando para $published."
                    $ok = $true
                }
                else {
                    throw "latest.yml público aponta para '$published' (esperada: $version)."
                }
                break
            }
            catch {
                $lastVerificationError = $_.Exception.Message
                if ($attempt -lt 5) {
                    Write-Info "Tentativa $attempt falhou; aguardando a propagação do GitHub..."
                    Start-Sleep -Seconds 5
                }
            }
        }
        if (-not $ok) {
            throw "A verificação pública do auto-update falhou: $lastVerificationError`n$url"
        }
    }

    Write-Host ''
    if ($DryRun) {
        Write-Host "  Dry-run concluído. Nada foi alterado." -ForegroundColor Yellow
    }
    else {
        Write-Host "  Versão $version publicada." -ForegroundColor Green
        Write-Host "  https://github.com/$($target.Owner)/$($target.Repo)/releases/tag/$tag" -ForegroundColor Green
    }
    Write-Host ''
}
catch {
    Write-Host ''
    Write-Host "  FALHOU: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ''
    exit 1
}
finally {
    Pop-Location
    Restore-ConsoleEncoding -Encoding $previousEncoding
}
