<?php

declare(strict_types=1);

namespace nevermissapps\minecraft;

/**
 * O laço de entrega, o mesmo do plugin Java: pergunta à API se há entregas, roda os comandos no console e confirma.
 * Não conhece o PocketMine (ver Jogo), para dar para testar sem servidor. Roda todo na thread principal; só o HTTP sai dela.
 */
final class Nucleo{
	public const API_PADRAO = "https://api-website.nevermissapps.com";
	public const CHAVE = '/^nmmc_[A-Za-z0-9_-]{43}$/D';
	public const NICK = '/^[A-Za-z0-9_]{3,16}$/D';
	// O gamertag do Xbox: de 1 a 16 letras, números e espaço, começa com letra, sem espaço duplo nem nas pontas.
	// O gamertag novo vem com "#1234": passa, e o nome no servidor não leva o sufixo (a mesma regra da API).
	public const GAMERTAG = '/^(?=[^#]{1,16}(?:#|$))[A-Za-z][A-Za-z0-9]*(?: [A-Za-z0-9]+)*(?:#[0-9]{1,4})?$/D';
	private const ID = '/^[A-Za-z0-9_-]{1,64}$/D';
	// A mesma lista da API. Nem o painel nem a API desligam esta checagem. O EssentialsX aceita cada um com "e" na frente.
	private const PROIBIDOS = [
		"op", "deop", "stop", "restart", "reload", "rl", "ban", "ban-ip", "banip", "ipban", "tempban",
		"tempbanip", "tempipban", "pardon", "pardon-ip", "pardonip", "unban", "unbanip", "whitelist",
		"execute", "sudo", "plugman", "end", "shutdown", "greload",
	];
	private const MAX_COMANDOS = 100;
	// Quantos nicks esperando o jogador entrar a API manda (e o plugin devolve os que estão online).
	private const MAX_ESPERANDO = 1000;
	private const MAX_LOTE = 50;

	/** @var array<string, array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}> */
	private array $aConfirmar = [];
	private ?string $api = null;
	private ?string $chave = null;
	/** @var list<string> */
	private array $esperando = [];
	private int $falhasSeguidas = 0;

	public string $situacao = "Ainda não consultou a NeverMiss";
	public int $ultimoContato = 0;
	public int $ultimaConsulta = 0;
	public bool $pausado = false;

	public function __construct(
		private Jogo $jogo,
		private Registro $registro,
		private string $versao
	){
		// O servidor caiu entre o "rodando" e o "feita": não roda de novo, avisa a falha.
		foreach($registro->interrompidas() as $id){
			$this->enfileirar($this->gravar(self::falhou($id, "interrompida")));
		}
	}

	public static function agora() : int{
		return (int) (microtime(true) * 1000);
	}

	/** Chave null para o laço (sem chave ou config errada). */
	public function configurar(?string $api, ?string $chave) : void{
		$this->api = $api;
		$this->chave = $chave;
	}

	public function configurado() : bool{
		return $this->chave !== null;
	}

	/** Só https. http só em localhost, para teste. Devolve null se não serve. */
	public static function validarApi(?string $texto) : ?string{
		if($texto === null){
			return null;
		}
		$limpo = rtrim(trim($texto), "/");
		$u = parse_url($limpo);
		if(!is_array($u) || !isset($u["scheme"], $u["host"]) || $u["host"] === ""){
			return null;
		}
		$esquema = strtolower($u["scheme"]);
		$host = strtolower($u["host"]);
		if($esquema === "https" || ($esquema === "http" && ($host === "localhost" || $host === "127.0.0.1"))){
			return $limpo;
		}
		return null;
	}

