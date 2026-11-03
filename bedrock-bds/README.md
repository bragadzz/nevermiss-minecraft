# NeverMiss para o Bedrock Dedicated Server (BDS)

Addon (behavior pack) que entrega no seu servidor Bedrock oficial da Mojang o que você vende pela NeverMiss, no Discord e na
loja do site. É o mesmo plugin do Java e do PocketMine (mesma chave, mesmo painel, mesmas proteções), feito com a Script API
do BDS.

- **BDS** oficial, no Windows e no Linux. Feito para o 1.26.61 (Script API `@minecraft/server` 2.9.0, `@minecraft/server-net`
  e `@minecraft/server-admin` 1.0.0-beta).
- **Endstone** e **LeviLamina** rodam em cima do BDS e carregam behavior packs do mesmo jeito: instale na pasta do BDS que eles
  usam, igual abaixo (não testado neles).
- Código aberto, licença MIT.

## Instalar

São 6 passos com o servidor **parado**. `<mundo>` é o `level-name` do `server.properties` (o padrão é `Bedrock level`).

1. No painel da NeverMiss, em **Minecraft › Servidores**, toque em **Adicionar servidor**. A chave aparece **uma vez só**: copie.
2. O `NeverMiss-BDS-1.2.0.mcpack` é um zip. Extraia o conteúdo em `behavior_packs/NeverMiss/` (tem que ficar
   `behavior_packs/NeverMiss/manifest.json`). No Windows, renomeie para `.zip` e use **Extrair tudo**; no Linux,
   `unzip NeverMiss-BDS-1.2.0.mcpack -d behavior_packs/NeverMiss`.
3. Ligue o pack no mundo: em `worlds/<mundo>/world_behavior_packs.json` (crie se não existir) ponha
   ```json
   [{ "pack_id": "7e641d0b-ac2a-4a7b-a20e-a23ce2ba1080", "version": [1, 2, 0] }]
   ```
   Se o arquivo já tem outros packs, acrescente esse item na lista.
4. Ligue o experimento **Beta APIs** no mundo. O HTTP (`@minecraft/server-net`) e os segredos (`@minecraft/server-admin`) só
   existem em beta na Script API. Dois jeitos:
   - no Minecraft (Windows, celular, console): **Editar mundo › Experimentos › Beta APIs**, e copie o mundo para `worlds/`;
   - ou, com Python 3: `python3 ligar-beta-apis.py "worlds/<mundo>/level.dat"` (no Windows, `py` no lugar de `python3`).
     Ele guarda uma cópia em `level.dat.antes-da-nevermiss`.
5. Libere a internet **só para este addon**: crie a pasta `config/4b243aaa-c498-417e-ad97-32a2c199f055/` (o id do script do
   addon) e, dentro dela, um `permissions.json` com
   ```json
   { "allowed_modules": ["@minecraft/server", "@minecraft/server-net", "@minecraft/server-admin"] }
   ```
   Os outros addons do servidor continuam sem internet, porque o `config/default/permissions.json` não muda. O
   `permissions.json` da raiz (op e membro dos jogadores) também não muda. Se o console disser que o `@minecraft/server-net`
   não é permitido, acrescente `"@minecraft/server-net"` em `allowed_modules` no `config/default/permissions.json`. Assim ele
   vale para todos os addons do servidor.
6. A chave: na mesma pasta, crie o `secrets.json` com
   ```json
   { "chave": "nmmc_SUA_CHAVE" }
   ```
   A chave como o painel mostra (com `Bearer ` na frente também vale). O BDS guarda o segredo fora do mundo e o addon só
   consegue pôr ele no cabeçalho da chamada à NeverMiss: não tem como ler, mostrar nem mandar a chave para o log.

Ligue o servidor (Windows: `bedrock_server.exe`; Linux: `LD_LIBRARY_PATH=. ./bedrock_server`). O console mostra
`[NeverMiss] Chave no secrets.json. Primeira consulta em 5 s.` e, em até 1 minuto, o painel mostra **Conectado**.

Confira o SHA-256 do mcpack antes de instalar (`sha256sum NeverMiss-BDS-1.2.0.mcpack` ou
`certutil -hashfile NeverMiss-BDS-1.2.0.mcpack SHA256`). Ele tem que bater com o que a NeverMiss publica. Se não bater, não
instale.

A chave só lê e confirma as entregas **deste** servidor. Vazou? Troque no painel (**Trocar chave**), ponha a nova no
`secrets.json` e reinicie o servidor.

## Comandos

