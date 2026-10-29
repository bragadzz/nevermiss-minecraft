<?php

declare(strict_types=1);

namespace nevermissapps\minecraft;

use pocketmine\command\Command;
use pocketmine\command\CommandSender;
use pocketmine\console\ConsoleCommandSender;
use pocketmine\event\Listener;
use pocketmine\event\player\PlayerJoinEvent;
use pocketmine\player\Player;
use pocketmine\plugin\PluginBase;
use pocketmine\scheduler\ClosureTask;
use pocketmine\scheduler\TaskHandler;

final class NeverMiss extends PluginBase implements Listener, Jogo{
	private Registro $registro;
	private Nucleo $nucleo;
	private ConsoleCommandSender $console;
	private ?TaskHandler $proxima = null;
	private bool $emCiclo = false;
	private bool $deNovo = false;
	private ?CommandSender $pediuTeste = null;

	protected function onEnable() : void{
		$this->saveDefaultConfig();
		try{
			$this->registro = new Registro($this->getDataFolder() . "executadas.txt");
		}catch(\RuntimeException $e){
			$this->getLogger()->critical("Não consegui abrir executadas.txt (" . $e->getMessage() . "). Nada vai ser entregue.");
			$this->getServer()->getPluginManager()->disablePlugin($this);
			return;
		}
		$this->console = new ConsoleCommandSender($this->getServer(), $this->getServer()->getLanguage());
		$this->nucleo = new Nucleo($this, $this->registro, $this->getDescription()->getVersion());
		$this->getServer()->getPluginManager()->registerEvents($this, $this);
		$this->configurar();
	}

	protected function onDisable() : void{
		if(isset($this->registro)){
			$this->registro->fechar();
		}
	}

	private function configurar() : void{
		$this->reloadConfig();
		$lido = $this->getConfig()->get("chave", "");
		$chave = is_string($lido) ? trim($lido) : "";
		$origem = "config.yml";
		$env = getenv("NEVERMISS_CHAVE");
		if($chave === "" && is_string($env) && trim($env) !== ""){
			$chave = trim($env);
			$origem = "variável NEVERMISS_CHAVE";
		}
		$lido = $this->getConfig()->get("api", Nucleo::API_PADRAO);
		$api = Nucleo::validarApi(is_string($lido) ? $lido : null);
		$erro = match(true){
			$api === null => "O endereço em 'api' do config.yml precisa ser https.",
			$chave === "" => "Sem chave. No console: nevermiss chave <chave do painel>",
			preg_match(Nucleo::CHAVE, $chave) !== 1 => "Essa chave não é da NeverMiss (começa com nmmc_).",
			default => null,
		};
		if($erro !== null || $api === null){
			$this->nucleo->configurar($api, null);
			$this->nucleo->situacao = $erro ?? "";
			$this->getLogger()->warning($erro ?? "");
			$this->cancelar();
			return;
		}
		if(str_starts_with($api, "http:")){
			$this->getLogger()->warning("API sem https em $api: use só para teste.");
		}
		$this->nucleo->configurar($api, $chave);
		$this->getLogger()->info("Chave ****" . substr($chave, -4) . " ($origem). Primeira consulta em 5 s.");
		$this->agendar(5);
	}

	private function cancelar() : void{
		$this->proxima?->cancel();
		$this->proxima = null;
	}

	private function agendar(int $segundos) : void{
		$this->cancelar();
		if($this->isEnabled()){
			$this->proxima = $this->getScheduler()->scheduleDelayedTask(new ClosureTask(function() : void{
				$this->volta();
			}), max(1, $segundos) * 20);
		}
	}

	/** Uma volta por vez. */
	private function volta() : void{
		$this->proxima = null;
		if($this->emCiclo){
			$this->deNovo = true;
			return;
		}
		$this->emCiclo = true;
		try{
			$this->nucleo->ciclo(function(int $segundos) : void{
				$this->fimDaVolta($segundos);
			});
		}catch(\Throwable $e){
			$this->getLogger()->warning("Erro na consulta à NeverMiss: " . $e->getMessage());
			$this->fimDaVolta(60);
		}
	}

