# NeverMiss para Minecraft

Plugin que entrega no seu servidor o que você vende pela NeverMiss, no Discord e na loja do site.
Você escreve no painel os comandos de cada produto; o plugin roda esses comandos no console quando a venda é paga.
Ou monta um **Kit da NeverMiss** no painel: o plugin põe os itens no inventário do comprador, sem precisar de outro plugin.

- Servidor de jogo (Spigot, Paper, Folia e forks; Fabric, Forge e NeoForge; PowerNukkitX, de Bedrock) e proxy (Velocity, BungeeCord e Waterfall).
- Jogadores Java e Bedrock (pelo Geyser + Floodgate, ou no servidor Bedrock).
- Servidor original e pirata (offline-mode).
- Código aberto, licença MIT.

## Plataformas

| Plataforma | Versões | Arquivo | Kit da NeverMiss | Provado num servidor de verdade (`teste/e2e.sh`) | Sem prova |
|---|---|---|---|---|---|
| Spigot, Paper e forks | 1.7.10 ao 26.x | `NeverMiss-Bukkit-1.2.0.jar` | sim | Com o kit e o `/kit` por um jogador: Paper 1.8.8, 1.12.2, 1.20.4 e 1.21.11 (código final), e 1.7.10, 1.16.5 e 26.3 (1.2.0 antes das últimas correções do core). O catálogo inteiro de itens do painel em 1.8.8, 1.12.2, 1.20.4 e 1.21.11 | Spigot puro (BuildTools). No 1.7.10 o inventário só foi conferido fora do teste |
| Folia | 1.19.4 em diante | `NeverMiss-Bukkit-1.2.0.jar` (o mesmo) | não (o item precisa ir na região do jogador) | Folia 1.21.11 sem kit (1.2.0 antes das últimas correções do core) | Folia com o código final |
| Fabric (só no servidor, sem o Fabric API) | 1.16.5 ao 26.x, Fabric Loader 0.14+ | `NeverMiss-Fabric-1.16.5-26.x-1.2.0.jar` | sim | Nenhum: só os testes sem servidor e os nomes do jogo conferidos nos mapas oficiais de 14 versões | Tudo num servidor de verdade (entrega, kit, `/kit`). Bedrock |
| Forge (só no servidor) | 1.16.5 ao 1.20.1 | `NeverMiss-Forge-1.16.5-1.20.1-NeoForge-1.20.4-26.x-1.2.0.jar` | sim | Nenhum: só o teste sem servidor e os nomes conferidos nos mapas e nas classes do Forge de cada versão | Tudo num servidor de verdade. O Forge 1.20.2 em diante não é atendido: o servidor sobe e o console avisa que o NeverMiss não entrega nada |
| NeoForge (só no servidor) | 1.20.4 ao 26.x | o mesmo do Forge | sim | Nenhum, como o Forge | Tudo num servidor de verdade |
| Forge 1.12.2 (só no servidor) | 1.12.2 | `NeverMiss-Forge-1.12.2-1.2.0.jar` | sim | Nenhum: compila contra os nomes do jogo, conferidos nos mapas MCP | Tudo num servidor de verdade. Sem Bedrock |
| PowerNukkitX (servidor Bedrock) | 3.x (Java 25) | `NeverMiss-PowerNukkitX-3.x-1.2.0.jar` | não (o catálogo tem só os itens do Java) | PowerNukkitX 3.0.6 com o código final, com um jogador Bedrock entrando pelo NetherNet | O 3.0.5 parou na entrada do jogador. O `{uuid}` com conta Xbox. Nukkit, Nukkit-MOT e PowerNukkitX 2.x não são atendidos |
| Velocity (proxy) | 3.1.1 em diante | `NeverMiss-Velocity-1.2.0.jar` | não (proxy não tem inventário) | Velocity 3.5.1 sozinho, com o código final (o kit que chega é recusado). Na 1.1.0: 3.5.1 e 4.3.1 com um Paper atrás | Velocity 4.3.1 e a entrega com um Paper atrás, com a 1.2.0 |
| BungeeCord e Waterfall (proxy) | API do 1.16 em diante | `NeverMiss-Bungee-1.2.0.jar` | não | Na 1.1.0: BungeeCord build 2107 e Waterfall 1.21, com um Paper atrás | Os dois com a 1.2.0 |
| Bedrock pelo Geyser + Floodgate | Floodgate 2.x | o do Bukkit, Velocity ou Bungee | só o da compra (sem `/kit`) | Na 1.1.0: Paper 1.21.11 com Geyser 2.12.1 e Floodgate 2.2.5; Velocity e BungeeCord com Floodgate 2.2.5 | Com a 1.2.0, e o kit dado a um jogador Bedrock (só no teste do core). No Fabric, no Forge e no NeoForge |