| No console (ou um op no jogo) | O que faz |
|---|---|
| `nevermiss status` | Conexão, último contato, entregas esperando o jogador e confirmações pendentes |
| `nevermiss testar` | Consulta a NeverMiss agora e responde se conectou |
| `nevermiss recarregar` | Lê de novo o `secrets.json` e o `variables.json` carregados pelo BDS (mudou o arquivo? reinicie) |

O mesmo funciona como `scriptevent nevermiss:status`, `scriptevent nevermiss:testar` e `scriptevent nevermiss:recarregar`.
O botão **Testar** do painel manda uma entrega de teste: o console mostra `Teste do painel recebido` e nenhum comando roda.

Para os jogadores: `/kit` lista os kits da NeverMiss que a pessoa comprou e `/kit <nome>` pega um (o `/nmkit` é o mesmo, para
o servidor em que outro addon já usa o `/kit`).

## Como funciona

- A cada 30 s o addon pergunta à NeverMiss se há entregas para este servidor. O HTTP não trava o servidor. Não precisa abrir
  porta: é o servidor que chama.
- Cada entrega roda os comandos no console (`runCommand` da dimensão), na ordem, e o console mostra
  `[NeverMiss] Rodou: <comando>`. Comando que não existe, com a sintaxe errada ou que não fez nada (um `give` para quem não está
  no servidor) conta como falha.
- **Nunca roda 2 vezes.** Antes do primeiro comando, o addon avisa a NeverMiss que começou e só roda com a resposta dela;
  o id da entrega também fica gravado no próprio mundo (dynamic properties do addon). Se a confirmação se perder, a entrega
  volta e o addon só confirma o que guardou. O BDS grava o mundo de tempos em tempos: se ele cair (queda de luz, `kill`) no meio, o
  mundo pode perder o registro e ficar com o efeito do comando. A NeverMiss não perde o "começou": a entrega volta marcada, o
  addon não roda de novo e ela fica **interrompida** no painel, para você conferir no jogo e usar **Reenviar** se não chegou.
- **NeverMiss sem responder ao "começou":** nada roda e a entrega sai de novo em até 2 minutos.
- **NeverMiss fora do ar:** o addon tenta de novo em 30 s, 60 s, até 5 min. O que já rodou fica guardado e é confirmado
  quando ela voltar.
- **Só com o jogador online** (opção do produto): a entrega espera o jogador entrar e roda logo depois.

### O comprador e as variáveis

O jogador compra com o **gamertag do Xbox** (de 1 a 16 letras, números e espaço, começando com letra, sem o `#1234` do
gamertag novo). É o nome que o BDS mostra.

| Variável | Vira |
|---|---|
| `{nick}` | o gamertag. Com espaço, vai **entre aspas** (`give "Some Name" diamond 1`), que é como o console do Bedrock lê um nome com espaço (com `"{nick}"` no painel dá o mesmo) |
| `{uuid}` | **não existe no BDS**: a Script API não dá o UUID nem o XUID do jogador. O painel não salva `{uuid}` num servidor BDS; o produto salvo antes de o servidor conectar falha (`uuid_pirata`) e o console explica. Use `{nick}` |
| `{quantidade}` | quantas unidades foram compradas |
| `{pedido}` | o número do pedido |
| `{produto}` | o nome do produto |

### Kit da NeverMiss

O kit montado no painel é dado pelo addon, sem outro addon de kit: no espaço que você escolheu, a armadura no corpo (com
"Equipar a armadura sozinho"), o escudo ou o totem na mão secundária e, sem lugar, no chão aos pés do jogador.

- O item vai pelo id do painel no Bedrock (`minecraft:diamond_sword`). Item que não existe no Bedrock é pulado; se nenhum
  existir, a entrega falha (`kit_incompativel`) e nada roda.
- O Bedrock não aceita encantamento acima do nível máximo: fica no máximo (Afiação 10 vira Afiação 5). Os que não existem no
  Bedrock (Varredura) são pulados. A mão secundária só aceita o que o Bedrock deixa (escudo, totem, flecha, mapa, foguete); o
  resto vai para o inventário.
- Nome e descrição com `&` para cor viram as cores do Bedrock.

## Segurança

- **Comandos proibidos**, recusados aqui e na NeverMiss (o painel não desliga): `op`, `deop`, `stop`, `restart`, `reload`,
  `rl`, `ban`, `ban-ip`, `banip`, `ipban`, `tempban`, `tempbanip`, `tempipban`, `pardon`, `pardon-ip`, `pardonip`, `unban`,
  `unbanip`, `whitelist`, `allowlist` (o whitelist do BDS), `execute`, `sudo`, `plugman`, `end`, `shutdown`, `greload`,
  `velocity`, também com prefixo (`minecraft:op`), com o `e` na frente (`eban`), entre aspas (`"op"`, `"" op`), com mais de uma
  barra (`//op`), colado no alvo (`op@a`), e a palavra `*` ou `algo.*` sozinha. A entrega com um deles falha inteira
  (`bloqueado`) e nada roda. É uma lista de bloqueio, não uma garantia: um comando que dá cargo de staff passa. Quem decide os
  comandos é você, no painel, com 2FA.
