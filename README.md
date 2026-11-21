<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="PaçocaOSTV">

# PaçocaOSTV

**Ecossistema completo estilo Apple tvOS 26 · Liquid Glass para Projetores HY300Pro, BTV e Android TV**

Launcher ultra-rápido em 60 fps, Central de Controle Liquid Glass, Player Nativo de Trailers com legendas PT-BR, SmartTube Glass, Volume HUD em vidro e Controles Remotos nativos para iOS e Android.

[Visão Geral](#-visão-geral) · [Galeria de Telas](#-galeria-de-telas) · [Estrutura do Projeto](#-estrutura-unificada) · [Documentação Técnica](#-documentação-técnica) · [Compilação e Instalação](#-compilação-e-instalação) · [Créditos](#-créditos-e-projetos-base)

<br>

<img src="docs/screenshots/btv-home-hero.png" width="900" alt="PaçocaOSTV — Home v2 Hero Spotlight no BTV">

</div>

---

## ✨ Visão Geral

O **PaçocaOSTV** unifica em um único ecossistema open-source tudo o que transforma uma TV Box (**BTV / B11**) ou projetor Android (**Magcubic HY300Pro / Allwinner H713**) em uma experiência fluida inspirada no **Apple tvOS 26 (Liquid Glass)**:

- **PaçocaOSTV Launcher (`app/`)**: Home v2 com rolagem vertical linear unificada por física de mola, Hero 1920×1080 com sinopse real do TMDB, trailers nativos em 720p full-bleed (sem abrir YouTube) com legendas WebVTT exclusivamente em PT-BR, Dock de apps, Continuar Assistindo, Clima, Calendário e Central de Controle em Liquid Glass com ícones **Apple SF Symbols 7**.
- **SmartTube Glass (`modules/smarttube-glass/`)**: Redesign em Jetpack Compose sobre o SmartTube com superfícies Liquid Glass estilo YTXTube, barra superior minimalista (`Buscar · Início · Inscrições · Música · Biblioteca`), Hub Biblioteca, Hero cinematográfico, Player OSD em vidro e 51 ícones reais Apple SF Symbols.
- **Volume HUD Liquid Glass (`modules/volume-hud/`)**: Overlay flutuante de volume estilo Apple TV/iOS com vidro fosco e bordas especulares.
- **Controles Remotos Nativos (`modules/remote-ios/` & `modules/remote-android/`)**: Apps companion em **SwiftUI (iOS)** e **Android** com touchpad, D-Pad, teclado, atalhos de hardware do HY300Pro (keystone, brilho, sleep timer) e conexão direta via ADB/TCP.

---

## 📸 Galeria de Telas

### PaçocaOSTV no Projetor HY300Pro (Allwinner H713 · 1920×1080)

| Home v2 · Hero Spotlight (Projetor HY300Pro) | Central de Controle Liquid Glass (Keystone, Projeção, Brilho & HDMI) |
| :---: | :---: |
| <img src="docs/screenshots/hy300-home-hero.png" width="440" alt="PaçocaOSTV Hero no Projetor HY300Pro"> | <img src="docs/screenshots/hy300-control-center.png" width="440" alt="Central de Controle Liquid Glass no HY300Pro"> |

### PaçocaOSTV no BTV / Android TV (1920×1080)

| Home v2 · Hero Spotlight ("Para você") | Cards de Recomendações |
| :---: | :---: |
| <img src="docs/screenshots/btv-home-hero.png" width="440" alt="Hero Spotlight no BTV"> | <img src="docs/screenshots/btv-home-cards.png" width="440" alt="Cards Para Você no BTV"> |

| Continuar Assistindo & Dock de Apps | Entradas, Atalhos, Clima & Calendário |
| :---: | :---: |
| <img src="docs/screenshots/btv-continue-dock.png" width="440" alt="Continuar Assistindo e Dock"> | <img src="docs/screenshots/btv-home-widgets.png" width="440" alt="Widgets Clima e Calendário"> |

| Central de Controle Liquid Glass (BTV) | Ajustes do PaçocaOSTV (Projeção, Imagem & Início) |
| :---: | :---: |
| <img src="docs/screenshots/btv-control-center.png" width="440" alt="Central de Controle Liquid Glass no BTV"> | <img src="docs/screenshots/pacoca-settings.png" width="440" alt="Ajustes do PaçocaOSTV"> |

### Apple Music Integrado (Interface Completa)

| Início — Destaques & Playlists | Explorar — Novos Lançamentos |
| :---: | :---: |
| <img src="docs/screenshots/pacoca-music.png" width="440" alt="Apple Music Início"> | <img src="docs/screenshots/pacoca-music-explore.png" width="440" alt="Apple Music Explorar"> |

| Rádio & Estações | Biblioteca — Adicionados Recentemente |
| :---: | :---: |
| <img src="docs/screenshots/pacoca-music-radio.png" width="440" alt="Apple Music Rádio"> | <img src="docs/screenshots/pacoca-music-library.png" width="440" alt="Apple Music Biblioteca"> |

| Álbum & Tracklist | Tocando Agora + Letra Sincronizada |
| :---: | :---: |
| <img src="docs/screenshots/pacoca-music-playlist.png" width="440" alt="Apple Music Álbum e Playlists"> | <img src="docs/screenshots/pacoca-music-lyrics.png" width="440" alt="Apple Music Tocando com Letra Sincronizada"> |

### Apple Podcasts Integrado (Interface Completa)

| Início — Novos Episódios & Programas | Página do Programa & Lista de Episódios |
| :---: | :---: |
| <img src="docs/screenshots/pacoca-podcasts-home.png" width="440" alt="Apple Podcasts Início"> | <img src="docs/screenshots/pacoca-podcasts.png" width="440" alt="Apple Podcasts Programa"> |

<p align="center">
  <img src="docs/screenshots/pacoca-podcasts-playing.png" width="640" alt="Apple Podcasts Tocando Agora">
</p>

### SmartTube Glass — YouTube tvOS 26 (Liquid Glass + Apple SF Symbols)

| Rodando ao Vivo na TV — Feed & Barra Liquid Glass | Rodando ao Vivo na TV — Hub Biblioteca SF Symbols |
| :---: | :---: |
| <img src="docs/screenshots/smarttube-glass-tv-home.png" width="440" alt="SmartTube Glass Feed ao Vivo na TV"> | <img src="docs/screenshots/smarttube-glass-tv-library.png" width="440" alt="SmartTube Glass Biblioteca ao Vivo na TV"> |

<p align="center">
  <img src="docs/screenshots/smarttube-glass-home.png" width="640" alt="SmartTube Glass — Hero Cinematográfico e Player OSD">
</p>

### Controles Remotos & Volume HUD

| Controle Remoto Paçoca (iOS SwiftUI & Android) | Apple Liquid Glass Volume HUD |
| :---: | :---: |
| <img src="docs/screenshots/remote-control.png" height="360" alt="Controle Remoto Paçoca"> | <img src="docs/screenshots/volume-hud.png" width="480" alt="Volume HUD Liquid Glass"> |

---

## 📁 Estrutura Unificada

```text
PacocaOS/
├── app/                          # PaçocaOSTV Launcher (Java/Android Canvas 60fps)
│   ├── src/io/freemytv/          # HomeView, Recs, Backdrop, QuickSettingsActivity, Prefs...
│   └── assets/                   # Traduções (pt.json), SF Symbols, ícones e fontes
├── modules/
│   ├── smarttube-glass/          # Redesign Compose (stnew) + 51 SF Symbols + preview HTML
│   ├── volume-hud/               # AppleLiquidGlassVolume (Android overlay + preview)
│   ├── remote-ios/               # Paçoca Remote iOS (SwiftUI + XcodeGen + AltStore)
│   └── remote-android/           # Paçoca Remote Android (companion APK)
├── docs/
│   └── screenshots/              # Capturas reais do BTV, Controles, Volume HUD e SmartTube Glass
├── build.sh                      # Build direto sem Gradle para PacocaOsTV.apk
└── release_ota.sh                # Script de release OTA
```

---

## 🛠️ Documentação Técnica

### 1. PaçocaOSTV Launcher (`app/src/io/freemytv/`)

#### Arquitetura de Renderização (`HomeView.java`, `Backdrop.java`, `QuickSettingsActivity.java`)
- **Canvas nativo sem overhead de ViewGroup**: toda a Home é desenhada em camadas isoladas (`HERO`, `STATUS`, `PLATTER`, `TILES`, `DRAWER`), cada uma com translação `(-getLeft(), -getTop())` em coordenadas lógicas unificadas `1920×1080` escaladas por `k = w / 1920f`.
- **Câmera linear única (`cameraY`)**: um único `Spring` conduz a transição vertical contínua entre:
  1. **Hero 1920×1080** (`y = 0`): abas `Explorar · Filmes · Séries · Animes`, cápsula de status **Liquid Glass** + avatar do perfil, backdrop em tela cheia, título, sinopse TMDB e 3 botões monocromáticos em meia escala (`Trailer/Parar`, `Salvar/Salvo`, `Assistir`).
  2. **Cards "Para você"** (`Z_CARDS`, `cameraY = 720`).
  3. **Continuar Assistindo** (`Z_WATCH`, `cameraY = 1430`).
  4. **Dock de Apps** (`Z_SHELF`, `dockVirtualTop = continueCards.isEmpty() ? 1620f : 2340f`).
  5. **Entradas, Atalhos, Clima & Calendário** (`Z_DRAWER`, cards de clima e calendário com altura dobrada `CARDS_H = 600` para leitura de sofá).
- **Gradiente ambiente por cor dominante (`Backdrop.extractDominantColors`)**: extrai 3 tons dominantes (`dominantTop`, `dominantMid`, `dominantBottom`) do título em foco para alimentar o fundo cinematográfico tanto da Home quanto da Central de Controle (`QuickSettingsActivity`).

#### Recomendações, Cache Ultrarrápido e Sessão Estável (`Recs.java`, `Prefs.java`)
- **Estabilidade de sessão**: as sugestões de cada aba ficam travadas em `tabSnapshot[NT]` durante toda a sessão. O `sessionSlot` só avança quando o usuário fica fora do Paçoca TV por **mais de 1 hora** (`≥ 60 * 60_000L`), eliminando trocas indesejadas enquanto navega ou assiste trailers.
- **Carregamento instantâneo de imagens**:
  - Usa TMDB `w1280` (~100 KB) em vez de `original` 4K (3–8 MB).
  - Grava os bytes diretamente no cache em disco (`fetchFast`) com **zero re-encode na CPU** (sem travar Cortex-A55/Mali-470).
  - Pool paralelo de **6 threads I/O**, cache em memória ampliado para **80 backdrops** + LRU dedicado de **4 bitmaps Hero**.
  - Intervalos de atualização em segundo plano otimizados (`LIST_EVERY = 6h`, `TRAKT_EVERY = 60m`).

#### Player Nativo de Trailers 720p Full-Bleed + Legendas WebVTT PT-BR (`HomeView.java`, `Recs.java`)
- **Zero YouTube / Zero Erro 152-4**: em vez de depender de embeds bloqueados ou abrir o app do YouTube, o botão **Trailer** resolve o `imdb_id` via TMDB (`/{movie|tv}/{id}/external_ids`).
- **Resolução invisível de stream**: uma `WebView` invisível `1×1` (`imdbResolverView`) lê `__NEXT_DATA__` do IMDb, pontua os streams priorizando **`DEF_720p` MP4/HLS** para início rápido e fluidez em hardware de entrada, e imediatamente libera a WebView (`about:blank`).
- **Reprodução nativa no lugar do Hero**: o vídeo roda diretamente na `TextureView` (`trailerTexture`) + `MediaPlayer` em `1920×1080` com center-crop atrás da interface do Hero (sem popup).
- **Legendas estritamente PT-BR**: seleciona apenas faixas `pt-BR` (`pt-br`/`pt_br`/Brasil) em WebVTT; se não existir faixa em português do Brasil, reproduz limpo sem legenda. As legendas são renderizadas em meia escala logo acima dos botões `Parar · Salvar · Assistir`.

---

### 2. SmartTube Glass (`modules/smarttube-glass/`)

- Baseado no fork Compose `stnew` do **SmartTube New**, com camada **Liquid Glass** inspirada no **YTXTube** (`Glass.kt`: fundo translúcido escuro + brilho especular vertical + borda hairline `1.dp` otimizado para 60 fps em Mali-470 e GPUs modernas).
- **Barra superior minimalista**: apenas `[🔍]` (somente ícone) · `Início` · `Inscrições` · `Música` · `Biblioteca` à esquerda, e **uma única bolinha circular em vidro com a foto do perfil Google logado** à direita.
- **Hub Biblioteca**: agrupa todas as demais seções (`Histórico`, `Playlists`, `Shorts`, `Canais`, `Jogos`, `Notícias`, `Configurações`) em cards Liquid Glass com ícones **Apple SF Symbols**.
- **51 ícones reais Apple SF Symbols** gerados nativamente no macOS substituindo todos os ícones do Player OSD e da navegação.
- **Package ID independente** (`io.github.margantcovka.smarttube`): instala lado a lado com o SmartTube oficial sem conflito.

---

### 3. Volume HUD & Controles Remotos (`modules/volume-hud/`, `modules/remote-ios/`, `modules/remote-android/`)

- **AppleLiquidGlassVolume**: serviço de acessibilidade/overlay Android que intercepta eventos de volume e exibe uma cápsula vertical/horizontal Liquid Glass com transição suave.
- **HY300AppleTV-SwiftUI (iOS)** & **HY300Remote-Android**: controle remoto completo via rede local (ADB/TCP) com suporte a múltiplos perfis (HY300Pro `192.168.1.7:5555` e BTV `192.168.1.5:5188`), trackpad gestual, D-Pad, volume, energia e atalhos de projetor.

---

## 🚀 Compilação e Instalação

### Requisitos & Configuração de Chaves / Login pelo Navegador Web
> **Nota de Privacidade:** Nenhum APK pré-compilado, `.keystore` ou token pessoal (`tmdb_key`, `media-user-token` do Apple Music/Podcasts) é versionado no GitHub público. Cada usuário deve compilar seus próprios APKs e autenticar suas contas pela interface web local.

- **JDK 17+**, **Android SDK** (`build-tools 35.0.0`, `platform-tools` / `adb`)
- **Chave TMDB (`tmdb_key`):**
  - **Antes de compilar:** Copie `config.example.properties` para `config.properties` (ignorado pelo `.gitignore`) e adicione sua chave ou Read Access Token do TMDB (`tmdb_key=...`).
  - **Ou pelo navegador web / QR Code na TV (sem recompilar):** No PaçocaOS, abra **Ajustes do Início → Chave TMDB**, escaneie o QR Code (ou acesse `http://<IP_DA_TV>:<PORTA>` no navegador do celular/PC) e cole sua chave.
- **Login no Apple Music & Apple Podcasts (`http://<IP_DA_TV>:8090/applemusic`):**
  1. Com o PaçocaOS aberto na TV/projetor, acesse `http://<IP_DA_TV>:8090/applemusic` no navegador do celular ou computador.
  2. Faça login diretamente com seu **Apple ID (E-mail + Senha)** e confirme o **Código 2FA de 6 dígitos**, **ou** cole o cookie `media-user-token` obtido no navegador em `music.apple.com` no passo **3 (Salvar Token na TV)** para autenticar automaticamente tanto o **Apple Music** quanto o **Apple Podcasts**.

### PaçocaOSTV Launcher (Compilação Local)
```bash
# Linux / macOS:
./build.sh

# Windows (PowerShell):
powershell -ExecutionPolicy Bypass -File .\build.ps1
```
Instalação via ADB:
```bash
adb connect 192.168.1.5:5188
adb -s 192.168.1.5:5188 install -r PacocaOsTV.apk
adb -s 192.168.1.5:5188 shell am force-stop io.freemytv
adb -s 192.168.1.5:5188 shell am start -n io.freemytv/.HomeActivity
```

### Volume HUD Liquid Glass
```bash
cd modules/volume-hud && ./build.sh
adb install -r AppleLiquidGlassVolume.apk
```

### Controle Remoto Android
```bash
cd modules/remote-android && ./build.sh
adb install -r HY300Remote.apk
```

### Controle Remoto iOS (SwiftUI)
```bash
cd modules/remote-ios
xcodegen generate
open *.xcodeproj
```

### SmartTube Glass
No repositório completo de build (`SmartTube-Glass-Build` com os arquivos de `modules/smarttube-glass/stnew/` aplicados):
```bash
./gradlew :smarttubetv:assembleStnewRelease --no-daemon
adb install -r smarttubetv/build/outputs/apk/stnew/release/SmartTube_new_*_armeabi-v7a.apk
```

---

## 🙏 Créditos e Projetos Base

O **PaçocaOSTV** só existe graças ao trabalho incrível da comunidade open-source. Todos os méritos aos criadores originais dos projetos que serviram de base ou inspiração:

| Projeto | Autor / Repositório | Papel no PaçocaOSTV |
| :--- | :--- | :--- |
| **Free My TV** | [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv) | Base arquitetural do launcher nativo em Canvas 60fps para Android TV |
| **SmartTube** | [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) | Motor YouTube open-source sem anúncios, SponsorBlock, 4K/HDR e contas Google |
| **SmartTube New** | [margantcovka/SmartTube-New](https://github.com/margantcovka/SmartTube-New) | Interface Jetpack Compose (`stnew`) otimizada para TVs e TV boxes de entrada |
| **YTXTube** | [Koustubh12345/YTXTube](https://github.com/Koustubh12345/YTXTube) | Referência e inspiração direta para a estética de superfícies **Liquid Glass** |
| **tvOS-Launcher** | [techgenius1/tvOS-Launcher](https://github.com/techgenius1/tvOS-Launcher) | Referência visual de layout e proporções tvOS |
| **TMDB / Trakt / IMDb** | [themoviedb.org](https://www.themoviedb.org/) · [trakt.tv](https://trakt.tv/) · [imdb.com](https://www.imdb.com/) | Metadados, sinopses, recomendações, histórico e trailers nativos |
| **Apple SF Symbols** | Apple Design Resources | Linguagem visual de ícones (SF Symbols) e estética tvOS 26 |

---

<div align="center">
<sub>Feito com carinho para projetores HY300Pro, BTV e Android TV · Licença MIT (respeitando as licenças originais de cada componente).</sub>
</div>
