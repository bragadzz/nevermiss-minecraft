<?php

// O laço inteiro contra a API falsa (teste/api-falsa.mjs), sem servidor: como o NucleoTest.java.
// Uso: teste/unitario.sh (sobe a API falsa na 3971 e roda este arquivo). Termina com "TUDO OK".

declare(strict_types=1);

namespace nevermissapps\minecraft;

require __DIR__ . "/../src/Jogo.php";
require __DIR__ . "/../src/Registro.php";
require __DIR__ . "/../src/Nucleo.php";

const API = "http://127.0.0.1:3971";
define(__NAMESPACE__ . "\\CHAVE", "nmmc_" . str_repeat("e", 43));

final class JogoFalso implements Jogo{
	/** @var array<string, string> nome em minúsculas => uuid */
	public array $online = [];
	public bool $onlineMode = false;
	/** @var list<string> */
	public array $rodados = [];
	/** @var list<string> */
	public array $falham = [];
	/** @var list<string> */
	public array $logs = [];

	public function rodar(string $comando) : bool{
		$this->rodados[] = $comando;
		return !in_array(explode(" ", $comando)[0], $this->falham, true);
	}

	public function online(string $nome) : bool{
		return isset($this->online[strtolower($nome)]);
	}

	public function uuid(string $nome) : ?string{
		return $this->online[strtolower($nome)] ?? null;
	}

	public function onlineMode() : bool{
		return $this->onlineMode;
	}

	public function versao() : string{
		return "PocketMine-MP 5.44.3 (Bedrock v1.26.30)";
	}

	public function log(string $texto) : void{
		$this->logs[] = $texto;
	}

	public function aviso(string $texto) : void{
		$this->logs[] = "AVISO " . $texto;
	}

	public function post(string $url, array $cabecalhos, string $corpo, \Closure $pronto) : void{
		$pronto(...Nucleo::http($url, $cabecalhos, $corpo));
	}
}

function ok(bool $condicao, string $mensagem) : void{
	if(!$condicao){
		fwrite(STDERR, "FALHOU: $mensagem\n");
		exit(1);
	}
}

/** @param array<mixed>|list<array<mixed>> $corpo */
function controle(string $caminho, array $corpo = []) : array{
	[$status, $json] = Nucleo::http(API . $caminho, ["Content-Type: application/json"], json_encode($corpo, JSON_THROW_ON_ERROR));
	ok($status >= 200 && $status < 300, "API falsa respondeu $status em $caminho");
	return $json;
}

/** @return array{estado: string, motivo: ?string, tentativas: int, resultado: ?list<bool>} */
function estado(string $id) : array{
	return controle("/teste/estado")["entregas"][$id];
}

function volta(Nucleo $n) : int{
	$segundos = null;
	$n->ciclo(function(int $s) use (&$segundos) : void{
		$segundos = $s;
	});
	ok($segundos !== null, "o ciclo não terminou");
	return $segundos;
}

$pastas = [];
/** Um plugin novo (arquivo executadas.txt novo, a não ser que venha um) falando com a API falsa limpa. */
function plugin(JogoFalso $jogo, ?string $arquivo = null, bool $limpar = true) : Nucleo{
	global $pastas;
	if($limpar){
		controle("/teste/limpar");
	}
	if($arquivo === null){
		$pasta = sys_get_temp_dir() . "/nevermiss-teste-" . bin2hex(random_bytes(6));
		$pastas[] = $pasta;
		$arquivo = "$pasta/executadas.txt";
	}
	$n = new Nucleo($jogo, new Registro($arquivo), "1.1.0");
	$n->configurar(API, CHAVE);
	return $n;
}

function entrega(string $id, ?string $nick, array $comandos, array $extra = []) : void{
	controle("/teste/entregas", ["id" => $id, "nick" => $nick, "comandos" => $comandos] + $extra);
}

