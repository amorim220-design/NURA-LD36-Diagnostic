# NURA LD36 v0.6.0 — corrigir rotação após abrir jogos

**Sintoma real:** v0.4 mostra imagem correta se inicia em horizontal, mas se um aplicativo gira o celular depois de começar a transmissão, a TV pode mostrar um pedaço ou recorte da tela. O modo de recorte GPU da v0.5 também apresentou travamentos ou distorções no teste real.

## Alterações

- A Activity aceita mudanças de orientação sem ser destruída, preservando USB AOA, MediaProjection e MediaCodec.
- Novo modo **AUTO — acompanha rotação, sem cortes** (seleção inicial recomendada). A saída USB H.264 mantém uma superfície 1280×720 enquanto a captura nativa do telefone muda entre horizontal e vertical.
- `MediaProjection.Callback.onCapturedContentResize` atualiza a textura, o enquadramento da GPU e redimensiona o **mesmo** `VirtualDisplay`, sem criar uma segunda sessão.
- Quando a tela do celular fica na vertical, o modo AUTO usa barras laterais (pillarbox) para preservar conteúdo; na horizontal, preenche proporcionalmente sem cortar.
- **Original (v0.4)** permanece disponível como fallback seguro. Se girar a tela nesse modo, encerre e reinicie o teste.
- **Preencher TV** permanece experimental e pode cortar conteúdo lateral, devido à proporção.
- Logs de redimensionamento no arquivo TXT ajudam diagnosticar se o Android notificou cada mudança.

## Teste em hardware

1. Feche o Mirror original. Instale v0.6.
2. Deixe o telefone na vertical. Selecione **AUTO** e toque em **ESPELHAR VÍDEO (TESTE 90s)**; autorize a captura.
3. Abra o mesmo aplicativo/jogo que muda a orientação; gire o telefone para a horizontal **sem reiniciar a captura**.
4. Confira se a imagem passa a ocupar a largura da TV corretamente, sem recortes. Volte para a vertical e repita.
5. Se houver erro, pressione Parar, selecione **Original** e inicie somente depois de girar para a posição desejada. Envie TXT/RX/TX da sessão com falha.

**Esta atualização foi compilada/testada em CI, mas a correção de rotação depende de validação física no adaptador LD36.** A versão continua sem envio de áudio. Não confundir a atualização do tamanho do VirtualDisplay com uma garantia de que a TV mostrará o quadro corretamente.

---

# NURA LD36 v0.5 — ajuste de preenchimento sem distorção

Depois de o usuário comprovar que o vídeo H.264 apareceu corretamente na TV com **pequenas bordas superior e inferior no modo horizontal**, a v0.5 preserva o modo Original da v0.4 e acrescenta um modo experimental de **Preencher TV sem esticar**.

## Como testar

1. Instale a v0.5 e feche o Mirror original. Gire o celular para a horizontal **antes** de iniciar.
2. Selecione **Preencher TV (sem distorcer)** no seletor AJUSTE NA TV e toque ESPELHAR VÍDEO (TESTE 90s).
3. Autorize a captura pelo Android e a conexão AOA USB. Compare a imagem na TV com a v0.4.
4. Se ocorrer tela preta, artefato de imagem, cortes indevidos ou travamento, pressione Parar, selecione **Original (sem cortes)** e repita. O modo Original continua usando o caminho direto já validado na v0.4.
5. Exporte TXT, RX e TX da sessão para análise. A captura TX em disco continua limitada a 32 MiB, sem limitar a transmissão real.

**Implementação:** o modo Preencher captura a tela na resolução nativa que o Android reporta, usa `SurfaceTexture` + OpenGL ES 2.0 em uma thread de GPU para realizar **recorte central proporcional**, e alimenta a superfície do encoder H.264 em 1280×720 na horizontal. Preserva os pixels sem esticamento, mas pode cortar conteúdo nas laterais se o celular for mais largo que a TV. O modo Original não executa a GPU extra. O serviço mantém a notificação persistente de captura. A seleção é armazenada no próprio app, e vale para a próxima sessão.

**Ainda experimental no hardware:** a v0.5 passou por revisão de código/testes e requer observação na TV; a funcionalidade nova não foi validada por captura real até o usuário instalá-la. Sem áudio nesta versão.

---

# NURA LD36 Diagnostic v0.4.0 — espelhamento H.264 experimental

Este APK acrescenta um modo experimental de captura de tela e vídeo USB/AOA. A versão atual **ainda não foi validada no adaptador físico após adicionar o vídeo**.

## Teste de vídeo — procedimento