	private function fimDaVolta(int $segundos) : void{
		$this->emCiclo = false;
		$quem = $this->pediuTeste;
		$this->pediuTeste = null;
		if($quem !== null && (!$quem instanceof Player || $quem->isConnected())){
			$quem->sendMessage("[NeverMiss] " . $this->nucleo->situacao);
		}
		$adiantar = $this->deNovo;
		$this->deNovo = false;
		if($segundos > 0){
			$this->agendar($adiantar ? 5 : $segundos);
		}
	}

	/** Consulta já, respeitando 5 s desde a última (a API recusa antes disso). */
	private function adiantar() : void{
		if($this->emCiclo){
			$this->deNovo = true;
			return;
		}
		$passou = intdiv(Nucleo::agora() - $this->nucleo->ultimaConsulta, 1000);
		$this->agendar(max(1, 5 - $passou));
	}

	public function entrou(PlayerJoinEvent $e) : void{
		if(isset($this->nucleo) && $this->nucleo->configurado() && $this->nucleo->aoEntrar($e->getPlayer()->getName())){
			$this->adiantar();
		}
	}

	public function onCommand(CommandSender $sender, Command $command, string $label, array $args) : bool{
		$sub = strtolower((string) ($args[0] ?? "status"));
		switch($sub){
			case "status":
				foreach($this->nucleo->status() as $linha){
					$sender->sendMessage($linha);
				}
				return true;
			case "testar":
				if(!$this->nucleo->configurado()){
					$sender->sendMessage("[NeverMiss] " . $this->nucleo->situacao);
					return true;
				}
				$this->pediuTeste = $sender;
				$sender->sendMessage("[NeverMiss] Consultando a NeverMiss...");
				$this->adiantar();
				return true;
			case "recarregar":
				$this->configurar();
				$sender->sendMessage("[NeverMiss] Configuração recarregada. " . $this->nucleo->situacao);
				return true;
			case "chave":
				if(!$sender instanceof ConsoleCommandSender){
					$sender->sendMessage("[NeverMiss] A chave só pode ser trocada no console do servidor.");
					return true;
				}
				if(count($args) !== 2 || preg_match(Nucleo::CHAVE, (string) $args[1]) !== 1){
					$sender->sendMessage("[NeverMiss] Use: nevermiss chave <chave do painel, começa com nmmc_>");
					return true;
				}
				$this->getConfig()->set("chave", (string) $args[1]);
				$this->getConfig()->save();
				$this->configurar();
				return true;
			default:
				$sender->sendMessage("[NeverMiss] Use: /nevermiss status | testar | recarregar");
				return true;
		}
	}

	// --- Jogo ---

	public function rodar(string $comando) : bool{
		return $this->getServer()->dispatchCommand($this->console, $comando);
	}

	public function online(string $nome) : bool{
		return $this->getServer()->getPlayerExact($nome)?->isOnline() ?? false;
	}

	public function uuid(string $nome) : ?string{
		$p = $this->getServer()->getPlayerExact($nome);
		return $p !== null && $p->isOnline() ? $p->getUniqueId()->toString() : null;
	}

	public function onlineMode() : bool{
		return $this->getServer()->getOnlineMode();
	}

	public function versao() : string{
		$s = $this->getServer();
		return $s->getName() . " " . $s->getPocketMineVersion() . " (Bedrock " . $s->getVersion() . ")";
	}

	public function log(string $texto) : void{
		$this->getLogger()->info($texto);
	}

	public function aviso(string $texto) : void{
		$this->getLogger()->warning($texto);
	}

	public function post(string $url, array $cabecalhos, string $corpo, \Closure $pronto) : void{
		$this->getServer()->getAsyncPool()->submitTask(new Requisicao($url, $cabecalhos, $corpo, function(array $r) use ($pronto) : void{
			// Plugin desligado (servidor fechando): nada roda. O que saiu volta para a fila quando o lease vencer.
			if($this->isEnabled()){
				$pronto(...$r);
			}
		}));
	}
}
