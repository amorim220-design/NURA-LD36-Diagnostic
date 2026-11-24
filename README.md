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