- **Spigot, Paper, Folia e PowerNukkitX:** o jar vai em `plugins/`. **Fabric, Forge e NeoForge:** em `mods/`, a chave em `config/nevermiss/config.yml`, e o `/nevermiss` é de quem pode o `/op` (nível 3). O jogador não instala nada.
- **PowerNukkitX:** o `{nick}` é o gamertag, entre aspas quando tem espaço; o `{uuid}` só com o jogador online. Entregue com comandos (`give {nick} diamond 3`).
- O Java é o que o seu servidor já pede: o plugin roda em todos (Java 8 em diante; o do Velocity, 11).

## Instalar

1. No painel da NeverMiss, em **Minecraft › Servidores**, toque em **Adicionar servidor**. A chave aparece **uma vez só**: copie.
2. Ponha o jar da sua plataforma (tabela acima) na pasta `plugins/` (no Fabric, no Forge e no NeoForge, `mods/`) e reinicie.
3. No console (do servidor ou do proxy): `nevermiss chave <a chave>`.
4. Em até 1 minuto o painel mostra **Conectado**.

Confira o SHA-256 do jar antes de instalar (`shasum -a 256 NeverMiss-Bukkit-1.2.0.jar` ou `certutil -hashfile NeverMiss-Bukkit-1.2.0.jar SHA256`). Ele tem que bater com o que a NeverMiss publica. Se não bater, não instale.

Quem tem o `NeverMiss-1.0.0.jar` ou o `NeverMiss-Bukkit-1.1.0.jar`: apague ele e ponha o `NeverMiss-Bukkit-1.2.0.jar`. A pasta `plugins/NeverMiss` (chave e `executadas.txt`) continua a mesma.

### Onde a chave pode ficar

| Onde | Como |
|---|---|
| Console | `nevermiss chave nmmc_...` (só no console, nunca no chat; no Fabric e no Forge 1.16.5+ o RCON também conta como console) |
| `config.yml` (`plugins/NeverMiss/`, `plugins/nevermiss/` no Velocity, `config/nevermiss/` no Fabric, no Forge e no NeoForge) | `chave: "nmmc_..."` e depois `nevermiss recarregar` |
| Variável de ambiente | `NEVERMISS_CHAVE=nmmc_...` (usada quando o `config.yml` está sem chave) |

A chave só lê e confirma as entregas **deste** servidor. Vazou? Troque no painel (**Trocar chave**): a antiga para na hora.

## No servidor de jogo ou no proxy?

| | No servidor de jogo (Bukkit) | No proxy (Velocity, BungeeCord) |
|---|---|---|
| Onde o comando roda | no console do servidor | no console do proxy |
| Para quê | `give`, kits, dinheiro, LuckPerms do servidor: tudo que é do mundo ou dos plugins daquele servidor | LuckPerms do proxy, `alert`, `send`: o que vale para a rede toda |
| "Só com o jogador online" | o jogador está **neste** servidor | o jogador está em **qualquer** servidor da rede |
| `{uuid}` | só em servidor original. Atrás de proxy o servidor é pirata, então não sai | sai do proxy (online-mode do proxy) |

