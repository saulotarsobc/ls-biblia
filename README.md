# Bíblia LSB

App Electron para baixar capítulos da Bíblia em Língua de Sinais Brasileira e
montar um vídeo de estudo: escolher livro → capítulo → versículos → qualidade →
editar (câmera lenta e zoom) → exportar.

```bash
npm install
npm run dev      # desenvolvimento
npm run build    # type-check + bundle
npm run dist     # instalador Windows
```

## Como funciona

A API pública `GETPUBMEDIALINKS` responde, numa única requisição por livro, com
todos os capítulos, as quatro qualidades e os marcadores de versículo:

```
https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS
  ?pub=nwt&langwritten=LSB&booknum=1&output=json&fileformat=MP4
```

Detalhes dessa API que moldaram o código:

- **Os `markers` só existem no arquivo 720p.** As durações são idênticas entre as
  qualidades, então os tempos de versículo são lidos do 720p e valem para o
  arquivo que o usuário escolher (`src/main/jw.ts`).
- **Os MP4 não têm faixa de áudio** — são só vídeo H.264, mais uma capa PNG de
  600×600 embutida como segunda stream de vídeo. Por isso todo mapeamento usa
  `:v:0` explícito: sem isso o ffmpeg pode eleger o PNG como "melhor" stream.
- **Nem todo livro existe em LSB.** Levítico (booknum 3) responde 404 hoje. A
  disponibilidade é verificada em tempo de execução, nunca fixada no código.
- A numeração é a bíblica padrão (19 = Salmos).

O catálogo fica em cache por 7 dias em `userData/catalogo`, e os vídeos em
`userData/videos`.

## Edição e exportação

O editor **não renderiza arquivo intermediário**. O preview toca o MP4 original e
converte posição na hora, via `src/shared/timeline.ts`:

- `editToSource` / `sourceToEdit` traduzem entre a linha do tempo cortada e o
  arquivo de origem, pulando os trechos não selecionados durante a reprodução.
- `buildAtoms` fatia tudo em **átomos**: trechos contínuos da origem com uma única
  velocidade. Os cortes acontecem tanto nas bordas dos versículos quanto nas
  bordas das regiões de câmera lenta, então uma região lenta pode atravessar
  vários versículos.

A exportação é **um único passe de ffmpeg** direto do original — corte,
velocidade e zoom juntos, uma só geração de recompressão. Cada átomo entra como
um input próprio com `-ss` antes do `-i` (busca rápida), em vez de `split`+`trim`,
que faria o ffmpeg bufferizar na RAM os frames de trechos distantes.

Versículos contíguos viram um corte só: escolher 3 a 10 gera um trecho, não oito.

### O que os testes com ffmpeg revelaram

O zoom animado passou por várias tentativas antes de funcionar
(`src/main/export.ts` documenta cada uma):

- **`crop` não serve.** As expressões de `w`/`h` não enxergam o tempo, porque o
  tamanho de saída do filtro precisa ser fixo na inicialização.
- A variável de tempo do `zoompan` chama **`time`**, não `t`.
- **O `zoompan` limita `zoom` ao mínimo de 1.0.** Fazer supersampling dividindo o
  zoom por 2 é silenciosamente clampado, e o zoom congela sem erro nenhum. O jeito
  certo é ampliar também o `s` e reduzir a imagem no fim.
- O `s` do `zoompan` é um **recorte** da imagem ampliada, então a janela visível
  mede `ow/zoom` — não `iw/zoom`, que só coincide quando a saída tem o mesmo
  tamanho da entrada.
- O `zoompan` recarimba os PTS num timebase próprio. Aplicar `setpts` depois dele
  produziu 5120 s a partir de um clipe de 5 s. A timeline é reconstruída a partir
  do índice do frame (`settb=AVTB,setpts=N/fps/TB*fator`).

Sem zoom, o caminho rápido usa só `setpts` e pula tudo isso.

## Testes

```bash
node src/shared/__tests__/timeline.test.ts   # 28 asserções, sem dependências
node src/main/__tests__/export.e2e.ts        # render real (precisa de gen1.mp4)
```

Um dos testes avalia em JS a expressão gerada para o ffmpeg e compara com o
`zoomAt` usado no preview — é o que garante que o editor mostra exatamente o que
vai ser renderizado, inclusive dentro de trechos em câmera lenta.

## Auto-update

O app usa `electron-updater` publicando releases no GitHub
(`saulotarsobc/ls-biblia`). A cada abertura (fora do `npm run dev`), ele
consulta a última release, baixa em segundo plano e mostra uma faixa no
rodapé pedindo para reiniciar e instalar — sem o usuário precisar baixar nada
manualmente. Se ele ignorar o botão, a atualização é aplicada sozinha no
próximo fechamento do app (`autoInstallOnAppQuit`).

Para publicar uma nova versão:

