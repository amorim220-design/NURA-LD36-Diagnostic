# v1.9 — Experimento de proporção nativa na horizontal

**Evidência física preservada**: v1.8, H.264 inicial 1280×720 e VDAT 90° exibiram a imagem na orientação horizontal correta na TV, mas com barras em todos os lados. v1.5 vertical exibiu imagem correta com barras laterais esperadas. Não há prova de que o receptor LD36 aceita H.264 de 1280×576 ou de que elimina barras com esta alteração.

**Hipótese a testar:** no celular com tela 2400×1080 (20:9), MediaProjection→VirtualDisplay de 1280×720 (16:9) faz um primeiro fit/letterbox. Depois LD36 aplica outro enquadramento 16:9. Esta nova modalidade opt-in altera **apenas a geometria inicial do fluxo H.264 e da resposta STAR** para manter a proporção da fonte; mantém o VDAT em 90° da v1.8. Para fonte 2400×1080 usa H.264 1280×576 (múltiplo de 16), sem GPU, sem crop, sem deformar intencionalmente, sem trocar MediaCodec durante a sessão. A TV de 16:9 ainda pode mostrar barras horizontais proporcionais devido ao 20:9.

**Como testar:** colocar Android realmente na horizontal antes de autorizar MediaProjection; selecionar "PROPORÇÃO NATIVA v1.9", iniciar teste e manter posição; conferir no TXT `v1.9 PROPORCAO` e `v1.9 STAR PERFIL: H264=1280x576`, `VDAT ANGLE CONFIRMACAO TX: ...90`; fotografar TV e verificar bordas, rotação, proporção e estabilidade. Caso apareça tela preta/sem imagem, interromper e voltar ao modo v1.8 VDAT 90°. Não escolher esta modalidade em retrato. A v1.9 é diagnóstica; comportamento automático entre retrato/paisagem ainda não foi implementado.

**Critério de aprovação:** vídeo recebido na TV com orientação horizontal correta, sem corte ou estiramento, e bordas reduzidas em comparação visual com v1.8. Se não for compatível, investigar STAR/SPS e retorno do dispositivo, em vez de insistir em escalas arbitrárias.

---

# NURA LD36 v1.8 — teste VDAT manual e desempenho de envio

Evidência física v1.7: Android 2400x1080, codec 1280x720, angle VDAT 0°, mas imagem TV girada/deformada. Log anterior: 209 quadros em aproximadamente 90s; não há prova de que o MediaCodec esteve gerando 30 fps contínuos, nem de que o dongle interpretou o campo VDAT como esperado.

Modo opt-in **VDAT 0° / 90° v1.8**: inicia com orientação natural do Android; idealmente iniciar horizontal antes da autorização MediaProjection para obter codec fixo 1280x720. Dois botões `VDAT 0° (v1.8)` e `VDAT 90° (v1.8)` alteram apenas o ângulo de cabeçalho VDAT no mesmo fluxo, sem trocar resolução, Surface ou codificador e sem encerrar USB; solicita atualização de captura/IDR pelo serviço. Uma telemetria adicional relata quadros/s realmente transmitidos e taxa de bits por janelas de ~5s. Contadores existentes separados por ângulo são mantidos. Modo v1.5 vertical permanece disponível.

**Procedimento:** selecionar v1.8, girar Android para horizontal (em app que permite paisagem) antes de aceitar permissão; confirmar no TXT `codec inicial=1280x720`; testar 0°, fotografar; tocar 90°, fotografar novamente; permanecer fisicamente na horizontal durante ambos os testes; exportar TXT. Teste de botões no app durante a sessão pode provocar overlay/captura diferente; preferir voltar ao mesmo conteúdo na TV após cada mudança. Só comparar enquanto ambos ângulos têm vídeo ativo.

**Não comprovado ainda:** rotação correta na TV, escala, FPS, ausência de travamentos, manipulação de IDR, compatibilidade com Android 16. A compilação não substitui teste físico.

---

# NURA LD36 v1.7 — orientação natural na partida (teste físico)

Referência vertical: foto real enviada pelo usuário (2026-11-24) mostra a tela inteira centralizada com barras laterais corretas, sem deformação. Teste v1.6 forçou Activity na horizontal e apresentou distorção no aplicativo e na tela em paisagem, embora tenha proporcionado outro enquadramento melhor em certas condições verticais.

