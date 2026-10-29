<?php

declare(strict_types=1);

namespace nevermissapps\minecraft;

use pocketmine\scheduler\AsyncTask;

/** O POST para a NeverMiss numa thread do PocketMine, para não travar o servidor. A resposta volta na thread principal. */
final class Requisicao extends AsyncTask{
	private const PRONTO = "pronto";

	private string $url;
	private string $cabecalhos;
	private string $corpo;

	/**
	 * @param list<string> $cabecalhos
	 * @param \Closure(array{0: int, 1: array<mixed>, 2: ?string, 3: ?string}) : void $pronto
	 */
	public function __construct(string $url, array $cabecalhos, string $corpo, \Closure $pronto){
		$this->url = $url;
		$this->cabecalhos = json_encode($cabecalhos, JSON_THROW_ON_ERROR);
		$this->corpo = $corpo;
		$this->storeLocal(self::PRONTO, $pronto);
	}

	public function onRun() : void{
		// Erro aqui derruba o servidor inteiro (o PocketMine 5 trata a thread que caiu como crash): vira "sem resposta".
		try{
			$cabecalhos = json_decode($this->cabecalhos, true, 4, JSON_THROW_ON_ERROR);
			$this->setResult(Nucleo::http($this->url, $cabecalhos, $this->corpo));
		}catch(\Throwable $e){
			$this->setResult([0, [], null, $e->getMessage()]);
		}
	}

	public function onCompletion() : void{
		$pronto = $this->fetchLocal(self::PRONTO);
		$pronto($this->getResult());
	}
}