// 1) Entrega, o corpo do pull (contrato da onda 1) e o User-Agent.
$jogo = new JogoFalso();
$n = plugin($jogo);
entrega("mc1", "Steve", ["say oi Steve", "/give Steve diamond 2"]);
$s = volta($n);
ok($s === 30, "próxima consulta em $s s, esperava 30");
ok($jogo->rodados === ["say oi Steve", "give Steve diamond 2"], "comandos rodados: " . json_encode($jogo->rodados));
$e = estado("mc1");
ok($e["estado"] === "entregue" && $e["resultado"] === [true, true], "mc1: " . json_encode($e));
$api = controle("/teste/estado");
$servidor = $api["ultimoPull"]["servidor"];
ok($api["ultimoPull"]["versao"] === "1.1.0", "versão do plugin no pull");
ok($servidor["plataforma"] === "pocketmine" && $servidor["edicao"] === "bedrock" && array_key_exists("bedrock", $servidor) && $servidor["bedrock"] === null, "servidor no pull: " . json_encode($servidor));
ok($servidor["onlineMode"] === false && $servidor["versao"] === "PocketMine-MP 5.44.3 (Bedrock v1.26.30)", "versão/onlineMode no pull");
ok($api["agentes"] === ["NeverMissMC/1.1.0"], "User-Agent: " . json_encode($api["agentes"]));
ok($n->situacao === "Conectado" && $n->aConfirmar() === 0, "situação depois da entrega: {$n->situacao}");
echo "entrega: ok\n";

// 2) Teste do painel: nada roda.
$jogo = new JogoFalso();
$n = plugin($jogo);
entrega("mcteste", null, [], ["evento" => "teste"]);
volta($n);
ok($jogo->rodados === [] && in_array("Teste do painel recebido", $jogo->logs, true), "teste do painel");
ok(estado("mcteste")["estado"] === "entregue", "teste do painel não ficou entregue");
echo "teste do painel: ok\n";

// 3) Comando proibido: a entrega falha inteira e nada roda.
foreach([
	"op Steve", "/op Steve", "OP Steve", "minecraft:op Steve", "pocketmine:op Steve", "deop Steve", "stop", "eban Steve",
	"esudo Steve x", "whitelist off", "ban-ip 1.2.3.4", "\"op\" Steve", "'op' Steve", "\"\" op Steve", "/ op Steve",
	"op\u{00A0}Steve", "lp user Steve permission set *", "setuperm Steve \"*\"", "lp user Steve permission set essentials.*",
	"say a\nop Steve", "say \x07", "", "   ", "/", str_repeat("a", 257), "end", "shutdown", "greload",
] as $c){
	ok(Nucleo::bloqueado($c), "devia bloquear: " . json_encode($c));
}
foreach(["say oi", "give Steve diamond 1", "kit vip Steve", "e Steve", "economy give Steve 10", "tell Steve \"op\"", "say opa", "say a.b", str_repeat("a", 256)] as $c){
	ok(!Nucleo::bloqueado($c), "não devia bloquear: " . json_encode($c));
}
ok(Nucleo::bloqueado(null), "comando null devia bloquear");
$jogo = new JogoFalso();
$n = plugin($jogo);
entrega("mcbloq", "Steve", ["say isto nao pode rodar", "\"\" op Steve"]);
volta($n);
ok($jogo->rodados === [], "rodou parte da entrega bloqueada");
$e = estado("mcbloq");
ok($e["estado"] === "falhou" && $e["motivo"] === "bloqueado", "mcbloq: " . json_encode($e));
echo "comando proibido: ok\n";