- O proxy não roda comando de servidor: um `give` no console do proxy não existe e a entrega falha (`comando_falhou`).
- Os dois podem rodar juntos: cadastre o proxy e cada servidor no painel, **cada um com a sua chave**, e escolha em cada produto para onde a entrega vai. A mesma chave em dois lugares faz a entrega cair em qualquer um deles.

## Bedrock (Geyser + Floodgate)

- Instale o Geyser e o Floodgate como o Geyser manda. O plugin acha o Floodgate sozinho e avisa no console: `Floodgate encontrado (prefixo ".")`. O `nevermiss status` mostra a linha **Bedrock**.
- O comprador digita o gamertag do Xbox como aparece no Minecraft (`Cool Guy`), sem o `#1234` do gamertag novo: com ele não dá para saber o nome no servidor, e sem ele `Cool Guy#1234` e `Cool Guy` seriam o mesmo jogador. O plugin troca pelo nome que o Floodgate dá no servidor: o prefixo mais o gamertag, com espaço virando `_`, cortado em 16 (`.Cool_Guy`). É esse nome que entra no `{nick}`. Deixe o `replace-spaces` do Floodgate ligado (o padrão).
- O `{uuid}` do jogador Bedrock é o do Floodgate (em servidor original), garantido só com o jogador online: use **Só com o jogador online**. Offline, ele vem da GeyserMC, que só conhece quem já entrou num servidor com Geyser, e gamertag com espaço nem é buscado (a entrega falha com `erro`).
- Sem o Floodgate, a entrega para Bedrock falha (`sem_bedrock`) e o console avisa `este servidor não aceita Bedrock: instale Geyser + Floodgate`.
- Floodgate só no proxy: o plugin do servidor não enxerga, então entregue para Bedrock pelo plugin do proxy (ou ponha o Floodgate também nos servidores).

## Kit da NeverMiss

No painel, em **Minecraft › Kits**, você monta o inventário (itens, quantidade, encantamentos, nome e descrição) e liga o kit na variação do produto: **Dar ao comprar** e/ou **Resgate com /kit**, a cada quanto tempo e até quando.

- **Na compra:** o plugin põe cada item no espaço que você escolheu, se estiver vazio; a armadura vai direto para o corpo ("Equipar a armadura sozinho"); o que não couber vai para o inventário e o resto cai no chão, aos pés do jogador. O jogador precisa estar no servidor: a entrega espera ele entrar. Ele recebe `Você recebeu o kit VIP Diamante. Use /kit vip-diamante a cada 5 min.`
- **`/kit <nome>`** (ou **`/nmkit <nome>`**, se o servidor já tem o `/kit` do EssentialsX ou de outro mod): o comprador pega o kit de novo depois do intervalo, até o fim do prazo. O kit da compra conta como o primeiro. `/kit` sozinho lista os kits dele com o tempo que falta. Quem comprou duas vezes tem dois resgates, cada um com o seu intervalo. Permissão `nevermiss.kit` (todos têm).
- O kit sai **como está no painel na hora**: editar o kit vale para as próximas entregas e resgates.
- **Versão:** o item usa o nome de cada versão (do 1.13 em diante o moderno, `diamond_sword`; antes, o antigo). O item que não existe na versão do servidor (netherite no 1.8) é pulado; se nenhum existir, a entrega falha (`kit_incompativel`).
- **Onde:** Spigot/Paper (provado do 1.7.10 ao 26.x), Fabric, Forge e NeoForge (ainda sem prova num servidor de verdade, tabela acima). O Folia, o PowerNukkitX e o proxy (Velocity, BungeeCord) não dão kit: a NeverMiss não manda kit para eles, e o que chegar falha (`kit_incompativel`) sem rodar nada.
- **Jogador Bedrock (Geyser):** recebe o kit da compra no nome do Floodgate. O `/kit` dele ainda não funciona, então o kit **com resgate** para ele falha inteiro (`kit_incompativel`) e nada roda, nem o da compra.
- **Até 1000 itens por entrega** (as vezes compradas × os itens do kit): acima disso a entrega falha (`kit_incompativel`) e nada roda, porque o servidor travaria e quase tudo cairia no chão. Um kit de 6 itens vai até 166 de uma vez.
- **Uma chave por servidor.** O intervalo do `/kit` fica no `resgates.txt` de cada servidor: com a mesma chave em dois servidores, o comprador pega o kit em cada um.
- **Nunca dá 2 vezes:** o kit da compra segue o `executadas.txt`, como os comandos. A hora de cada `/kit` vai para o `resgates.txt` (na pasta do plugin) antes de dar os itens. Não apague esse arquivo: sem ele o intervalo zera.
- Por dentro, o plugin dá cada item com o comando do próprio jogo (`give` e `item replace`, ou o `replaceitem` antes do 1.17; no Bukkit sempre como `minecraft:give`, para o `/give` de outro plugin não pegar; no Fabric e no Forge, direto no despachante de comandos do jogo). No 1.7.10 e no 1.8, pela API do Bukkit.

