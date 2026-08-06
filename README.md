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

```bash
# 1. suba a versão em package.json (semver)
npm version patch   # ou minor / major

# 2. gere GH_TOKEN com escopo "repo" em https://github.com/settings/tokens
export GH_TOKEN=ghp_xxx        # PowerShell: $env:GH_TOKEN = 'ghp_xxx'

# 3. builda e publica o instalador + latest.yml na release do GitHub
npm run release
```

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

Resolução em ordem: `FFMPEG_PATH` → binário de `ffmpeg-static` → `ffmpeg` do PATH.

O install script do `ffmpeg-static` foi bloqueado nesta máquina, então o app usa
o ffmpeg do sistema. Para empacotar com o binário embutido:

```bash
npm install-scripts approve ffmpeg-static
```

## Releases

### Em ambiente linux

```bash
GH_TOKEN=ghp_xxx npm run release;
```

### Em ambiente Windows

```powershell
setx GH_TOKEN ghp_xxx;
npm run release;
```

### Com .env

```powershell
# extrai o GH_TOKEN do .env e exporta para o ambiente
setx GH_TOKEN (Get-Content .env | Select-String -Pattern '^GH_TOKEN="(.+)"$' | ForEach-Object { $_.Matches[0].Groups[1].Value });
npm run release;
```