// 4) Nick: Java com o regex de sempre, Bedrock com o do gamertag. O nome no PocketMine leva o espaço (entre aspas no comando).
$jogo = new JogoFalso();
$n = plugin($jogo);
entrega("mcnick1", "a b", ["say x"]);
entrega("mcnick2", "Steve\n", ["say x"]);
entrega("mcnick3", "1Steve", ["say x"], ["edicaoJogador" => "bedrock"]);
entrega("mcgt", "Some Name#1234", ["give Some_Name diamond 1", "say {nick} comprou", "tell Some_Names oi", "kit \"Some_Name\" vip", "tell \"{nick}\" oi"], ["edicaoJogador" => "bedrock"]);
entrega("mcgt2", "Alex#77", ["give Alex diamond 1"], ["edicaoJogador" => "bedrock"]);
volta($n);
foreach(["mcnick1", "mcnick2", "mcnick3"] as $id){
	$e = estado($id);
	ok($e["estado"] === "falhou" && $e["motivo"] === "bloqueado", "$id: " . json_encode($e));
}
ok($jogo->rodados === ["give \"Some Name\" diamond 1", "say \"Some Name\" comprou", "tell Some_Names oi", "kit \"Some Name\" vip", "tell \"Some Name\" oi", "give Alex diamond 1"], "gamertag: " . json_encode($jogo->rodados));
ok(estado("mcgt")["estado"] === "entregue" && estado("mcgt2")["estado"] === "entregue", "gamertag não ficou entregue");
echo "nick e gamertag: ok\n";

// 5) Só com o jogador online: Alex fora não recebe; quando entra, o pull leva o nick e a entrega roda.
$jogo = new JogoFalso();
$n = plugin($jogo);
entrega("mcalex", "Alex", ["give Alex diamond 1"], ["soOnline" => true]);
entrega("mcgtalex", "Big Alex#12", ["give Big_Alex diamond 1"], ["soOnline" => true, "edicaoJogador" => "bedrock"]);
volta($n);
ok($jogo->rodados === [], "rodou com o jogador fora");
ok(estado("mcalex")["estado"] === "pendente" && estado("mcalex")["tentativas"] === 0, "mcalex devia esperar: " . json_encode(estado("mcalex")));
ok(str_contains($n->status()[3], "Esperando o jogador entrar: 2"), "status: " . $n->status()[3]);
ok(!$n->aoEntrar("Steve") && $n->aoEntrar("alex") && $n->aoEntrar("BIG ALEX"), "aoEntrar");
$jogo->online["alex"] = "00000000-0000-3000-8000-000000000001";
$jogo->online["big alex"] = "00000000-0000-3000-8000-000000000002";
volta($n);
ok(controle("/teste/estado")["ultimoPull"]["online"] === ["Alex", "Big Alex#12"], "online no pull: " . json_encode(controle("/teste/estado")["ultimoPull"]["online"]));
ok($jogo->rodados === ["give Alex diamond 1", "give \"Big Alex\" diamond 1"], "só online: " . json_encode($jogo->rodados));
ok(estado("mcalex")["estado"] === "entregue" && estado("mcgtalex")["estado"] === "entregue", "só online não ficou entregue");
// Saiu antes de rodar: adiada (volta para a fila sem contar tentativa).
$r = $n->executar(["id" => "mcbob", "nick" => "Bob", "soOnline" => true, "teste" => false, "bedrock" => false, "comandos" => ["give Bob diamond 1"]]);
ok($r["estado"] === "adiada" && $r["comandos"] === null && $jogo->rodados === ["give Alex diamond 1", "give \"Big Alex\" diamond 1"], "adiada: " . json_encode($r));
// Até 1000 nicks esperando: o que passa disso não vai.
$jogo = new JogoFalso();
$n = plugin($jogo);
controle("/teste/ajustar", ["esperandoExtra" => 1500]);
volta($n);
ok(str_contains($n->status()[3], "Esperando o jogador entrar: 1000"), "esperando acima de 1000: " . $n->status()[3]);
for($i = 0; $i < 1500; $i++){
	$jogo->online["espera$i"] = "x";
}
$s = volta($n);
ok(count(controle("/teste/estado")["ultimoPull"]["online"]) === 1000, "mandou mais de 1000 online");
echo "só online: ok\n";