**Mudança limitada:** modo opt-in ORIENTAÇÃO NATURAL v1.7 deixa o Android controlar orientação da Activity (SCREEN_ORIENTATION_UNSPECIFIED). Antes da permissão MediaProjection, orienta o usuário sobre como iniciar; após STAR, mede tamanho real do display e escolhe H264 fixo 720x1280 se vertical ou 1280x720 se horizontal. Mantém o fluxo direto MediaProjection/MediaCodec da família v0.4 e o mapeamento VDAT invertido da v1.5. Não altera resolução do codec durante a sessão (teste isolado). Mantém v1.5 e v1.6 como modos comparativos.

**Teste preferencial:** manter a rotação automática do Android ativada, selecionar ORIENTAÇÃO NATURAL, escolher uma tela que gira realmente (app de vídeo/jogo), iniciar ESPELHAR, colocar telefone em horizontal ANTES de confirmar a permissão, confirmar no TXT `v1.7 PERFIL NATURAL: display real=... -> codec inicial=1280x720`. Se Android apresentar retrato, o codec iniciará em 720x1280 — isso não é uma validação horizontal. Registrar fotos da TV. Não girar depois durante o mesmo teste; parar/recomeçar para cada orientação.

**Critérios de aprovação:** vídeo horizontal na TV sem ficar de lado, sem deformação ou cortes, estabilidade, sem mudar codec enquanto envia, logs 1280x720. **Não testado fisicamente**; correção automática ao girar em tempo real é posterior e não foi implementada.

---

# NURA LD36 v1.6 — experimento com perfil paisagem somente no início

**Evidência física:** v1.5 em retrato exibiu a interface corretamente com pequenas bordas laterais. Em paisagem depois de iniciar captura em retrato, a imagem ficou de lado, ocupando apenas uma faixa da TV, e a resolução H264 permaneceu 720x1280; trocar codificador ao vivo na v1.1 provocou congelamento e tela preta.

**Hipótese isolada v1.6:** evitar troca de resolução durante sessão. Novo modo opt-in **PAISAGEM NA PARTIDA v1.6** solicita orientação horizontal ao Android ANTES de pedir autorização MediaProjection. Depois verifica que display real está horizontal, captura H264 diretamente em 1280x720 e mantém essa resolução por toda a sessão; o campo VDAT angle usa mapeamento inverso da v1.5 (Android 90° => campo 0°). Mantém USB AOA/handshake e caminho direto Original da v0.4, sem GPU e sem reconfiguração dinâmica.

**Teste físico:** abrir v1.6 -> selecionar PAISAGEM NA PARTIDA -> tocar ESPELHAR -> aceitar permissão -> confirmar log `Tela real: 2400x1080 -> encoder 1280x720` (dimensões reais podem variar) -> fotografar imagem na TV. NÃO girar o celular no meio da sessão; o foco é verificar primeiro se inicialização com codec paisagem é aceita pelo dongle e produz imagem 16:9, sem cortes. Se ficar preta, encerrar sessão e selecionar Original ou VDAT INVERTIDO v1.5. O modo retrato aprovado não foi alterado.

**Limitações e critérios:** respeitar Android, que pode recusar orientação forçada por configuração de dispositivo. A prova de compilação NÃO comprova imagem na TV. Se o STAR/decoder não aceitar 1280x720 inicial, será preciso rever negociação ou composição. Se houver imagem mas geometria incorreta, obter nova foto e TXT para comparar.

---

# v1.5 — teste controlado de orientação INVERTIDA na TV

Pedido do usuário após foto com bordas claras em cima/baixo e pretas nas laterais: inverter o comportamento da orientação da TV. O modo adicional **VDAT INVERTIDO v1.5** modifica só o valor do campo int32 `angle` dos pacotes VDAT:
- Android 0° -> VDAT 90°
- Android 90° -> VDAT 0°
- Android 180° -> VDAT 270°
- Android 270° -> VDAT 180°

Mantém o mesmo MediaCodec, a Surface, a resolução de captura e os pacotes AOA da v1.4. NÃO implementa aumento de escala e não garante ajuste completo das bordas. O modo Original v0.4 continua o padrão seguro; o modo VDAT ANGLE v1.4 continua como controle comparativo. O aplicativo exporta logs Android angle versus valor efetivamente enviado e contadores TX após o giro.