	public static function bloqueado(?string $comando) : bool{
		if($comando === null || self::semBarra($comando) === "" || mb_strlen($comando, "UTF-8") > 256){
			return true;
		}
		// Caractere de controle ou texto que não é UTF-8 (o preg_match devolve false).
		if(preg_match('/\p{Cc}/u', $comando) !== 0){
			return true;
		}
		$texto = strtolower(self::semBarra($comando));
		// Duas leituras: a da API (palavras separadas por espaço, sem as aspas das pontas) e a do PocketMine, que entende aspas:
		// nele `"" op Steve` roda o op.
		$palavras = [];
		foreach(preg_split('/[\s\p{Z}]+/u', $texto) ?: [] as $p){
			$palavras[] = trim($p, "\"'");
		}
		$doPocketMine = self::argumentos($texto);
		foreach([$palavras[0] ?? "", $doPocketMine[0] ?? ""] as $primeira){
			$doisPontos = strpos($primeira, ":");
			if($doisPontos !== false){
				$primeira = substr($primeira, $doisPontos + 1);
			}
			if(in_array($primeira, self::PROIBIDOS, true) || (str_starts_with($primeira, "e") && in_array(substr($primeira, 1), self::PROIBIDOS, true))){
				return true;
			}
		}
		foreach([...$palavras, ...$doPocketMine] as $p){
			if($p === "*" || str_ends_with($p, ".*")){
				return true;
			}
		}
		return false;
	}

	/**
	 * Os argumentos como o console do PocketMine lê a linha (CommandStringHelper::parseQuoteAware): o que está entre aspas é um
	 * argumento só, e o argumento vazio ("") some.
	 *
	 * @return list<string>
	 */
	private static function argumentos(string $linha) : array{
		preg_match_all('/"((?:\\\\.|[^\\\\"])*)"|(\S+)/u', $linha, $m);
		$args = [];
		foreach($m[0] as $k => $_){
			$a = $m[1][$k] !== "" ? $m[1][$k] : $m[2][$k];
			if($a !== ""){
				$args[] = preg_replace('/\\\\([\\\\"])/u', '$1', $a) ?? $a;
			}
		}
		return $args;
	}

	private static function semBarra(string $comando) : string{
		$c = trim($comando);
		return str_starts_with($c, "/") ? substr($c, 1) : $c;
	}

	/** O nome do jogador no servidor: o gamertag sem o sufixo "#1234". O nick do Java fica como veio. */
	private static function nome(string $nick) : string{
		$h = strpos($nick, "#");
		return $h === false ? $nick : substr($nick, 0, $h);
	}

	/**
	 * O {nick} vira o nome do jogador, entre aspas quando tem espaço (o console do PocketMine entende aspas).
	 * A API troca o espaço do gamertag por "_" (o nome que o Floodgate dá no Java); no PocketMine o nome tem o espaço,
	 * então a palavra "Nome_Com_Espaco" volta a ser "Nome Com Espaco", entre aspas. O lojista que já pôs aspas ("{nick}")
	 * não ganha aspas dobradas.
	 */
	private static function comNome(string $comando, string $nome) : string{
		if(!str_contains($nome, " ")){
			return str_replace("{nick}", $nome, $comando);
		}
		$certo = "\"$nome\"";
		$daApi = str_replace(" ", "_", $nome);
		$palavras = explode(" ", str_replace(["\"{nick}\"", "{nick}"], $certo, $comando));
		foreach($palavras as $i => $p){
			if($p === $daApi || $p === "\"$daApi\""){
				$palavras[$i] = $certo;
			}
		}
		return implode(" ", $palavras);
	}

	/** Um jogador entrou: se há entrega esperando por ele, o plugin adianta a consulta. */
	public function aoEntrar(string $nome) : bool{
		foreach($this->esperando as $n){
			if(strcasecmp(self::nome($n), $nome) === 0){
				return true;
			}
		}
		return false;
	}

	/** A lista "esperando" traz o nick como foi comprado (o gamertag pode vir com o "#1234"). */
	private function online(string $nick) : bool{
		return $this->jogo->online(self::nome($nick));
	}