1. Feche o aplicativo Mirror original.
2. Conecte o LD36 à TV (HDMI), alimentação e USB-C ao telefone.
3. Abra NURA LD36 Diagnostic **v0.4.0** e toque em **ESPELHAR VÍDEO (TESTE 90s)**.
4. Autorize o aviso do Android para capturar a tela. Depois, conceda acesso USB ao LD36 se solicitado.
5. O aplicativo responde ao handshake SYNC/STAR já capturado fisicamente. **Somente depois de STAR** ele inicia um serviço de primeiro plano com MediaProjection, MediaCodec AVC, VirtualDisplay e transmite pacotes MIRR/VDAT com vídeo H.264 Annex-B.
6. Abra uma tela simples no celular; verifique se aparece imagem na TV. A captura tem notificação persistente enquanto ativa.
7. Toque **Parar** para encerrar antes dos 90 segundos ou deixe terminar. Exporte TXT/RX/TX. O arquivo TX é limitado a 32 MiB de captura, embora o envio USB continue.
8. Os controles de varredura passiva e negociação ativa anteriores continuam disponíveis.

**Limites conhecidos:** não transmite áudio; não faz controle de toque remoto; a codificação pode falhar em dispositivos sem H.264 Surface encoder; a TV pode não mostrar vídeo se parâmetros de inicialização, resoluções, áudio ou formatos diferirem do aplicativo original. Isso é um experimento, não uma garantia de reprodução. O serviço de captura só inicia com consentimento expresso do Android, e encerra ao parar, em falha de captura ou desligamento USB.

### Fluxo técnico
- AOA RX: `StreamProbe` + `ActiveProtocolSession` montam mensagens mesmo fragmentadas.
- Handshake: heartbeat ecoado, SYNC 25→28, APP_SYNC, STAR 32→48.
- A autorização do Android inicia `MirrorProjectionService` **apenas ao receber STAR**, e uma notificação de primeiro plano é mostrada.
- O encoder surface H.264 envia SPS/PPS + quadros IDR/P; `VideoPacket.make()` encapsula em MIRR/TADV de 32 + N bytes.
- Um lock serializa TX de vídeo e TX de controle para não misturar frames.
- Relatório de texto registra quadros transmitidos e erros, sem copiar cada payload H.264 na tela.

---

# NURA LD36 Diagnostic v0.3.0

Aplicativo Android para diagnóstico do LD36 via USB AOA sem root.

## Modo passivo e modo ativo

- **VARREDURA PASSIVA (60s):** registra RX sem enviar bytes.
- **NEGOCIAR PROTOCOLO (60s):** responde apenas a mensagens recebidas de tipos documentados: ecoa heartbeat `00 00 00 00`, responde SYNC (25→28 bytes) e envia APP MSG_SYNC (30 bytes), responde STAR (32→48 bytes). Não envia comandos de reinicialização/firmware e não faz sondagem arbitrária.
- Exportação **TXT**, **RX BIN** e **TX BIN** (para a mesma sessão).
- O aplicativo não intercepta o Mirror original. Feche o Mirror original antes de testar.
- **Não oferece espelhamento de tela nem áudio.** O STAR em modo ativo serve somente para validar o transporte/protocolo, sem iniciar MediaProjection nem MediaCodec.
- A análise de pacotes é incremental, independente dos limites de `read()`. O modo ativo é experimental: os layouts foram documentados e testados em emulação TCP; a compatibilidade em USB real ainda precisa de validação.
- A saída TX só é emitida ao reconhecer mensagens documentadas recebidas do dongle; se o LD36 continuar enviando apenas zeros, o único TX será o eco do heartbeat.

## Instruções do teste

1. Feche o Mirror original; conecte o LD36 ao HDMI da TV, à alimentação e ao telefone Android.
2. Abra o NURA LD36 Diagnostic, autorize a conexão USB AOA.
3. Execute **NEGOCIAR PROTOCOLO (60s)** e aguarde o encerramento.
4. Exporte **TXT**, **BIN** (RX) e **TX**, mesmo que TX esteja vazio.
5. Encaminhe os três arquivos para comparar a negociação e determinar o próximo passo.
6. Se o LD36 desconectar, repita no modo passivo para comparação.

Os recursos de análise ativos não autorizam mudanças de firmware. Todos os arquivos são salvos no armazenamento privado do app antes da exportação.

---

# Registro histórico: NURA LD36 Diagnostic v0.1

Aplicativo Android **sem root** para investigar, com consentimento USB, o canal
Android Open Accessory (AOA) do adaptador LD36/Mirror.

## Funcionalidades (v0.1)

