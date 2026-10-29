# NeverMiss para PocketMine-MP (Bedrock)

Plugin que entrega no seu servidor PocketMine-MP o que você vende pela NeverMiss, no Discord e na loja do site.
É o mesmo plugin do Java (mesma chave, mesmo painel, mesmas proteções), feito para o servidor Bedrock puro.

- PocketMine-MP 5 (PHP 8.1 ou mais novo). Feito para o 5.44.3.
- Servidor com e sem `xbox-auth`.
- Código aberto, licença MIT.

## Instalar

1. No painel da NeverMiss, em **Minecraft › Servidores**, toque em **Adicionar servidor**. A chave aparece **uma vez só**: copie.
2. Ponha o `NeverMiss-PocketMine-1.1.0.phar` na pasta `plugins/` e ligue o servidor.
3. No console do servidor: `nevermiss chave <a chave>`.
4. Em até 1 minuto o painel mostra **Conectado**.

Confira o SHA-256 do phar antes de instalar (`shasum -a 256 NeverMiss-PocketMine-1.1.0.phar` ou
`certutil -hashfile NeverMiss-PocketMine-1.1.0.phar SHA256`). Ele tem que bater com o que a NeverMiss publica. Se não bater, não instale.

### Onde a chave pode ficar

| Onde | Como |
|---|---|
| Console | `nevermiss chave nmmc_...` (só no console, nunca no chat) |
| `plugin_data/NeverMiss/config.yml` | `chave: "nmmc_..."` e depois `/nevermiss recarregar` |
| Variável de ambiente | `NEVERMISS_CHAVE=nmmc_...` (usada quando o `config.yml` está sem chave) |

A chave só lê e confirma as entregas **deste** servidor. Vazou? Troque no painel (**Trocar chave**): a antiga para na hora.

## Comandos

Permissão `nevermiss.admin` (vem para op).

| Comando | O que faz |
|---|---|
| `/nevermiss status` | Conexão, último contato, entregas esperando o jogador e confirmações pendentes |
| `/nevermiss testar` | Consulta a NeverMiss agora e responde se conectou |
| `/nevermiss recarregar` | Relê o `config.yml` |
| `nevermiss chave <chave>` | Grava a chave (só no console) |

O botão **Testar** do painel manda uma entrega de teste: o console mostra `Teste do painel recebido` e nenhum comando roda.

## Como funciona

- A cada 30 s o plugin pergunta à NeverMiss se há entregas para este servidor, numa thread à parte (não trava o servidor).
  Não precisa abrir porta: é o servidor que chama.
- Cada entrega roda os comandos no console, na ordem, e o console mostra `[NeverMiss] Rodou: <comando>`.
- **Nunca roda 2 vezes.** Antes do primeiro comando, o id da entrega vai para `plugin_data/NeverMiss/executadas.txt`. Se a
  confirmação se perder, a entrega volta e o plugin só confirma de novo. Se o servidor cair no meio, a entrega aparece no painel
  como falha (**interrompida**), com o botão **Reenviar**. Não apague esse arquivo.
- **NeverMiss fora do ar:** o plugin tenta de novo em 30 s, 60 s, até 5 min. O que já rodou fica guardado e é confirmado quando
  ela voltar.
- **Só com o jogador online** (opção do produto): a entrega espera o jogador entrar e roda logo depois.

### O comprador e as variáveis

O jogador Bedrock compra com o **gamertag do Xbox** (de 1 a 16 letras, números e espaço, começando com letra; o `#1234` do
gamertag novo é aceito e fica de fora do nome no servidor). É o nome que o PocketMine mostra.

