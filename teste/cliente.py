#!/usr/bin/env python3
"""Um jogador falso (pirata) para o teste/e2e.sh: entra no servidor, responde o keep-alive e manda comandos.

Uso: python3 -I teste/cliente.py <porta> <nick> < comandos
  Cada linha da entrada é um comando do jogador ("kit vip-diamante"). Na saída: CONECTADO, CHAT <texto> e SAIU <motivo>.
  O servidor precisa de network-compression-threshold=-1 (sem compressão) e online-mode=false.
Os números dos pacotes são os de cada versão testada (1.7.10, 1.8.8, 1.12.2, 1.16.5, 1.20.4, 1.21.11 e 26.3); os do 1.21.11 e do 26.3
saíram do relatório do próprio servidor da Mojang (java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports).
"""
import hashlib
import json
import socket
import struct
import sys
import threading
import time
import uuid

# protocolo: (keep-alive que chega, o que volta, chat que chega, comando que sai, saída do jogo, carregou (1.21.4+)).
# O keep-alive volta com os mesmos bytes (int no 1.7, varint no 1.8, long depois). O teleporte não é confirmado: o
# servidor só manda de novo, não derruba.
PLAY = {
    5: (0x00, 0x00, 0x02, 0x01, 0x40, None),
    47: (0x00, 0x00, 0x02, 0x01, 0x40, None),
    340: (0x1F, 0x0B, 0x0F, 0x02, 0x1A, None),
    754: (0x1F, 0x10, 0x0E, 0x03, 0x19, None),
    765: (0x24, 0x15, 0x69, 0x04, 0x1B, None),
    774: (0x2B, 0x1B, 0x77, 0x06, 0x20, 0x2B),
    777: (0x2D, 0x1C, 0x7C, 0x07, 0x20, 0x2C),
}
# configuração (1.20.2+): (terminou, keep-alive, ping, pacotes conhecidos, código de conduta, saída) que chegam,
# e (terminou, keep-alive, pong, pacotes conhecidos, aceitar a conduta) que saem
CONFIG = {
    765: ((0x02, 0x03, 0x04, None, None, 0x01), (0x02, 0x03, 0x04, None, None)),
    774: ((0x03, 0x04, 0x05, 0x0E, 0x13, 0x02), (0x03, 0x04, 0x05, 0x07, 0x09)),
    777: ((0x03, 0x04, 0x05, 0x0F, 0x14, 0x02), (0x03, 0x04, 0x05, 0x07, 0x09)),
}


def varint(n):
    n &= 0xFFFFFFFF
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out += bytes([b | 0x80])
        else:
            return out + bytes([b])


def texto(s):
    b = s.encode("utf-8")
    return varint(len(b)) + b


def ler_varint(ler):
    n = 0
    for i in range(5):
        b = ler(1)[0]
        n |= (b & 0x7F) << (7 * i)
        if not b & 0x80:
            break
    return n - (1 << 32) if n & (1 << 31) else n


def varint_de(buf, i=0):
    n = 0
    for k in range(5):
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << (7 * k)
        if not b & 0x80:
            break
    return n, i


class Conexao:
    def __init__(self, porta):
        self.s = socket.create_connection(("127.0.0.1", porta), timeout=60)
        self.trava = threading.Lock()

    def ler(self, n):
        buf = b""
        while len(buf) < n:
            parte = self.s.recv(n - len(buf))
            if not parte:
                raise EOFError("o servidor fechou a conexão")
            buf += parte
        return buf

    def pacote(self):
        tamanho = ler_varint(self.ler)
        corpo = self.ler(tamanho)
        pid, i = varint_de(corpo)
        return pid, corpo[i:]

    def mandar(self, pid, dados=b""):
        corpo = varint(pid) + dados
        with self.trava:
            self.s.sendall(varint(len(corpo)) + corpo)


def protocolo(porta):
    c = Conexao(porta)
    c.mandar(0x00, varint(-1) + texto("127.0.0.1") + struct.pack(">H", porta) + varint(1))
    c.mandar(0x00)
    _, dados = c.pacote()
    n, i = varint_de(dados)
    c.s.close()
    return json.loads(dados[i:i + n].decode("utf-8"))["version"]["protocol"]


def legivel(dados):
    """O texto de um pacote de chat (JSON no 1.8/1.12, NBT no 1.20.3+): só as letras, sem o resto."""
    t = dados.decode("utf-8", "replace")
    return "".join(ch if ch.isprintable() else " " for ch in t).strip()


def main():
    porta, nick = int(sys.argv[1]), sys.argv[2]
    proto = protocolo(porta)
    if proto not in PLAY:
        print("SAIU protocolo %d não está na tabela do cliente" % proto, flush=True)
        return 1
    ka_in, ka_out, chat_in, cmd_out, saida, carregou = PLAY[proto]
    c = Conexao(porta)
    c.mandar(0x00, varint(proto) + texto("127.0.0.1") + struct.pack(">H", porta) + varint(2))
    login = texto(nick)
    if proto >= 764:
        login += uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + nick).encode()).digest(), version=3).bytes
    c.mandar(0x00, login)

    estado = "login"
    while True:
        pid, dados = c.pacote()
        if estado == "login":
            if pid == 0x00:
                print("SAIU " + legivel(dados), flush=True)
                return 1
            if pid == 0x03:
                print("SAIU o servidor ligou a compressão: use network-compression-threshold=-1", flush=True)
                return 1
            if pid == 0x02:
                if proto in CONFIG:
                    c.mandar(0x03)
                    estado = "config"
                else:
                    estado = "jogo"
                    break
        elif estado == "config":
            entra, sai = CONFIG[proto]
            if pid == entra[0]:
                c.mandar(sai[0])
                break
            elif pid in (entra[1], entra[2]):
                c.mandar(sai[1] if pid == entra[1] else sai[2], dados)
            elif pid == entra[3]:
                c.mandar(sai[3], varint(0))
            elif pid == entra[4]:
                c.mandar(sai[4])
            elif pid == entra[5]:
                print("SAIU " + legivel(dados), flush=True)
                return 1

    print("CONECTADO %d" % proto, flush=True)

    def comandos():
        for linha in sys.stdin:
            linha = linha.strip()
            if not linha:
                continue
            if proto >= 765:
                dados = texto(linha)
                if proto == 765:  # 1.20.4: o comando ainda leva o carimbo da assinatura, vazio
                    dados += struct.pack(">qq", int(time.time() * 1000), 0) + varint(0) + varint(0) + b"\0\0\0"
                c.mandar(cmd_out, dados)
            else:
                c.mandar(cmd_out, texto("/" + linha))
            print("MANDOU " + linha, flush=True)

    threading.Thread(target=comandos, daemon=True).start()
    if carregou is not None:
        c.mandar(carregou)
    try:
        while True:
            pid, dados = c.pacote()
            if pid == ka_in:
                c.mandar(ka_out, dados)
            elif pid == chat_in:
                print("CHAT " + legivel(dados), flush=True)
            elif pid == saida:
                print("SAIU " + legivel(dados), flush=True)
                return 1
    except (EOFError, OSError) as e:
        print("SAIU %s" % e, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
