# NeverMiss para Minecraft

Plugin que entrega no seu servidor o que você vende pela NeverMiss, no Discord e na loja do site.
Você escreve no painel os comandos de cada produto; o plugin roda esses comandos no console quando a venda é paga.
Ou monta um **Kit da NeverMiss** no painel: o plugin põe os itens no inventário do comprador, sem precisar de outro plugin.

- Servidor de jogo (Spigot, Paper, Folia e forks) e proxy (Velocity, BungeeCord e Waterfall).
- Jogadores Java e Bedrock (pelo Geyser + Floodgate).
- Servidor original e pirata (offline-mode).
- Código aberto, licença MIT.

## Plataformas

| Onde | Jar | Versões | Testado em |
|---|---|---|---|
| Spigot, Paper e forks | `NeverMiss-Bukkit-1.2.0.jar` | 1.7.10 ao mais novo (26.x), Java 8 ou mais novo | Paper 1.7.10, 1.8.8, 1.12.2, 1.16.5, 1.20.4, 1.21.11 e 26.3 |
| Folia | `NeverMiss-Bukkit-1.2.0.jar` (o mesmo) | 1.19.4 em diante | Folia 1.21.11 |
| Fabric (só no servidor, sem o Fabric API: o jar vai em `mods/`, a chave em `config/nevermiss/config.yml`, e o `/nevermiss` é de op nível 3) | `NeverMiss-Fabric-1.16.5-26.x-1.2.0.jar` | 1.16.5 ao 26.x, um jar só, Fabric Loader 0.14 ou mais novo | ainda não rodou num servidor Fabric de verdade: só nos testes sem servidor e conferido com os nomes do jogo de cada versão |
| Velocity (proxy) | `NeverMiss-Velocity-1.2.0.jar` | 3.1.1 em diante | Velocity 3.5.1 e 4.3.1 |
| BungeeCord e Waterfall (proxy) | `NeverMiss-Bungee-1.2.0.jar` | API do 1.16 em diante | BungeeCord build 2107 e Waterfall 1.21 |
| Bedrock (Geyser + Floodgate) | qualquer um dos três | Floodgate 2.x | Paper 1.21.11 com Geyser 2.12.1 e Floodgate 2.2.5; Velocity e BungeeCord com Floodgate 2.2.5 |

O Kit da NeverMiss (abaixo) foi testado com um jogador no servidor em Paper 1.7.10, 1.8.8, 1.12.2, 1.16.5, 1.20.4, 1.21.11 e 26.3, e o catálogo inteiro de itens do painel em Paper 1.8.8, 1.12.2, 1.20.4 e 1.21.11.

O Java é o que o seu servidor já pede: o plugin roda em todos.

## Instalar

1. No painel da NeverMiss, em **Minecraft › Servidores**, toque em **Adicionar servidor**. A chave aparece **uma vez só**: copie.
2. Ponha o jar da sua plataforma (tabela acima) na pasta `plugins/` e reinicie.
3. No console (do servidor ou do proxy): `nevermiss chave <a chave>`.
4. Em até 1 minuto o painel mostra **Conectado**.

Confira o SHA-256 do jar antes de instalar (`shasum -a 256 NeverMiss-Bukkit-1.2.0.jar` ou `certutil -hashfile NeverMiss-Bukkit-1.2.0.jar SHA256`). Ele tem que bater com o que a NeverMiss publica. Se não bater, não instale.

Quem tem o `NeverMiss-1.0.0.jar` ou o `NeverMiss-Bukkit-1.1.0.jar`: apague ele e ponha o `NeverMiss-Bukkit-1.2.0.jar`. A pasta `plugins/NeverMiss` (chave e `executadas.txt`) continua a mesma.

### Onde a chave pode ficar