- Detecta `UsbAccessory` e exibe fabricante/modelo/versão.
- Também enumera VID/PID de dispositivos `UsbDevice` visíveis quando não há AOA.
- Solicita a permissão USB do sistema e abre `UsbManager.openAccessory()`.
- Faz **somente leitura** de `FileInputStream` no `ParcelFileDescriptor`.
- Salva o fluxo RX original em arquivo `.bin` (primeiros 8 MiB da sessão).
- Mostra os bytes RX em hexadecimal, tamanho de cada leitura e cabeçalhos **candidatos**.
- Exporta relatórios `.txt` e captura `.bin` pelo seletor de documentos do Android.
- Não requer root, Shizuku, Internet nem permissões de armazenamento público.

**Não incluído:** gravação TX, handshake ativo, espelhamento de tela, áudio,
interceptação das comunicações de outro app, remoção da proteção Jiagu ou
captura USB abaixo da camada Android.

## Base técnica

Relatório: `RELATORIO-MIRROR-PROTOCOLO.md` (19/11/2026), artefato de pesquisa
fornecido pelo usuário.

Identidade AOA esperada:

- Manufacturer: `onebit`
- Model: `sagetech.onebit.onlymirror`
- Version: `1.0`

Formato de pacote descrito no relatório (ainda não confirmado no cabo físico):

- Bytes 0..3: tamanho total, incluindo prefixo.
- Bytes 4..7: tag invertida; exemplos `CNYS` / `RRIM`.
- Bytes 8..11: magic.
- Quatro zeros podem indicar heartbeat.

**Cuidado com fragmentação:** uma chamada `InputStream.read()` pode retornar
parte de um pacote, vários pacotes juntos ou um bloco começando no meio de um
pacote. Por isso o aplicativo **não pressupõe alinhamento**, e grava o stream RX
em formato binário para análise posterior.

## Como usar no Realme 6 (Android 11)

1. Instale o APK debug gerado e feche o aplicativo Mirror original.
2. Conecte o LD36 ao HDMI da TV, alimentação necessária e USB-C ao celular.
3. Quando o Android perguntar qual aplicativo abrir, escolha **NURA LD36 Diagnostic**.
   Se o Mirror abrir automaticamente, limpe a preferência de abertura USB padrão
   do Mirror nas configurações de aplicativos.
4. Abra o NURA LD36 Diagnostic; toque em **Detectar**.
5. Quando aparecer o acessório, toque **Conectar RX** e autorize o acesso USB.
6. Aguarde as mensagens ou desconexão. Toque em **Parar**.
7. Toque **Exportar TXT** e **Exportar BIN** para salvar os arquivos.
8. Guarde os dois arquivos para investigar os primeiros pacotes reais.

Se aparecer apenas *Nenhum UsbAccessory AOA disponível*, o celular não disponibilizou
o acessório ao app naquele momento; teste alimentação/conexão e padrões do Android.

## Compilar APK sem instalar Android Studio (GitHub Actions)

1. Crie um repositório GitHub **privado** para este projeto.
2. Envie o conteúdo completo desta pasta à raiz do repositório, incluindo `.github`.
3. Acesse a aba **Actions** e selecione **Build APK - NURA LD36 Diagnostic**.
4. Clique **Run workflow** e aguarde a conclusão.
5. Abra a execução concluída e baixe o artefato `NURA-LD36-Diagnostic-APK`.
6. Extraia o ZIP do artefato e instale `app-debug.apk` no Android.

O projeto também pode ser compilado no Android Studio com JDK 17, Android SDK
platform 35, Android Gradle Plugin 8.7.3 e Gradle 8.9. Não incluímos o binário
`gradle-wrapper.jar`; no GitHub Actions o Gradle é instalado automaticamente.

## Analisar captura em um computador

```bash
python tools/analyze_ld36_capture.py NURA_LD36_RX_YYYYMMDD_HHMMSS.bin
```

O script procura cabeçalhos candidatos `CNYS`, `RRIM`, `_PPA`, `EVIL` e heartbeat.
Não envia dados ao adaptador e não presume que o tráfego USB observado na vida
real tenha o mesmo formato do fluxo TCP simulado.

## Limites e segurança

- Fonte Java sem dependências AndroidX; sem ROOT ou permissões perigosas.
- Captura apenas dados entregues à própria aplicação após autorização USB.
- Durante a primeira versão, **nenhum comando de protocolo é escrito no USB**.
- O LD36 pode parar de enviar após seu primeiro pacote se esperar resposta SYNC.
- **Não foi compilado nem testado em hardware físico neste ambiente de entrega.**
  O teste Java do leitor de cabeçalhos passou localmente, mas deve-se validar
  a compilação Android e a operação real em um aparelho antes de usar os logs.