Teste físico: selecionar VDAT INVERTIDO v1.5, espelhar primeiro com celular em retrato; fotografar imagem na TV; girar para paisagem e fotografar; retornar para retrato; exportar TXT. Critérios: orientação visual corresponde ao desejo do usuário, não aumenta cortes, não há freeze/tela preta. Caso falhe, voltar ao Original ou VDAT ANGLE v1.4. Não considerar o comportamento aprovado sem observação na TV.

---

# v1.4 — diagnostico da confirmacao real de envio VDAT apos giro

Reanalisado arquivo fisico TX de 2026-11-24: 30.040.644 bytes, estrutura sem gaps, 2.040 quadros VDAT (1 SPS/PPS e 2.039 quadros de video), angulos 0=1.632 e 90=407 pacotes e 91 heartbeats. Foram seis mudancas de valor apos angulo inicial. Os dados mostram o que o app transmitiu, NAO como a TV exibiu.

Melhoria v1.4: no modo opt-in VDAT ANGLE ha contadores independentes dos quadros transmitidos por angulo 0/90/180/270, dos pedidos/quadros IDR observados, dos SPS/PPS, e das transicoes. Depois de `transmitUsb` retornar, o app registra `VDAT ANGLE CONFIRMACAO TX` para o primeiro pacote transmitido em cada orientacao e uma linha periodica com contadores a cada 300 quadros. Ao parar, `VDAT ANGLE RESUMO` resume o teste. Isso ajuda separar evento do Android e envio USB sem afirmar que o LD36 decodificou o video.

O app continua iniciando com modo Original como default; nao modifica superficie ou MediaCodec no modo VDAT ANGLE. A semantica de `angle` no receptor e o resultado visual ainda sao desconhecidos. Nao utilizar modo AUTO ORIGINAL que congelou no hardware. Teste em TV: escolher VDAT ANGLE v1.4, iniciar em retrato, girar uma unica vez, observar imagem, exportar TXT e BIN. Sem relato visual nao considerar o recurso funcional.

---

# v1.3 — experimento isolado do campo de orientação VDAT (sem reinicialização do H.264)

**Evidência na TV:** modo Original v0.4 mostra imagem; v1.0 direto horizontal 1280×720 não exibiu vídeo; v1.1 troca dinâmica H264 iniciou giro mas congelou e depois ficou preto. O código atual envia `VideoPacket.make(..., angle=0, ...)` em todos os quadros. O formato VDAT possui um `int32` na posição 24 para `angle`, mas sua semântica no hardware LD36 ainda **não está comprovada**.

**Novo modo opt-in VDAT ANGLE:** inicia com a MESMA cadeia de captura/encoder Original v0.4, sem reinicializar MediaCodec, sem reanexar Surface, sem alterar SPS/PPS e sem renegociar STAR. Lê `Display.getRotation()` e envia o valor numérico 0, 90, 180 ou 270 no campo VDAT angle a partir do próximo quadro H264. Após a mudança, pede um IDR sem alterar codec. A interface registra `VDAT ANGLE ATUALIZADO` e `VDAT ANGLE PACKET`. Os testes unitários confirmam o campo em little-endian e verificam que a carga AVC não foi modificada.

**Hipótese experimental, não solução confirmada:** alguns receptores podem ignorar este campo, interpretá-lo em outra unidade, ou mostrar conteúdo ainda fora de orientação; pode não aparecer imagem. O decoder pode permanecer configurado em retrato. O resultado visual em tela deve ser relatado pelo usuário antes de qualquer conclusão.

**Procedimento:** instalar debug APK v1.3 -> em AJUSTE NA TV escolher VDAT ANGLE v1.3 -> iniciar na vertical e confirmar vídeo -> girar uma vez para paisagem -> observar se houve rotação/letterbox ou ficou preto -> voltar à vertical se ainda houver vídeo -> exportar TXT. O modo **Original v0.4** segue disponível e é a referência segura. Não insistir com modo AUTO ORIGINAL v1.1, que congelou após troca de SPS/PPS no hardware real. O áudio permanece fora do escopo.

---

# v1.2 — proteção contra dois leitores concorrentes do MediaCodec

Evidência teste v1.1: primeira troca 720x1280 -> 1280x720 reportou êxito, porém retorno a 720x1280 teve `timeout aguardando encoder anterior`; um segundo timeout ocorreu em seguida. O código da v1.1 criava outro thread de leitura sem confirmar que o anterior havia encerrado, podendo disputar o mesmo MediaCodec.