## Loja no jogo (/loja)

O jogador compra **dentro do servidor** o que você vende pela NeverMiss. Ele digita `/loja` (ou `/shop`, ou `/nmloja`) e abre um menu de baú com os produtos que você criou no painel para **este** servidor; escolhe a opção e a quantidade e toca em **Pagar com Pix**. O QR Code do Pix aparece num **mapa na mão** dele: é só ler com o app do banco. Pago, o mapa some, ele recebe `Pagamento aprovado!` e a entrega chega como as outras (pela fila, com os comandos e o kit do produto).

- **O que aparece:** a variação com **Entrega no Minecraft** neste servidor e **Pedir o nick** ligado. Fica fora (vende só no Discord e no site) a que manda código na DM, a entrega pela equipe e a que dá cargo do Discord. No painel, em **Entrega no Minecraft › Mais opções › Na /loja do jogo**: **Mostrar na /loja** e o **ícone** do menu (sem escolher, o 1º item do kit, ou um baú).
- **Pagamento:** o Pix sai pela chave YuvexPay que o seu bot já usa, com o mesmo estoque e o mesmo preço (com o desconto por quantidade). Não precisa da loja web publicada. O pedido aparece em **Site › Pedidos** como `Minecraft · <nick>`. Sem cupom.
- **Vence em 15 min.** O plugin confere o pagamento a cada 5 s; vencido, o mapa sai. No 1.15 em diante o chat tem também o **copia e cola** num clique. No máximo 2 Pix abertos por jogador.
- **Jogador Bedrock (Geyser):** compra no gamertag dele; o produto que não entrega para Bedrock aparece como "Só para o Minecraft Java".
- **NPC ou menu de outro plugin:** `nevermiss loja <jogador>` no console abre a /loja daquele jogador.
- **Onde:** Spigot e Paper. O Folia, o Fabric, o Forge, o NeoForge, o PowerNukkitX e os proxies ainda não têm a /loja.

## Comandos

Permissão `nevermiss.admin` (vem para op; no proxy, dê pelo plugin de permissão do proxy; no Fabric, no Forge e no NeoForge, quem pode o `/op`). O console sempre pode.

| Comando | O que faz |
|---|---|
| `nevermiss status` | Plataforma, conexão, último contato, entregas esperando o jogador, confirmações pendentes e Bedrock |
| `nevermiss testar` | Consulta a NeverMiss agora e responde se conectou |
| `nevermiss recarregar` | Relê o `config.yml` |
| `nevermiss chave <chave>` | Grava a chave (só no console) |
| `kit [nome]` e `nmkit [nome]` | Do jogador, permissão `nevermiss.kit`: lista ou pega os kits da NeverMiss dele (acima) |
| `loja`, `shop` e `nmloja` | Do jogador, permissão `nevermiss.loja` (todos têm): abre a loja do servidor (acima). Spigot e Paper |
| `nevermiss loja <jogador>` | Abre a /loja de um jogador (para NPC e menu de outro plugin) |

O botão **Testar** do painel manda uma entrega de teste: o console mostra `Teste do painel recebido` e nenhum comando roda.

## Como funciona