| Onde | Como |
|---|---|
| Console | `nevermiss chave nmmc_...` (só no console, nunca no chat) |
| `config.yml` (`plugins/NeverMiss/`, ou `plugins/nevermiss/` no Velocity) | `chave: "nmmc_..."` e depois `nevermiss recarregar` |
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
- **`/kit <nome>`** (ou **`/nmkit <nome>`**, se o servidor já tem o `/kit` do EssentialsX): o comprador pega o kit de novo depois do intervalo, até o fim do prazo. O kit da compra conta como o primeiro. `/kit` sozinho lista os kits dele com o tempo que falta. Quem comprou duas vezes tem dois resgates, cada um com o seu intervalo. Permissão `nevermiss.kit` (todos têm).
- O kit sai **como está no painel na hora**: editar o kit vale para as próximas entregas e resgates.
- **Versão:** o item usa o nome de cada versão (do 1.13 em diante o moderno, `diamond_sword`; antes, o antigo). O item que não existe na versão do servidor (netherite no 1.8) é pulado; se nenhum existir, a entrega falha (`kit_incompativel`).
- **Onde:** só no Spigot/Paper (do 1.7.10 ao 26.x). O Folia ainda não dá kit, e o proxy (Velocity, BungeeCord) não tem inventário: a NeverMiss não manda kit para eles. Jogador Bedrock (Geyser) recebe o kit da compra normalmente; o `/kit` dele ainda não funciona.
- **Nunca dá 2 vezes:** o kit da compra segue o `executadas.txt`, como os comandos. A hora de cada `/kit` vai para o `resgates.txt` (na pasta do plugin) antes de dar os itens. Não apague esse arquivo: sem ele o intervalo zera.
- Por dentro, o plugin dá cada item com o comando do próprio jogo (`give` e `item replace`, ou o `replaceitem` antes do 1.17, sempre como `minecraft:give`, para o `/give` de outro plugin não pegar). No 1.7.10 e no 1.8, pela API do Bukkit.

## Comandos

Permissão `nevermiss.admin` (vem para op; no proxy, dê pelo plugin de permissão do proxy). O console sempre pode.

| Comando | O que faz |
|---|---|
| `nevermiss status` | Plataforma, conexão, último contato, entregas esperando o jogador, confirmações pendentes e Bedrock |
| `nevermiss testar` | Consulta a NeverMiss agora e responde se conectou |
| `nevermiss recarregar` | Relê o `config.yml` |
| `nevermiss chave <chave>` | Grava a chave (só no console) |
| `kit [nome]` e `nmkit [nome]` | Do jogador, permissão `nevermiss.kit`: lista ou pega os kits da NeverMiss dele (acima) |

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

## Compilar e testar

Precisa de JDK 17 (os jars saem para Java 8; o do Velocity, para Java 11).

```sh
./gradlew build                        # os 3 jars em bukkit/, velocity/ e bungee/build/libs; roda os testes do core
teste/e2e.sh paper 1.20.4              # servidor de verdade + API falsa
teste/e2e.sh paper 1.21.11 bedrock     # com Geyser e Floodgate
teste/e2e.sh folia 1.21.11
teste/e2e.sh velocity 3.5.1            # proxy + um Paper atrás, com o plugin do servidor e outra chave
teste/e2e.sh waterfall 1.21
teste/e2e.sh bungeecord latest bedrock # com o Floodgate no proxy
```

- Cada versão pede o seu Java (`JAVA=/caminho/do/java`; `JAVA_BACKEND` para o Paper atrás do proxy): o 1.16.5 vai até o Java 16, o 1.20.5+, o Folia e o Velocity 3.4+ pedem o 21, e o 26.x e o Velocity 4 pedem o 25.
- `core/`: o laço (`Nucleo`), a config e o `/nevermiss` (`Laco`), o `executadas.txt` (`Registro`), o Floodgate (`Bedrock`), o kit e os comandos dele por versão (`Kit`), o `/kit` (`Kits`) e o `resgates.txt` (`Resgates`), sem servidor. `Jogo` é o que cada plataforma implementa.
- `core/src/test/.../NucleoTest.java` e `KitTest.java`: o laço inteiro e o kit contra a API falsa (`ApiFalsa.java`), sem servidor, com o comando do kit de cada versão.
- `teste/e2e.sh`: baixa o servidor da fonte oficial (PaperMC, Mojang, md_5, GeyserMC), sobe em `127.0.0.1` e confere entrega, comando proibido, `{uuid}`, teste do painel, comando que falha, entrega interrompida, confirmação perdida, a entrega Bedrock e a plataforma que vai para a API. No Spigot/Paper, um jogador falso (`teste/cliente.py`, pirata) entra e recebe um kit (armadura de diamante no corpo, espada com Afiação V e 64 maçãs douradas), que o próprio jogo confere (`data get`, ou `testfor` antes do 1.13), e usa o `/kit` com intervalo; outro jogador, que não comprou, não pega. Termina com `TUDO OK`.

## Licença

MIT. Veja [LICENSE](LICENSE).