	/** Uma volta do laço. Chama $fim uma vez só, com os segundos até a próxima (-1 = parar). */
	public function ciclo(\Closure $fim) : void{
		$chamou = false;
		$fim = function(int $segundos) use (&$chamou, $fim) : void{
			if(!$chamou){
				$chamou = true;
				$fim($segundos);
			}
		};
		if($this->chave === null){
			$fim(-1);
			return;
		}
		$this->confirmar(function() use ($fim) : void{
			$this->ultimaConsulta = self::agora();
			$enviados = [];
			foreach($this->esperando as $n){
				if(count($enviados) < self::MAX_ESPERANDO && $this->online($n)){
					$enviados[] = $n;
				}
			}
			$this->post("/v1/minecraft/fila", $this->corpoFila($enviados), $fim, function(int $status, array $json, ?string $retry, ?string $erro) use ($fim, $enviados) : void{
				if($status === 0){
					$fim($this->foraDoAr("Sem resposta da NeverMiss (" . ($erro ?? "?") . ")"));
					return;
				}
				if($status !== 200){
					$fim($this->erro($status, $json, $retry));
					return;
				}
				$this->falhasSeguidas = 0;
				$this->ultimoContato = self::agora();
				$pausa = ($json["pausado"] ?? false) === true;
				if($pausa && !$this->pausado){
					$this->jogo->aviso("Entregas pausadas pela NeverMiss.");
				}
				$this->pausado = $pausa;
				$this->esperando = self::textos($json["esperando"] ?? null);
				$this->situacao = $pausa ? "Entregas pausadas pela NeverMiss" : "Conectado";
				foreach($this->lerEntregas($json["entregas"] ?? null) as $e){
					$this->enfileirar($this->executar($e));
				}
				$this->confirmar(function() use ($fim, $enviados, $json) : void{
					foreach($this->esperando as $n){
						if(!in_array($n, $enviados, true) && $this->online($n)){
							$fim(5);
							return;
						}
					}
					$fim(max(10, min(300, self::inteiro($json["proximaEm"] ?? null, 30))));
				}, $fim);
			});
		}, $fim);
	}

	/**
	 * @param array{id: string, nick: ?string, soOnline: bool, teste: bool, bedrock: bool, comandos: list<?string>} $e
	 * @return array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}
	 */
	public function executar(array $e) : array{
		$id = $e["id"];
		$guardado = $this->registro->feita($id);
		if($guardado !== null){
			return $guardado;
		}
		if($this->registro->interrompida($id)){
			return $this->gravar(self::falhou($id, "interrompida"));
		}
		if($e["teste"] || $e["comandos"] === []){
			if($e["teste"]){
				$this->jogo->log("Teste do painel recebido");
			}
			return self::entregue($id, []);
		}
		$nick = $e["nick"];
		if($nick !== null && preg_match($e["bedrock"] ? self::GAMERTAG : self::NICK, $nick) !== 1){
			$this->jogo->aviso("Entrega $id recusada: nick inválido");
			return self::falhou($id, "bloqueado");
		}
		if(count($e["comandos"]) > self::MAX_COMANDOS){
			return self::falhou($id, "bloqueado");
		}
		$nome = $nick === null ? null : self::nome($nick);
		$prontos = [];
		foreach($e["comandos"] as $c){
			$p = $c !== null && $nome !== null ? self::comNome($c, $nome) : $c;
			if(self::bloqueado($p)){
				$this->jogo->aviso("Entrega $id recusada: comando proibido: " . ($p ?? "?"));
				return self::falhou($id, "bloqueado");
			}
			$prontos[] = self::semBarra((string) $p);
		}
		if($e["soOnline"] && $nome !== null && !$this->jogo->online($nome)){
			return self::adiada($id);
		}

		if(self::precisaUuid($prontos)){
			if(!$this->jogo->onlineMode()){
				return self::falhou($id, "uuid_pirata");
			}
			// O PocketMine só sabe o UUID de quem está no servidor.
			$uuid = $nome === null ? null : $this->jogo->uuid($nome);
			if($uuid === null){
				$this->jogo->aviso("Entrega $id: o {uuid} só sai com o jogador online. Ligue \"Só com o jogador online\" no produto e reenvie.");
				return self::falhou($id, "nick_nao_existe");
			}
			foreach($prontos as $i => $p){
				$prontos[$i] = str_replace("{uuid}", $uuid, $p);
			}
		}

		try{
			$this->registro->marcarRodando($id);
		}catch(\Exception $x){
			$this->jogo->aviso("Não consegui gravar executadas.txt, nada rodou: " . $x->getMessage());
			return self::falhou($id, "erro");
		}
		$ok = [];
		$tudo = true;
		foreach($prontos as $p){
			try{
				$rodou = $this->jogo->rodar($p);
			}catch(\Throwable $x){
				$this->jogo->aviso("Erro no comando: " . $x->getMessage());
				$rodou = false;
			}
			$this->jogo->log(($rodou ? "Rodou: " : "Falhou: ") . $p);
			$ok[] = $rodou;
			$tudo = $tudo && $rodou;
		}
		return $this->gravar($tudo ? self::entregue($id, $ok) : self::falhou($id, "comando_falhou", $ok));
	}