- **Do comprador só vem o nick**, num formato fechado, conferido no checkout, na NeverMiss e de novo aqui antes de rodar.
- **O addon nunca baixa nem roda código.** Da NeverMiss chegam só os textos dos comandos que você salvou no painel e os itens
  do kit.
- **O console nunca sai do servidor.** O addon manda só se cada comando deu certo (sim/não) e um motivo de uma lista fechada.
- **Só HTTPS** (quem faz a conexão é o próprio BDS). `http://` só em `localhost` e `127.0.0.1`, para teste, pelo `api` do
  `config/4b243aaa-c498-417e-ad97-32a2c199f055/variables.json`.
- A chave fica no `secrets.json` e o addon não tem como ler: não aparece no log nem no `nevermiss status`.

## Problemas comuns

| No console | O que fazer |
|---|---|
| Nada da NeverMiss ao ligar | Confira os passos 2 e 3; com `content-log-console-output-enabled=true` no `server.properties` o BDS mostra o erro do addon |
| Erro do `@minecraft/server-net` ou do `@minecraft/server-admin` | Faltou o passo 4 (Beta APIs) ou o 5 (`permissions.json` na pasta do addon) |
| `Sem chave` | O `secrets.json` não está em `config/4b243aaa-c498-417e-ad97-32a2c199f055/` (passo 6) |
| `Chave inválida ou revogada` | Gere outra no painel (**Trocar chave**), ponha no `secrets.json` e reinicie o servidor |
| `a NeverMiss não confirmou o início` | Nada rodou. A entrega sai de novo em até 2 minutos; se repetir, confira a internet do servidor |
| `O adicional Minecraft não está ativo nesta loja` | Contrate ou renove o adicional no painel |
| `Atualize o addon NeverMiss` | Baixe a versão nova |
| `o {uuid} não existe no BDS` | Troque `{uuid}` por `{nick}` no produto e use **Reenviar** |

## Montar e testar

Precisa de Node 22.2 ou mais novo (o mcpack é montado sem compressão e com data fixa, sempre igual; o `.gitattributes` mantém as
linhas em LF, senão um clone no Windows daria outro SHA-256).

```sh
node build.mjs              # build/NeverMiss-BDS-1.2.0.mcpack e o SHA-256
node teste/nucleo-teste.mjs # o laço contra a API falsa (porta 4011), sem servidor
teste/vm-subir.sh           # Mac com Apple Silicon: a VM Linux com o BDS oficial
teste/e2e.sh                # BDS de verdade + API falsa; termina com TUDO OK
teste/vm-apagar.sh          # apaga a VM, o Lima, os downloads e os caches
```

- `teste/nucleo-teste.mjs`: o laço inteiro (entrega, comando proibido, gamertag, só online, até 1000 esperando, confirmação
  perdida, registro e interrompida, a queda que o mundo não gravou (o início na API), o início sem resposta, `{uuid}`, kit,
  `/kit`, erros da API, a chave sem o `Bearer`, só https) contra a `teste/api-falsa.mjs`.
- `teste/vm-subir.sh`: o BDS só roda em Linux x86_64 e Windows. No Mac sobe um Ubuntu 24.04 arm64 no Lima 2.2.1 (vz, 2 CPUs,
  3 GB) com o Rosetta rodando o `bedrock_server` x86_64, tudo em `~/frentes-lojas/mbd-vm`, com o SHA-256 conferido. Baixar o
  BDS é aceitar a [EULA do Minecraft](https://minecraft.net/eula) e a política de privacidade da Microsoft.
- `teste/e2e.sh`: a API falsa no Mac (só no 127.0.0.1; a VM chega por um túnel SSH reverso), o BDS na VM (UDP 19140 e 19141) e
  confere entrega, comando proibido, `{uuid}`, teste do painel, comando que falha, gamertag com espaço, jogador offline
  esperando, `nevermiss status`/`testar` no console, confirmação perdida, reinício sem repetir, queda (`kill -9`) sem rodar de
  novo (a entrega fica interrompida, ou entregue se o mundo guardou o registro) e a chave fora do log. Sem cliente Bedrock
  ninguém entra no servidor: o que depende do jogador online (só online, kit, `/kit`) fica provado pelo
  `teste/nucleo-teste.mjs`.

## Licença

MIT. Veja [LICENSE](../LICENSE).