- A cada 30 s o plugin pergunta à NeverMiss se há entregas para este servidor. Não precisa abrir porta: é o servidor que chama.
- Cada entrega roda os comandos no console, na ordem, e o console mostra `[NeverMiss] Rodou: <comando>`. No Folia, os comandos rodam na região global.
- **Nunca roda 2 vezes.** Antes do primeiro comando, o id da entrega vai para o `executadas.txt` (na pasta do plugin). Se a confirmação se perder, a entrega volta e o plugin só confirma de novo. Se o servidor cair no meio, a entrega aparece no painel como falha (**interrompida**), com o botão **Reenviar**. Não apague esse arquivo.
- **NeverMiss fora do ar:** o plugin tenta de novo em 30 s, 60 s, até 5 min. O que já rodou fica guardado e é confirmado quando ela voltar.
- **Só com o jogador online** (opção do produto): a entrega espera o jogador entrar e roda logo depois.
- **Prazo (VIP de 30 dias, por exemplo):** use o prazo do próprio comando (`lp user {nick} parent addtemp vip 30d accumulate`) ou o "Tirar depois de N dias" do painel.

### Variáveis dos comandos

| Variável | Vira |
|---|---|
| `{nick}` | o nick que o comprador digitou (3 a 16 letras, números ou `_`). Bedrock: o nome do Floodgate (`.Cool_Guy`) |
| `{uuid}` | o UUID do jogador. **Só em servidor original** (ou no proxy original): em servidor pirata a entrega falha (`uuid_pirata`) |
| `{quantidade}` | quantas unidades foram compradas |
| `{pedido}` | o número do pedido |
| `{produto}` | o nome do produto |

Em servidor pirata, o comprador é identificado pelo nick. Um nick digitado errado entrega para outra pessoa, por isso o checkout pede o nick igual ao do jogo.

## Segurança

- **Comandos proibidos**, recusados aqui e na NeverMiss (o painel não desliga): `op`, `deop`, `stop`, `restart`, `reload`, `rl`, `ban`, `ban-ip`, `banip`, `ipban`, `tempban`, `tempbanip`, `tempipban`, `pardon`, `pardon-ip`, `pardonip`, `unban`, `unbanip`, `whitelist`, `execute`, `sudo`, `plugman` e os de desligar e recarregar o proxy (`end`, `shutdown`, `greload` e o `velocity`, que tem o `velocity reload` e o `velocity dump`), também com prefixo (`minecraft:op`) e com o `e` do EssentialsX (`esudo`, `eban`), e a palavra `*` ou `algo.*` sozinha, com ou sem aspas. A entrega com um deles falha inteira (`bloqueado`) e nada roda. É uma lista de bloqueio, não uma garantia: um comando que dá cargo de staff (`lp user {nick} parent set admin`) passa. Quem decide os comandos é você, no painel, com 2FA.
- **Do comprador só vem o nick**, num formato fechado (3 a 16 letras, números ou `_`; no Bedrock, o gamertag: letras, números e espaço) conferido no checkout, na NeverMiss e de novo aqui antes de rodar. No Bedrock o plugin confere a lista proibida depois de montar o nome.
- **O plugin nunca baixa nem roda código.** Da NeverMiss chegam só os textos dos comandos que você salvou no painel (com 2FA).
- **O console nunca sai do servidor.** O plugin manda só se cada comando deu certo (sim/não) e um motivo de uma lista fechada.
- **Só HTTPS.** `http://` só é aceito em `localhost` e `127.0.0.1`, para teste. Redirecionamentos são recusados, para a chave não ir parar em outro endereço.
- A chave não aparece no log, só os 4 últimos caracteres.

## Problemas comuns

| No console | O que fazer |
|---|---|
| `Chave inválida ou revogada` | Gere outra no painel e use `nevermiss chave` |
| `O adicional Minecraft não está ativo nesta loja` | Contrate ou renove o adicional no painel |
| `Atualize o plugin NeverMiss` | Baixe a versão nova |
| `Sem resposta da NeverMiss (SSLHandshakeException)` | Java muito antigo: atualize o Java do servidor |
| `este servidor não aceita Bedrock: instale Geyser + Floodgate` | Instale o Floodgate aqui, ou entregue pelo plugin do proxy que tem o Floodgate |
| `Este Forge não tem o que o NeverMiss usa` (ou `Este Minecraft`, no Fabric) | A versão não é atendida (o Forge 1.20.2 em diante, por exemplo): use o NeoForge, ou o Forge 1.16.5 ao 1.20.1 |