```powershell
# sobe a versão, roda os testes, cria tag + release e publica os assets
pwsh .\scripts\release.ps1 -Bump patch   # ou minor / major
```

Detalhes do script em [Releases](#releases).

O `electron-builder` sobe o instalador NSIS e o `latest.yml` — este último é o
arquivo que o `electron-updater` lê para detectar versão nova.

### Duas condições sem as quais o update nunca aparece

O `electron-updater` roda na máquina do usuário e consulta o GitHub **sem
autenticação nenhuma**. Se a release não for legível por um anônimo, ele leva
404, dispara o evento de erro e o app segue na versão velha em silêncio. Então:

1. **A release precisa estar publicada, não em rascunho.** O padrão do
   `electron-builder` é criar como _draft_, e `/releases/latest` responde 404
   para rascunhos. Por isso o `publish` do `package.json` fixa
   `"releaseType": "release"`. Para publicar uma que ficou pra trás:

   ```bash
   gh release edit v1.0.0 --repo saulotarsobc/ls-biblia --draft=false
   ```

2. **O repositório precisa ser público.** Num repo privado a API responde 404
   para qualquer requisição não autenticada, e não há saída boa: a opção
   `private: true` do electron-updater embute um `GH_TOKEN` no `app-update.yml`
   dentro do asar, extraível por qualquer usuário. Se um dia o código precisar
   voltar a ser privado, aponte o `publish.repo` para um repositório público
   separado só de releases.

Para conferir que a cadeia está de pé — é exatamente o que o app faz:

```bash
curl -s https://api.github.com/repos/saulotarsobc/ls-biblia/releases/latest | grep tag_name
curl -sL https://github.com/saulotarsobc/ls-biblia/releases/latest/download/latest.yml
```

## ffmpeg

Resolução em ordem: `FFMPEG_PATH` → `resources/bin/ffmpeg.exe` → binário de
`ffmpeg-static` (desenvolvimento) → `ffmpeg` do PATH.

O `electron-builder` copia explicitamente `ffmpeg.exe` e `ffprobe.exe` para
`resources/bin` através de `extraResources`. Assim, o aplicativo instalado não
depende de uma instalação de ffmpeg no sistema. Se o install script do
`ffmpeg-static` estiver bloqueado antes do build, libere-o com:

```bash
npm install-scripts approve ffmpeg-static
```

## Releases

A publicação é feita da máquina local por scripts PowerShell em [`scripts/`](scripts).
Não há CI: o que sai na release é exatamente o que foi buildado aqui.

```powershell
npm run release          # publica a versão que está no package.json
npm run release:dry      # simula tudo, sem criar tag, release nem assets
npm run release:notes    # só imprime o changelog que seria usado
```

Para passar parâmetros, chame o script direto — o npm não repassa flags de
traço simples:

```powershell
pwsh .\scripts\release.ps1 -Bump patch     # npm version + publicação
pwsh .\scripts\release.ps1 -SkipTests      # pula npm test
pwsh .\scripts\release.ps1 -Force          # aceita árvore suja / move tag existente
pwsh .\scripts\release.ps1 -SkipVerify     # pula a checagem do latest.yml
```

### O que o `release.ps1` faz

1. Confere `git`, `node`, `npm`, `gh`, a versão do Node contra o `.nvmrc` e se
   a árvore está limpa.
2. Sobe a versão com `npm version`, se `-Bump` for passado.
3. Roda `npm test`.
4. Envia os commits pendentes da branch atual.
5. Cria (ou mova, com `-Force`) a tag `v<versão>` e a envia para o remoto.
6. Monta o changelog agrupando os commits pelos tipos de
   [`.github/commit.md`](.github/commit.md) — o mesmo texto sai em
   `dist/release-notes.md`.
7. Cria a Release no GitHub com esse changelog mais as notas automáticas do
   GitHub. Se a release já existir, o corpo é preservado.
8. Roda `npm run release:publish` (build + `electron-builder --publish always`),
   que sobe `latest.yml`, o instalador NSIS e o `.blockmap`.
9. Baixa o `latest.yml` publicado **sem autenticação** e confere a versão — é
   exatamente o que o `electron-updater` faz na máquina do usuário, então isso
   pega na hora os dois problemas descritos acima (release em rascunho ou
   repositório privado).

Todas as etapas são idempotentes: rodar de novo com a mesma versão não duplica
tag nem release, e o electron-builder substitui os assets já existentes.

### Token do GitHub

O script procura o token nesta ordem, e para no primeiro que encontrar:

1. variável de ambiente `GH_TOKEN` ou `GITHUB_TOKEN`;
2. `electron-builder.env` ou `.env` (ambos no `.gitignore`);
3. `gh auth token`, se você já tiver feito `gh auth login`.

Ou seja: com o `gh` autenticado, não é preciso configurar nada.
