# LS Bíblia Mobile

Aplicativo Android nativo do LS Bíblia, escrito integralmente em Kotlin.

## Recursos

- Seleção de livro, capítulo, versículos e qualidade do vídeo.
- Download com reaproveitamento de arquivos em cache.
- Gerenciador de cache para catálogos, vídeos e downloads incompletos.
- Editor com linha do tempo, cortes por versículo, câmera lenta e zoom animado.
- Faixas editáveis diretamente: arraste no vazio para criar, o meio para mover e as bordas para redimensionar.
- Enquadramento direto no vídeo: pinça com dois dedos para ampliar e arrasto com um dedo para reposicionar.
- Exportação H.264 para `Filmes/LS Bíblia`, com opções para assistir e compartilhar.
- Layout protegido pelas barras de status e navegação do Android.

## Executar no aparelho conectado

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug installDebug
adb shell am force-stop com.saulocosta.lsbiblia
adb shell monkey -p com.saulocosta.lsbiblia -c android.intent.category.LAUNCHER 1
```

Requisitos: JDK 17, Android SDK e um dispositivo Android 7.0 ou mais recente.
