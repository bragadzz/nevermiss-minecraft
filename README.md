# NeverMiss para Minecraft

Plugin que entrega no seu servidor o que você vende pela NeverMiss, no Discord e na loja do site.
Você escreve no painel os comandos de cada produto; o plugin roda esses comandos no console quando a venda é paga.

- Spigot e Paper (e os forks deles), do 1.8.8 em diante, num jar só (Java 8 ou mais novo). Testado no Paper 1.8.8 e 1.20.4.
- Servidor original e pirata (offline-mode).
- Código aberto, licença MIT.

## Instalar

1. No painel da NeverMiss, em **Minecraft › Servidores**, toque em **Adicionar servidor**. A chave aparece **uma vez só**: copie.
2. Ponha o `NeverMiss-1.0.0.jar` na pasta `plugins/` e ligue o servidor.
3. No console do servidor: `nevermiss chave <a chave>`.
4. Em até 1 minuto o painel mostra **Conectado**.

Confira o SHA-256 do jar antes de instalar (`shasum -a 256 NeverMiss-1.0.0.jar` ou `certutil -hashfile NeverMiss-1.0.0.jar SHA256`). Ele tem que bater com o que a NeverMiss publica. Se não bater, não instale.

### Onde a chave pode ficar

| Onde | Como |
|---|---|
| Console | `nevermiss chave nmmc_...` (só no console, nunca no chat) |
| `plugins/NeverMiss/config.yml` | `chave: "nmmc_..."` e depois `/nevermiss recarregar` |
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

- A cada 30 s o plugin pergunta à NeverMiss se há entregas para este servidor. Não precisa abrir porta: é o servidor que chama.
- Cada entrega roda os comandos no console, na ordem, e o console mostra `[NeverMiss] Rodou: <comando>`.
- **Nunca roda 2 vezes.** Antes do primeiro comando, o id da entrega vai para `plugins/NeverMiss/executadas.txt`. Se a confirmação se perder, a entrega volta e o plugin só confirma de novo. Se o servidor cair no meio, a entrega aparece no painel como falha (**interrompida**), com o botão **Reenviar**. Não apague esse arquivo.
- **NeverMiss fora do ar:** o plugin tenta de novo em 30 s, 60 s, até 5 min. O que já rodou fica guardado e é confirmado quando ela voltar.
- **Só com o jogador online** (opção do produto): a entrega espera o jogador entrar e roda logo depois.
- **Prazo (VIP de 30 dias, por exemplo):** use o prazo do próprio comando (`lp user {nick} parent addtemp vip 30d accumulate`) ou o "Tirar depois de N dias" do painel.

### Variáveis dos comandos

| Variável | Vira |
|---|---|
| `{nick}` | o nick que o comprador digitou (3 a 16 letras, números ou `_`) |
| `{uuid}` | o UUID do jogador. **Só em servidor original**: em servidor pirata a entrega falha (`uuid_pirata`) |
| `{quantidade}` | quantas unidades foram compradas |
| `{pedido}` | o número do pedido |
| `{produto}` | o nome do produto |

Em servidor pirata, o comprador é identificado pelo nick. Um nick digitado errado entrega para outra pessoa, por isso o checkout pede o nick igual ao do jogo.

## Segurança

- **Comandos proibidos**, recusados aqui e na NeverMiss (o painel não desliga): `op`, `deop`, `stop`, `restart`, `reload`, `rl`, `ban`, `ban-ip`, `banip`, `pardon`, `pardon-ip`, `unban`, `whitelist`, `execute`, `sudo`, `plugman`, também com prefixo (`minecraft:op`), e qualquer permissão curinga (`*` ou `algo.*`). A entrega com um deles falha inteira (`bloqueado`) e nada roda.
- **Do comprador só vem o nick**, num formato fechado e conferido de novo aqui. Fora do formato, a entrega é recusada.
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

## Compilar e testar

Precisa de JDK 17 (o jar sai para Java 8).

```sh
./gradlew build          # jar em build/libs, roda os testes
teste/e2e.sh 1.20.4      # servidor Paper de verdade + API falsa
teste/e2e.sh 1.8.8
```

- `src/test/.../NucleoTest.java`: o laço inteiro contra a API falsa (`ApiFalsa.java`), sem servidor.
- `teste/e2e.sh`: baixa o Paper da PaperMC (o Paper baixa o servidor oficial da Mojang na primeira vez), sobe um servidor pirata em `127.0.0.1` e confere entrega, comando proibido, `{uuid}` em pirata, teste do painel, comando que falha, entrega interrompida e confirmação perdida. Termina com `TUDO OK`.

## Licença

MIT. Veja [LICENSE](LICENSE).