Mudança de segurança isolada: quando a transferência de Surface ainda não aconteceu e a thread anterior está viva, **não iniciar outro leitor** e manter Surface/codec existentes. Se a thread já terminou, o leitor antigo pode ser reiniciado. O log diferencia `AUTO ORIGINAL TIMEOUT SEGURO` e `AUTO ORIGINAL RECUPERACAO`. Log de troca bem-sucedida agora informa que o novo H264 aguarda validação, sem alegar entrega de SPS/PPS/IDR à TV.

**Ainda não resolve a possível causa inicial do timeout:** a thread pode estar bloqueada na escrita USB. A versão é um teste de recuperação segura, não uma correção comprovada de rotação. Não reiniciar repetidamente durante falha; usar Original v0.4 como fallback e exportar TXT. Protocolo AOA intacto.

---

# v1.1 — AUTO ORIGINAL (teste isolado de mudança dinâmica de resolução H.264)

Resultado real: Original v0.4 continua com imagem na TV; TV DIRETO v1.0 não apresentou imagem; GPU v0.8-v0.9 distorceu em todos os ângulos. Por isso o novo caminho inicia **exatamente como o Original** (MediaProjection -> VirtualDisplay -> MediaCodec Surface). Não usa EGL/OpenGL. Não altera os pacotes AOA/VDAT.

No novo modo opt-in AUTO ORIGINAL, a codificação começa com **720x1280 em retrato** ou **1280x720 em paisagem**, como Original. Quando o Android reporta mudança real nas dimensões da tela, um processo de troca controlada:
1. pausa o loop do antigo MediaCodec,
2. cria um novo MediaCodec com dimensões da nova orientação,
3. faz `VirtualDisplay.resize(newW,newH,dpi)` e `setSurface` na nova entrada do encoder,
4. reinicia H.264 com SPS/PPS/IDR atualizados (sem renegociação de STAR, experimental).
5. registra `AUTO ORIGINAL EVENTO`, `AUTO ORIGINAL TROCA`, `AUTO ORIGINAL TROCA OK` ou `FALHOU`.

**Limitações conhecidas:** o dongle pode não aceitar alteração dinâmica de SPS/PPS/IDR sem novo handshake STAR; pode ficar preto depois do giro. Android também pode impor limite a mudança de Surface. Este experimento não comprova rotação automática funcional. Se ficar preto, testar Original novamente. Não usar em sessões importantes. Os botões de giro GPU não funcionam neste modo. Som permanece fora do escopo.

Teste curto e comparável no hardware real: instalar APK -> selecionar AUTO ORIGINAL v1.1 -> iniciar em retrato e fotografar -> girar para paisagem e fotografar -> retornar a retrato -> exportar TXT. Testar Original v0.4 como controle se a imagem sumir. **Critério de validação:** imagem sempre presente, proporção natural e mudança de orientação sem esticar nem cortar; sem isso não aprovar.

---

# NURA LD36 v1.0.0 — teste isolado do compositor nativo Android

Contexto: na TV real o protocolo AOA e H.264 funcionam. A v0.9 continua visualmente distorcida em todos os ângulos. O processamento da v0.5–v0.9 tem uma potencial **dupla transformação**: Android já ajusta a tela ao VirtualDisplay e a GPU ajusta novamente a textura com AspectFit/UV.

## Novo terceiro modo: TV DIRETO v1.0

- MediaProjection cria um VirtualDisplay fixo de 1280x720 **diretamente na Surface de entrada do MediaCodec**, exatamente como o caminho testado da v0.4, mas com saída horizontal constante.
- **Zero ScreenCropRenderer / EGL / escala extra.** O Android fica responsável por centralização e proporção. Não remapear eixos por conta própria.
- Ao girar o telefone, registrar dimensões do display/conteúdo, solicitar IDR, **não redimensionar nem reconectar a Surface do codec**. Manter o sinal do LD36 em 1280x720.
- Preservar modo Original v0.4 como opção inicial (segura) e modo GPU v0.9 para comparação. Não foram feitas alterações nos pacotes AOA/VDAT nem implementado áudio.

## Testes no aparelho/TV (pendentes)