// 6) Confirmação perdida 2 vezes: a entrega volta (lease vencido) e o plugin só confirma, sem rodar de novo.
$jogo = new JogoFalso();
$n = plugin($jogo);
controle("/teste/ajustar", ["leaseMs" => 0]);
controle("/teste/cortar");
controle("/teste/cortar");
entrega("mc2", "Steve", ["give Steve diamond 1"]);
volta($n);
ok($n->aConfirmar() === 1 && estado("mc2")["estado"] === "entregando", "a confirmação cortada não ficou guardada");
volta($n);
$e = estado("mc2");
ok($e["estado"] === "entregue" && $e["tentativas"] === 2, "mc2: " . json_encode($e));
ok($jogo->rodados === ["give Steve diamond 1"], "rodou 2 vezes: " . json_encode($jogo->rodados));
ok($n->aConfirmar() === 0, "sobrou confirmação");
echo "confirmação perdida: ok\n";

// 7) O arquivo: um plugin novo (servidor reiniciado) lê o que já rodou e não roda de novo; o "rodando" sem "feita" vira interrompida.
$pasta = sys_get_temp_dir() . "/nevermiss-teste-" . bin2hex(random_bytes(6));
$pastas[] = $pasta;
mkdir($pasta);
$arquivo = "$pasta/executadas.txt";
$velho = Nucleo::agora() - 31 * 24 * 60 * 60 * 1000;
$linhas = ["mcinterrompida rodando " . Nucleo::agora(), "mcvelha rodando $velho", "mccortada rodan"];
for($i = 0; $i < 60; $i++){
	$linhas[] = "mclote$i rodando " . Nucleo::agora();
}
file_put_contents($arquivo, implode("\n", $linhas) . "\n");
$jogo = new JogoFalso();
controle("/teste/limpar");
$lote = [];
for($i = 0; $i < 60; $i++){
	$lote[] = ["id" => "mclote$i", "nick" => "Steve", "comandos" => ["say lote"]];
}
controle("/teste/entregas", $lote);
entrega("mcinterrompida", "Steve", ["say nao pode rodar"]);
$n = plugin($jogo, $arquivo, false);
ok($n->aConfirmar() === 61, "interrompidas na fila: " . $n->aConfirmar());
controle("/teste/ajustar", ["leaseMs" => 0]);
volta($n);
ok($jogo->rodados === [], "rodou uma interrompida: " . json_encode($jogo->rodados));
$e = estado("mcinterrompida");
ok($e["estado"] === "falhou" && $e["motivo"] === "interrompida", "mcinterrompida: " . json_encode($e));
ok(estado("mclote59")["motivo"] === "interrompida" && controle("/teste/estado")["confirmacoes"] === 2, "lote de 50 em 50");
$texto = (string) file_get_contents($arquivo);
ok(!str_contains($texto, "mcvelha") && !str_contains($texto, "mccortada"), "linha velha ou cortada ficou no arquivo");
// Reiniciado de novo: o feita da interrompida está no arquivo, nada volta como interrompida.
entrega("mc3", "Steve", ["give Steve diamond 3"]);
volta(plugin($jogo, $arquivo, false));
controle("/teste/limpar");
entrega("mc3", "Steve", ["give Steve diamond 3"]);
$n = plugin($jogo, $arquivo, false);
ok($n->aConfirmar() === 0, "interrompida voltou depois de confirmada");
volta($n);
ok($jogo->rodados === ["give Steve diamond 3"], "mc3 rodou de novo depois de reiniciar: " . json_encode($jogo->rodados));
ok(estado("mc3")["estado"] === "entregue", "mc3 não foi confirmada pelo arquivo");
echo "arquivo e interrompida: ok\n";

