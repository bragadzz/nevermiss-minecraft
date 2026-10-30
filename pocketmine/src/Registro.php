<?php

declare(strict_types=1);

namespace nevermissapps\minecraft;

/**
 * plugin_data/NeverMiss/executadas.txt: os ids que já rodaram neste servidor.
 * É o que garante que uma entrega nunca roda 2 vezes, mesmo que a confirmação se perca.
 * Mesmo formato do plugin Java: "<id> rodando <ms>" e "<id> feita <ms> <estado> <motivo|-> <1 e 0 por comando|->".
 */
final class Registro{
	private const GUARDAR_MS = 30 * 24 * 60 * 60 * 1000;

	/** @var array<string, true> */
	private array $rodando = [];
	/** @var array<string, array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}> */
	private array $feitas = [];
	/** @var resource */
	private $saida;

	public function __construct(string $arquivo){
		$limite = Nucleo::agora() - self::GUARDAR_MS;
		$manter = [];
		if(file_exists($arquivo)){
			// Arquivo que existe e não abre: parar. Seguir com ele vazio faria rodar de novo o que já rodou.
			$texto = file_get_contents($arquivo);
			if($texto === false){
				throw new \RuntimeException("não consegui ler $arquivo");
			}
			foreach(explode("\n", $texto) as $linha){
				$linha = trim($linha);
				$p = explode(" ", $linha);
				if(count($p) < 3 || !ctype_digit($p[2]) || (int) $p[2] < $limite){
					continue;
				}
				if($p[1] === "rodando"){
					$this->rodando[$p[0]] = true;
				}elseif($p[1] === "feita" && count($p) >= 6){
					$this->feitas[$p[0]] = self::deLinha($p);
				}else{
					continue; // linha cortada por uma queda: o "rodando" só é gravado inteiro antes de rodar
				}
				$manter[] = $linha;
			}
		}
		$pasta = dirname($arquivo);
		if(!is_dir($pasta) && !mkdir($pasta, 0777, true)){
			throw new \RuntimeException("não consegui criar $pasta");
		}
		$tmp = $arquivo . ".tmp";
		if(file_put_contents($tmp, $manter === [] ? "" : implode("\n", $manter) . "\n") === false || !rename($tmp, $arquivo)){
			throw new \RuntimeException("não consegui reescrever $arquivo");
		}
		$saida = fopen($arquivo, "ab");
		if($saida === false){
			throw new \RuntimeException("não consegui abrir $arquivo");
		}
		$this->saida = $saida;
	}

	/** @return array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>}|null */
	public function feita(string $id) : ?array{
		return $this->feitas[$id] ?? null;
	}

	/** Começou a rodar e não terminou: o servidor caiu no meio. */
	public function interrompida(string $id) : bool{
		return isset($this->rodando[$id]) && !isset($this->feitas[$id]);
	}

	/** @return list<string> */
	public function interrompidas() : array{
		$ids = [];
		foreach($this->rodando as $id => $_){
			if(!isset($this->feitas[$id])){
				$ids[] = (string) $id;
			}
		}
		return $ids;
	}

	/** Vai para o disco (com fsync) antes do primeiro comando rodar. */
	public function marcarRodando(string $id) : void{
		$this->gravar("$id rodando " . Nucleo::agora());
		$this->rodando[$id] = true;
	}

	/** @param array{id: string, estado: string, motivo: ?string, comandos: ?list<bool>} $r */
	public function marcarFeita(array $r) : void{
		$this->feitas[$r["id"]] = $r;
		$bits = "";
		foreach($r["comandos"] ?? [] as $ok){
			$bits .= $ok ? "1" : "0";
		}
		$this->gravar($r["id"] . " feita " . Nucleo::agora() . " " . $r["estado"] . " " . ($r["motivo"] ?? "-") . " " . ($bits === "" ? "-" : $bits));
	}

	public function fechar() : void{
		fclose($this->saida);
	}

	private function gravar(string $linha) : void{
		$linha .= "\n";
		if(fwrite($this->saida, $linha) !== strlen($linha) || !fflush($this->saida) || !fsync($this->saida)){
			throw new \RuntimeException("não consegui gravar executadas.txt");
		}
	}

	/**
	 * @param list<string> $p
	 * @return array{id: string, estado: string, motivo: ?string, comandos: list<bool>}
	 */
	private static function deLinha(array $p) : array{
		$comandos = [];
		if($p[5] !== "-"){
			foreach(str_split($p[5]) as $b){
				$comandos[] = $b === "1";
			}
		}
		return ["id" => $p[0], "estado" => $p[3], "motivo" => $p[4] === "-" ? null : $p[4], "comandos" => $comandos];
	}
}
