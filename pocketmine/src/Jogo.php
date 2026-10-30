<?php

declare(strict_types=1);

namespace nevermissapps\minecraft;

/** O que o núcleo usa do servidor. O plugin implementa com o PocketMine; o teste, com um falso. Tudo na thread principal. */
interface Jogo{
	/** Roda um comando no console. */
	public function rodar(string $comando) : bool;

	/** Não diferencia maiúsculas. */
	public function online(string $nome) : bool;

	/** O UUID do jogador online (Player::getUniqueId). null = não está no servidor. */
	public function uuid(string $nome) : ?string;

	/** O xbox-auth do server.properties. */
	public function onlineMode() : bool;

	public function versao() : string;

	public function log(string $texto) : void;

	public function aviso(string $texto) : void;

	/**
	 * POST fora da thread principal. $pronto volta na principal com o que Nucleo::http devolve.
	 *
	 * @param list<string> $cabecalhos
	 * @param \Closure(int, array<mixed>, ?string, ?string) : void $pronto
	 */
	public function post(string $url, array $cabecalhos, string $corpo, \Closure $pronto) : void;
}