	/**
	 * Manda as confirmações guardadas, 50 por vez, e depois chama $depois. Se a API não responder, ficam para a próxima volta.
	 * O arquivo é que garante que nada roda 2 vezes.
	 */
	public function confirmar(\Closure $depois, \Closure $fim) : void{
		$lote = array_slice($this->aConfirmar, 0, self::MAX_LOTE, true);
		if($lote === [] || $this->chave === null){
			$depois();
			return;
		}
		$itens = [];
		foreach($lote as $r){
			$itens[] = self::json($r);
		}
		$this->post("/v1/minecraft/confirmar", ["resultados" => $itens], $fim, function(int $status, array $json) use ($lote, $depois, $fim) : void{
			if($status === 400){
				$this->jogo->aviso("A NeverMiss recusou uma confirmação: " . (self::texto($json["error"] ?? null) ?? "?"));
			}elseif($status !== 200){
				$depois();
				return;
			}
			foreach($lote as $id => $r){
				if(($this->aConfirmar[$id] ?? null) === $r){
					unset($this->aConfirmar[$id]);
				}
			}
			$this->confirmar($depois, $fim);
		});
	}

	public function aConfirmar() : int{
		return count($this->aConfirmar);
	}

	/** @return list<string> */
	public function status() : array{
		$c = $this->chave;
		return [
			"NeverMiss {$this->versao} (pocketmine): {$this->situacao}",
			"Chave: " . ($c === null ? "nenhuma" : "****" . substr($c, -4)) . " · API: " . ($this->api ?? "?"),
			"Último contato: " . ($this->ultimoContato === 0 ? "nunca" : "há " . intdiv(self::agora() - $this->ultimoContato, 1000) . " s"),
			"Esperando o jogador entrar: " . count($this->esperando) . " · A confirmar: " . count($this->aConfirmar),
		];
	}

	/**
	 * Faz o POST e entrega a resposta a $pronto. Um erro dentro de $pronto termina a volta (o laço não para).
	 *
	 * @param array<string, mixed> $corpo
	 */
	private function post(string $caminho, array $corpo, \Closure $fim, \Closure $pronto) : void{
		$cabecalhos = [
			"Authorization: Bearer " . $this->chave,
			"User-Agent: NeverMissMC/" . $this->versao,
			"Content-Type: application/json; charset=utf-8",
			"Accept: application/json",
			"Expect:",
		];
		$texto = json_encode($corpo, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE | JSON_THROW_ON_ERROR);
		$this->jogo->post($this->api . $caminho, $cabecalhos, $texto, function(int $status, array $json, ?string $retry, ?string $erro) use ($pronto, $fim) : void{
			try{
				$pronto($status, $json, $retry, $erro);
			}catch(\Throwable $x){
				$this->jogo->aviso("Erro na consulta à NeverMiss: " . $x->getMessage());
				$fim(60);
			}
		});
	}