1. Escolher TV DIRETO v1.0 e espelhar; o vídeo inicia em 1280x720, sem GPU própria.
2. Em vertical: verificar imagem inteira, proporções naturais e bordas aceitas.
3. Girar o aparelho, abrir app/jogo horizontal: verificar se a TV se atualiza, sem cortes, alongamento nem ficar de lado.
4. Exportar TXT e comparar com fotos da TV. Eventos TV DIRETO ROTACAO, TV DIRETO CONTEUDO e fluxo VDAT são diagnósticos de sessão.
5. Se falhar, interromper e comparar com Original v0.4; não afirmar correção visual sem a evidência do teste físico.

---

# NURA LD36 v0.9 — correção experimental do aspecto / rotação

Fotos do teste v0.8 mostraram dois casos: conteúdo achatado em uma faixa horizontal, e conteúdo maior porém deformado. A v0.8 também acumulava correções UV de 90/180/270 graus ao receber uma nova orientação física do Android.

## Escopo das mudanças

- Ao mudar a dimensão real da captura (por exemplo 1080x2400 para 2400x1080), zera automaticamente a rotação UV manual anterior (0 graus) antes de recalcular a geometria aspect-fit. Impede dupla rotação ao mudar o celular de orientação.
- Gira a textura apenas quando o usuário solicitar. O modo experimental calcula largura/altura da imagem **já orientada** e ajusta dentro do encoder 1280x720 sem recorte, conservando a proporção de pixels declarada pela fonte.
- Botão REDEFINIR PROPORÇÃO / GIRO 0° recupera geometria inicial. Para evitar falsa confirmação de alterações, diagnóstico GPU registra separadamente realGPU, dimensões fonte, dimensões orientadas, proporção e coordenadas do quad.
- O modo Original (v0.4) continua selecionado por padrão; transporte AOA, SYNC/STAR e H.264 não foram alterados. Áudio não implementado.

## Validação física pendente

1. Comece com telefone vertical, Rotação experimental. Observe o vídeo na TV.
2. Se ficar de lado, use GIRAR 90° até a orientação ficar correta e confira proporção visual.
3. Gire o telefone para horizontal com a captura ativa. A correção manual volta a 0°. Teste com um jogo horizontal.
4. Se imagem desproporcional, use REDEFINIR 0° e compare. Não tente compensar proporção errada com uma rotação arbitrária.
5. Exporte TXT e procure GEOMETRY, GPU APOS AJUSTE, GPU APOS REBOUND, RESET EM ROTACAO FISICA.
6. Fotos com elementos circulares (ícone, relógio) ajudam a detectar distorção. Não considerar correção validada sem teste real na TV.

---

# NURA LD36 v0.8.0 — diagnóstico da orientação por rotação de textura

Base factual: no relatório real de 23:01:29, o Android abriu a sessão com captura 1080x2400, encoder 1280x720; registrou REBOUND #1 ao mudar para 2400x1080 às 23:01:43 e REBOUND #2 ao voltar a 1080x2400 às 23:01:52. Ambos reportaram reanexação de superfície OK, mas os vídeos/fotos mostram imagem girada ou estreita. Rebind OK não demonstra enquadramento correto.

## Mudanças v0.8

- Mantém Original (v0.4) como padrão e conserva o transporte USB H.264 e o modo anterior para fallback.
- Em Rotação experimental, acrescenta GIRAR IMAGEM DA TV 90°, que troca coordenadas UV e o aspecto correspondente na GPU, sem reiniciar MediaProjection, codec ou conexão USB. Toques sucessivos aplicam 0 -> 90 -> 180 -> 270 -> 0, somente a pedido do usuário.
- Preserva a matriz de transformação SurfaceTexture produzida pelo Android. Log TXT inclui GPU DIAGNOSTICO, GPU APOS REBOUND, GPU APOS AJUSTE, ângulo manual e dimensões.
- Não há recorte proposital: o modo experimental usa aspect-fit. Bordas para preservar a proporção são esperadas.
- Envio: vídeo apenas. Áudio ainda não implementado.

## Procedimento de validação no LD36 físico