// 8) Comando que falha e {uuid}.
$jogo = new JogoFalso();
$jogo->falham = ["comandoquenaoexiste"];
$n = plugin($jogo);
entrega("mcfalha", "Steve", ["say antes", "comandoquenaoexiste Steve"]);
entrega("mcuuid", "Steve", ["lp user {uuid} parent add vip"]);
volta($n);
$e = estado("mcfalha");
ok($e["estado"] === "falhou" && $e["motivo"] === "comando_falhou" && $e["resultado"] === [true, false], "mcfalha: " . json_encode($e));
$e = estado("mcuuid");
ok($e["estado"] === "falhou" && $e["motivo"] === "uuid_pirata", "mcuuid pirata: " . json_encode($e));
$jogo = new JogoFalso();
$jogo->onlineMode = true;
$n = plugin($jogo);
entrega("mcuuidfora", "Steve", ["lp user {uuid} parent add vip"]);
volta($n);
$e = estado("mcuuidfora");
ok($e["estado"] === "falhou" && $e["motivo"] === "nick_nao_existe" && $jogo->rodados === [], "mcuuidfora: " . json_encode($e));
$jogo->online["steve"] = "2f9b1e6a-7c1d-3b8e-9a4f-0123456789ab";
entrega("mcuuidon", "Steve", ["lp user {uuid} parent add vip"]);
volta($n);
ok($jogo->rodados === ["lp user 2f9b1e6a-7c1d-3b8e-9a4f-0123456789ab parent add vip"], "uuid: " . json_encode($jogo->rodados));
echo "comando que falha e uuid: ok\n";

// 9) Erros da API e a rede fora.
$jogo = new JogoFalso();
$n = plugin($jogo);
$n->configurar(API, "nmmc_" . str_repeat("x", 43));
ok(volta($n) === 300 && str_contains($n->situacao, "Chave inválida"), "401: {$n->situacao}");
$n->configurar(API, CHAVE);
controle("/teste/ajustar", ["adicional" => false]);
ok(volta($n) === 300 && str_contains($n->situacao, "adicional"), "403: {$n->situacao}");
controle("/teste/ajustar", ["adicional" => true, "minima" => "9.0.0"]);
ok(volta($n) === 600 && str_contains($n->situacao, "mínima 9.0.0"), "426: {$n->situacao}");
controle("/teste/ajustar", ["minima" => "1.0.0", "intervaloMs" => 60000, "ultimaConsulta" => Nucleo::agora()]);
$s = volta($n);
ok($s >= 55 && $s <= 60, "429 com Retry-After: $s");
controle("/teste/ajustar", ["intervaloMs" => 0, "pausado" => true]);
entrega("mcpausa", "Steve", ["say pausa"]);
volta($n);
ok($n->pausado && $n->situacao === "Entregas pausadas pela NeverMiss" && $jogo->rodados === [] && estado("mcpausa")["estado"] === "pendente", "pausado");
$n->configurar("http://127.0.0.1:1", CHAVE);
ok(volta($n) === 30 && volta($n) === 60 && volta($n) === 120 && volta($n) === 240 && volta($n) === 300, "espera com a rede fora");
ok(str_starts_with($n->situacao, "Sem resposta da NeverMiss"), "rede fora: {$n->situacao}");
$n->configurar(null, null);
ok(volta($n) === -1, "sem chave o laço para");
echo "erros: ok\n";

// 10) Config: só https (http só em localhost), e a chave nunca aparece inteira.
ok(Nucleo::validarApi("https://api-website.nevermissapps.com/") === "https://api-website.nevermissapps.com", "https");
ok(Nucleo::validarApi("http://127.0.0.1:3971") === "http://127.0.0.1:3971" && Nucleo::validarApi("http://localhost:1") !== null, "http local");
foreach(["http://exemplo.com", "ftp://127.0.0.1", "127.0.0.1:3971", "", "http://127.0.0.1.exemplo.com"] as $u){
	ok(Nucleo::validarApi($u) === null, "devia recusar $u");
}
ok(preg_match(Nucleo::CHAVE, CHAVE . "\n") === 0 && preg_match(Nucleo::CHAVE, CHAVE) === 1, "regex da chave");
$n = plugin(new JogoFalso());
ok(str_contains($n->status()[1], "****eeee") && !str_contains(implode(" ", $n->status()), CHAVE), "status mostra a chave");
echo "config: ok\n";

foreach($pastas as $p){
	foreach(glob("$p/*") ?: [] as $f){
		unlink($f);
	}
	@rmdir($p);
}
echo "TUDO OK\n";