	/**
	 * O POST de verdade, com curl. Roda fora da thread principal (no PocketMine, numa AsyncTask).
	 * Sem seguir redirecionamento (a chave não vai para outro endereço) e sempre conferindo o certificado.
	 *
	 * @param list<string> $cabecalhos
	 * @return array{0: int, 1: array<mixed>, 2: ?string, 3: ?string} status (0 = sem resposta), JSON, Retry-After e o erro
	 */
	public static function http(string $url, array $cabecalhos, string $corpo) : array{
		$retry = null;
		$ch = curl_init($url);
		$opcoes = [
			CURLOPT_POST => true,
			CURLOPT_POSTFIELDS => $corpo,
			CURLOPT_HTTPHEADER => $cabecalhos,
			CURLOPT_RETURNTRANSFER => true,
			CURLOPT_FOLLOWLOCATION => false,
			CURLOPT_SSL_VERIFYPEER => true,
			CURLOPT_SSL_VERIFYHOST => 2,
			CURLOPT_CONNECTTIMEOUT => 10,
			CURLOPT_TIMEOUT => 20,
			CURLOPT_HEADERFUNCTION => function($ch, string $linha) use (&$retry) : int{
				if(stripos($linha, "retry-after:") === 0){
					$retry = trim(substr($linha, 12));
				}
				return strlen($linha);
			},
		];
		$ca = self::certificados();
		if($ca !== null){
			$opcoes[CURLOPT_CAINFO] = $ca;
		}
		curl_setopt_array($ch, $opcoes);
		$texto = curl_exec($ch);
		if(!is_string($texto)){
			return [0, [], null, curl_error($ch)];
		}
		$json = json_decode($texto, true);
		return [(int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE), is_array($json) ? $json : [], $retry, null];
	}

	/**
	 * O PHP do PocketMine nem sempre acha os certificados do sistema. Sem eles o https não conecta (e nunca roda sem conferir).
	 * null = o curl.cainfo do php.ini, que o curl já usa. Os outros vão explícitos: o curl do PHP do pmmp nem sempre lê o openssl.cafile.
	 */
	private static function certificados() : ?string{
		$ini = ini_get("curl.cainfo");
		if(is_string($ini) && $ini !== "" && is_file($ini)){
			return null;
		}
		$openssl = openssl_get_cert_locations();
		foreach([$openssl["ini_cafile"] ?? "", $openssl["default_cert_file"] ?? "", "/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt", "/etc/ssl/cert.pem", "/etc/ssl/ca-bundle.pem"] as $f){
			if(is_string($f) && $f !== "" && is_file($f)){
				return $f;
			}
		}
		return null;
	}

	/** @param array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>} $r */
	private function enfileirar(array $r) : void{
		$this->aConfirmar[$r["id"]] = $r;
	}

	/**
	 * @param array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>} $r
	 * @return array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}
	 */
	private function gravar(array $r) : array{
		try{
			$this->registro->marcarFeita($r);
		}catch(\Exception $x){
			$this->jogo->aviso("Não consegui gravar executadas.txt: " . $x->getMessage());
		}
		return $r;
	}

	/** @param array<mixed> $json */
	private function erro(int $status, array $json, ?string $retry) : int{
		return match($status){
			401 => $this->parar("Chave inválida ou revogada. Gere outra no painel.", 300),
			403 => $this->parar("O adicional Minecraft não está ativo nesta loja.", 300),
			426 => $this->parar("Atualize o plugin NeverMiss (mínima " . (self::texto($json["minima"] ?? null) ?? "?") . ").", 600),
			429 => max(5, min(300, self::inteiro($retry, 5))),
			default => $this->foraDoAr("A NeverMiss respondeu $status"),
		};
	}

	private function parar(string $motivo, int $segundos) : int{
		$this->situacao = $motivo;
		$this->jogo->aviso($motivo);
		return $segundos;
	}