1. Instale v0.8; primeiro use Original e verifique o vídeo conhecido da v0.4.
2. Pare e selecione Rotação experimental. Inicie autorizando captura e USB.
3. Observe a TV em vertical e horizontal. Se aparecer de lado, toque GIRAR IMAGEM DA TV 90° uma vez, e repita até achar a orientação correta. Anote o número de toques.
4. Verifique se a imagem inteira aparece sem distorção; se necessário, repita o ajuste após a rotação.
5. Exporte TXT depois do teste. Procure GPU DIAGNOSTICO, GPU APOS AJUSTE, REBOUND e ORIENTACAO MANUAL.
6. Se continuar falhando, volte ao modo Original. Esta correção não está comprovada em hardware real até novo teste.

---

# NURA LD36 v0.7.0 — diagnóstico direcionado de rotação (ainda experimental)

**Evidência física:** a versão 0.4 exibiu vídeo na TV, com pequenas bordas; a 0.5 cortou a imagem e a 0.6 exibiu faixas, conteúdo girado e enquadramento errado. **Não declarar que v0.7 corrigiu até validar em hardware.**

## Correção implementada no código

- A opção **Original (v0.4)** volta a ser padrão. Sem GPU de composição, mantém o caminho de vídeo que o usuário confirmou na TV.
- A opção **Rotação experimental** usa uma superfície de captura separada da superfície do encoder; a GPU preserva toda a imagem com barras se necessário, **não usa crop**.
- Quando o Android reporta mudança de dimensões: VirtualDisplay.setSurface(null) -> atualiza SurfaceTexture.setDefaultBufferSize() na thread EGL e aguarda conclusão -> VirtualDisplay.resize() -> VirtualDisplay.setSurface(captureSurface) -> solicita quadro-chave H.264.
- Logs de REBOUND (sucesso ou falha) mostram os resultados da operação; também há atualização após a configuração da atividade ou DisplayManager.

## Teste físico (sem áudio)

1. Instale v0.7 e **guarde v0.4** para comparação.
2. Selecione **Original** e confirme que a imagem se comporta como na v0.4. Se não funcionar, interrompa o teste e reporte.
3. Pare. Selecione **Rotação experimental**, inicie espelhamento (90 segundos), autorize compartilhamento de tela/USB.
4. Com a captura ativa, abra um aplicativo vertical e depois um jogo horizontal. Gire fisicamente o celular; observe imagem inteira, barras, orientação e estabilidade.
5. Exporte TXT para encontrar eventos REBOUND e erros. A captura TX pode ter 32 MiB mesmo que todo o vídeo tenha sido enviado.
6. Se a imagem voltar a ficar de lado, cortada ou comprimida, pare e volte ao Original; não trate o modo experimental como estável.

Limitações: o resultado ainda depende do compositor MediaProjection da ROM Android 11, dos eventos de rotação recebidos e do decodificador LD36. O encoder H.264 continua 1280x720 no modo experimental e o áudio **não está implementado**.

---

# NURA LD36 v0.6.0 — correcao de rotacao, sem cortes

**Problema no teste físico da v0.5:** em modo Preencher TV a imagem ficou cortada. Ao abrir um jogo e girar o celular, apenas uma parte da tela aparecia na TV.

**Correção experimental:** o modo Automático substitui o recorte por **aspect-fit**: toda a imagem é mostrada sem distorcer e barras são permitidas quando necessário. A captura permanece viva durante eventos de mudança de orientação, e a resolução do virtual display e do SurfaceTexture são atualizadas quando o Android reporta novo tamanho. O encoder H.264 continua em **1280×720** (paisagem) para evitar reiniciar a negociação do LD36 a cada rotação. O serviço permanece com a permissão explícita MediaProjection.

### Teste solicitado
1. Abra o NURA LD36, escolha **Automático (recomendado)** e inicie a captura USB.
2. Verifique a tela inicial e abra um jogo **na horizontal**. Alterne para vertical e horizontal sem fechar a captura.
3. Verifique se a TV mostra **a tela inteira**, mesmo que sejam necessárias barras pretas para preservar a proporção.
4. Exporte o TXT (especialmente as linhas "Orientacao alterada" e possíveis erros); informe se o aplicativo voltou a mostrar o quadro inteiro.
5. Se apresentar problema, selecione **Original (v0.4)** e inicie nova sessão. O modo Original preserva o caminho direto anterior.

**Não há áudio**, a rotação dinâmica depende do comportamento do MediaProjection/VirtualDisplay no Android do telefone e só pode ser confirmada com novo teste físico. Não declarar que a rotação funciona antes do teste.

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