## Compilar e testar

Precisa do JDK 17 (os jars saem para Java 8; o do Velocity, para Java 11). O do PowerNukkitX compila com o `javac` do JDK 25, em `JAVA25_HOME`: sem ele, saem todos os outros.

```sh
JAVA25_HOME=/caminho/do/jdk-25 ./gradlew build   # os jars em <módulo>/build/libs; roda os testes do core, do Fabric e do Forge
teste/e2e.sh paper 1.20.4              # servidor de verdade + API falsa
teste/e2e.sh paper 1.21.11 bedrock     # com Geyser e Floodgate
teste/e2e.sh folia 1.21.11
teste/e2e.sh velocity 3.5.1            # proxy + um Paper atrás, com o plugin do servidor e outra chave
SO_PROXY=1 teste/e2e.sh velocity 3.5.1 # só o proxy: um servidor ligado de cada vez
teste/e2e.sh waterfall 1.21
teste/e2e.sh bungeecord latest bedrock # com o Floodgate no proxy
teste/e2e.sh powernukkitx 3.0.6        # servidor Bedrock, com um jogador Bedrock pelo NetherNet (Java 25)
teste/e2e.sh fabric 1.21.1             # escritos, ainda não rodaram: fabric 1.20.1 | 26.3, forge 1.12.2 | 1.16.5 | 1.20.1, neoforge 1.21.1 | 26.3
```

- Cada versão pede o seu Java (`JAVA=/caminho/do/java`; `JAVA_BACKEND` para o Paper atrás do proxy): o 1.16.5 vai até o Java 16, o 1.20.5+, o Folia e o Velocity 3.4+ pedem o 21, o 26.x, o Velocity 4 e o PowerNukkitX 3 pedem o 25, e o Forge 1.12.2, o 8.
- `core/`: o laço (`Nucleo`), a config e o `/nevermiss` (`Laco`), o `executadas.txt` (`Registro`), o Floodgate (`Bedrock`), o kit e os comandos dele por versão (`Kit`), o `/kit` (`Kits`) e o `resgates.txt` (`Resgates`), sem servidor. `Jogo` é o que cada plataforma implementa: `bukkit/`, `velocity/`, `bungee/`, `fabric/`, `forge/` (Forge 1.16.5+ e NeoForge), `forge12/` e `nukkit/` (PowerNukkitX).
- `core/src/test/.../NucleoTest.java` e `KitTest.java`: o laço inteiro e o kit contra a API falsa (`ApiFalsa.java`), sem servidor, com o comando do kit de cada versão. `fabric/src/test` e `forge/src/test`: o mod com o Brigadier de verdade e um jogo de mentira.
- `teste/e2e.sh`: baixa o servidor da fonte oficial (PaperMC, Mojang, md_5, GeyserMC, Fabric, Forge, NeoForge, PowerNukkitX), sobe em `127.0.0.1` e confere entrega, comando proibido, `{uuid}`, teste do painel, comando que falha, entrega interrompida, confirmação perdida, a entrega Bedrock e a plataforma que vai para a API. No servidor de jogo, um jogador falso (`teste/cliente.py`, pirata) entra e recebe um kit (armadura de diamante no corpo, espada com Afiação V e 64 maçãs douradas), que o próprio jogo confere (`data get`, ou `testfor` antes do 1.13), e usa o `/kit` com intervalo; outro jogador, que não comprou, não pega; o kit com resgate para jogador Bedrock e o kit grande demais são recusados sem dar nada. No proxy, o kit que chega é recusado. Termina com `TUDO OK`.

## Licença

MIT. Veja [LICENSE](LICENSE).