	private function foraDoAr(string $motivo) : int{
		$this->situacao = $motivo;
		if($this->falhasSeguidas === 0){
			$this->jogo->aviso("$motivo. Tento de novo em 30 s.");
		}
		$this->falhasSeguidas++;
		return min(300, 30 << min(4, $this->falhasSeguidas - 1));
	}

	/**
	 * @param list<string> $online
	 * @return array<string, mixed>
	 */
	private function corpoFila(array $online) : array{
		return [
			"versao" => $this->versao,
			"servidor" => [
				"versao" => mb_substr($this->jogo->versao(), 0, 80, "UTF-8"),
				"onlineMode" => $this->jogo->onlineMode(),
				"plataforma" => "pocketmine",
				"edicao" => "bedrock",
				"bedrock" => null,
			],
			"online" => $online,
		];
	}

	/** @return list<array{id: string, nick: ?string, soOnline: bool, teste: bool, bedrock: bool, comandos: list<?string>}> */
	private function lerEntregas(mixed $lista) : array{
		$entregas = [];
		if(!is_array($lista)){
			return $entregas;
		}
		foreach($lista as $o){
			if(!is_array($o)){
				continue;
			}
			$id = self::texto($o["id"] ?? null);
			if($id === null || preg_match(self::ID, $id) !== 1){
				$this->jogo->aviso("Entrega com id inválido ignorada");
				continue;
			}
			$comandos = [];
			foreach(is_array($o["comandos"] ?? null) ? $o["comandos"] : [] as $c){
				$comandos[] = is_string($c) ? $c : null;
			}
			$entregas[] = [
				"id" => $id,
				"nick" => self::texto($o["nick"] ?? null),
				"soOnline" => ($o["soOnline"] ?? false) === true,
				"teste" => ($o["evento"] ?? null) === "teste",
				"bedrock" => ($o["edicaoJogador"] ?? null) === "bedrock",
				"comandos" => $comandos,
			];
		}
		return $entregas;
	}

	/** @param list<string> $comandos */
	private static function precisaUuid(array $comandos) : bool{
		foreach($comandos as $c){
			if(str_contains($c, "{uuid}")){
				return true;
			}
		}
		return false;
	}

	/**
	 * @param array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>} $r
	 * @return array<string, mixed>
	 */
	private static function json(array $r) : array{
		$o = ["id" => $r["id"], "estado" => $r["estado"]];
		if($r["motivo"] !== null){
			$o["motivo"] = $r["motivo"];
		}
		if($r["comandos"] !== null){
			$o["comandos"] = $r["comandos"];
		}
		return $o;
	}

	/**
	 * @param list<bool> $comandos
	 * @return array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}
	 */
	private static function entregue(string $id, array $comandos) : array{
		return ["id" => $id, "estado" => "entregue", "motivo" => null, "comandos" => $comandos];
	}

	/**
	 * @param ?list<bool> $comandos
	 * @return array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}
	 */
	private static function falhou(string $id, string $motivo, ?array $comandos = null) : array{
		return ["id" => $id, "estado" => "falhou", "motivo" => $motivo, "comandos" => $comandos];
	}

	/**
	 * O jogador não está online: volta para a fila sem contar como falha.
	 *
	 * @return array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}
	 */
	private static function adiada(string $id) : array{
		return ["id" => $id, "estado" => "adiada", "motivo" => null, "comandos" => null];
	}

	private static function texto(mixed $v) : ?string{
		return is_string($v) || is_int($v) || is_float($v) ? (string) $v : null;
	}

	private static function inteiro(mixed $v, int $padrao) : int{
		if(is_int($v)){
			return $v;
		}
		return is_string($v) && preg_match('/^\s*-?\d{1,9}\s*$/D', $v) === 1 ? (int) trim($v) : $padrao;
	}

	/** @return list<string> */
	private static function textos(mixed $v) : array{
		$l = [];
		foreach(is_array($v) ? $v : [] as $x){
			if(count($l) < self::MAX_ESPERANDO && is_string($x)){
				$l[] = $x;
			}
		}
		return $l;
	}
}