| Variável | Vira |
|---|---|
| `{nick}` | o gamertag (sem o `#1234`). Com espaço, vai **entre aspas** (`give "Some Name" diamond 1`), que é como o console do PocketMine lê um nome com espaço |
| `{uuid}` | o UUID que o PocketMine dá ao jogador (`Player::getUniqueId()`, não o XUID). **Só com `xbox-auth=on`** (sem ele a entrega falha com `uuid_pirata`) e **só com o jogador no servidor**: ligue "Só com o jogador online" no produto |
| `{quantidade}` | quantas unidades foram compradas |
| `{pedido}` | o número do pedido |
| `{produto}` | o nome do produto |

Sem `xbox-auth`, qualquer um entra com qualquer nome: um gamertag digitado errado (ou copiado) entrega para outra pessoa.

## Segurança

- **Comandos proibidos**, recusados aqui e na NeverMiss (o painel não desliga): `op`, `deop`, `stop`, `restart`, `reload`, `rl`,
  `ban`, `ban-ip`, `banip`, `ipban`, `tempban`, `tempbanip`, `tempipban`, `pardon`, `pardon-ip`, `pardonip`, `unban`, `unbanip`,
  `whitelist`, `execute`, `sudo`, `plugman`, também com prefixo (`pocketmine:op`), com o `e` na frente (`eban`), entre aspas
  (`"op"`, `"" op`), e a palavra `*` ou `algo.*` sozinha. A linha é lida do jeito que o console do PocketMine lê (com aspas). A
  entrega com um deles falha inteira (`bloqueado`) e nada roda. É uma lista de bloqueio, não uma garantia: um comando que dá cargo
  de staff passa. Quem decide os comandos é você, no painel, com 2FA.
- **Do comprador só vem o nick**, num formato fechado, conferido no checkout, na NeverMiss e de novo aqui antes de rodar.
- **O plugin nunca baixa nem roda código.** Da NeverMiss chegam só os textos dos comandos que você salvou no painel.
- **O console nunca sai do servidor.** O plugin manda só se cada comando deu certo (sim/não) e um motivo de uma lista fechada.
- **Só HTTPS, com o certificado conferido.** `http://` só em `localhost` e `127.0.0.1`, para teste. Redirecionamentos são
  recusados, para a chave não ir parar em outro endereço.
- A chave não aparece no log, só os 4 últimos caracteres.

## Problemas comuns

| No console | O que fazer |
|---|---|
| `Chave inválida ou revogada` | Gere outra no painel e use `nevermiss chave` |
| `O adicional Minecraft não está ativo nesta loja` | Contrate ou renove o adicional no painel |
| `Atualize o plugin NeverMiss` | Baixe a versão nova |
| `Sem resposta da NeverMiss (SSL certificate problem...)` | O PHP do servidor não achou os certificados: configure `curl.cainfo` no `php.ini` |
| `o {uuid} só sai com o jogador online` | Ligue "Só com o jogador online" no produto e use **Reenviar** |

## Montar e testar

Precisa de Node 22 ou mais novo (o phar é montado sem PHP, sempre igual).

```sh
node build.mjs           # build/NeverMiss-PocketMine-1.1.0.phar e o SHA-256
teste/unitario.sh        # o laço contra a API falsa, sem servidor
teste/e2e.sh             # PocketMine-MP 5 de verdade + API falsa
```

- `build.mjs`: os arquivos em ordem, com data fixa e a assinatura SHA-256 do phar. Os mesmos arquivos dão o mesmo SHA-256.
- `teste/nucleo-teste.php` (pelo `teste/unitario.sh`): o laço inteiro contra a API falsa (`teste/api-falsa.mjs`, porta 3971).
- `teste/e2e.sh`: baixa o `PocketMine-MP.phar` 5.44.3 e o PHP 8.2 do pmmp para macOS (com o SHA-256 conferido), sobe um
  servidor em `127.0.0.1` (UDP 19132 e 19133) e confere entrega, comando proibido, `{uuid}` sem xbox-auth, teste do painel,
  comando que falha, gamertag com espaço, entrega interrompida, jogador offline esperando e confirmação perdida. Termina com `TUDO OK`.

## Licença

MIT. Veja [LICENSE](../LICENSE).
