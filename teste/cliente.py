#!/usr/bin/env python3
"""Um jogador falso (pirata) para o teste/e2e.sh: entra no servidor, responde o keep-alive e manda comandos.

Uso: python3 -I teste/cliente.py <porta> <nick> < comandos
  Cada linha da entrada é um comando do jogador ("kit vip-diamante"). Na saída: CONECTADO, CHAT <texto> e SAIU <motivo>.
  A /loja (contrato, 18): "@clicar <espaço>" clica no menu aberto. Na saída: JANELA <id> <título> e MAPA <id> <arquivo>, o
  mapa inteiro (128 x 128, um byte de cor por pixel) gravado em <arquivo> (na pasta NM_MAPAS, ou na atual).
  O servidor precisa de network-compression-threshold=-1 (sem compressão) e online-mode=false.
Os números dos pacotes são os de cada versão testada (1.7.10, 1.8.8, 1.12.2, 1.16.5, 1.20.4, 1.21.11 e 26.3); os do 1.21.11 e do 26.3
saíram do relatório do próprio servidor da Mojang (java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --reports).
Os do 1.20.1 (763) e do 1.21.1 (767), para o Fabric, saíram da tabela de pacotes de cada versão e ainda não rodaram.
Os do 1.18.2 (758) e do 1.19.2 (760), para o Forge, também (não rodaram). O Forge 1.13+ pergunta pelos canais de mod no login:
o cliente responde que não entende, como o vanilla.
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
    758: (0x21, 0x0F, 0x0F, 0x03, 0x1A, None),
    760: (0x20, 0x12, 0x62, 0x04, 0x19, None),
    763: (0x23, 0x12, 0x64, 0x04, 0x1A, None),
    765: (0x24, 0x15, 0x69, 0x04, 0x1B, None),
    767: (0x26, 0x18, 0x6C, 0x04, 0x1D, None),
    774: (0x2B, 0x1B, 0x77, 0x06, 0x20, 0x2B),
    777: (0x2D, 0x1C, 0x7C, 0x07, 0x20, 0x2C),
}
# A /loja: (menu abriu, dados do mapa, transação recusada (até o 1.16) que chegam; clique, desculpa da transação que saem).
# 1.21.11 e 26.3 do relatório da Mojang; os outros da tabela de pacotes de cada versão, conferida com os de cima
MENU = {
    47: (0x2D, 0x34, 0x32, 0x0E, 0x0F),
    340: (0x13, 0x24, 0x11, 0x07, 0x05),
    754: (0x2D, 0x25, 0x11, 0x09, 0x07),
    765: (0x31, 0x2A, None, 0x0D, None),
    774: (0x39, 0x31, None, 0x11, None),
    777: (0x3C, 0x34, None, 0x12, None),
}
# configuração (1.20.2+): (terminou, keep-alive, ping, pacotes conhecidos, código de conduta, saída) que chegam,
# e (terminou, keep-alive, pong, pacotes conhecidos, aceitar a conduta) que saem
CONFIG = {
    765: ((0x02, 0x03, 0x04, None, None, 0x01), (0x02, 0x03, 0x04, None, None)),
    767: ((0x03, 0x04, 0x05, 0x0E, None, 0x02), (0x03, 0x04, 0x05, 0x07, None)),
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


def clique(proto, janela, espaco):
    """O clique esquerdo (modo 0) no espaço do menu, sem item na mão. O servidor cancela (é a /loja) e manda o menu de novo."""
    if proto >= 768:  # 1.21.2+: o id da janela em varint; 1.21.5+: o item no cursor é um HashedStack opcional (vazio = 0)
        return varint(janela) + varint(0) + struct.pack(">hb", espaco, 0) + varint(0) + varint(0) + b"\0"
    if proto >= 755:  # 1.17 a 1.21.1: o state id e a lista dos espaços mudados
        return bytes([janela]) + varint(0) + struct.pack(">hb", espaco, 0) + varint(0) + varint(0) + b"\0"
    item = b"\0" if proto >= 393 else struct.pack(">h", -1)
    modo = bytes([0]) if proto == 47 else varint(0)
    return bytes([janela]) + struct.pack(">hbh", espaco, 0, 1) + modo + item


def mapa(proto, dados, mapas, pasta):
    """Os pixels do Map Data no mapa guardado (128 x 128). None se o pacote não traz pixels."""
    mid, i = varint_de(dados)
    i += 1  # escala
    if proto >= 340:
        i += 1  # 1.12-1.16: o "mostrar a posição"; 1.17+: o "travado"
    if 754 <= proto < 755:
        i += 1  # 1.16: "travado" também
    if proto >= 755:
        tem = dados[i]
        i += 1
        n = 0
        if tem:
            n, i = varint_de(dados, i)
        if n:
            return None  # o mapa da /loja não tem marcador
    else:
        n, i = varint_de(dados, i)
        if n:
            return None
    colunas = dados[i]
    i += 1
    if not colunas:
        return None
    linhas, x, z = dados[i], dados[i + 1], dados[i + 2]
    i += 3
    tam, i = varint_de(dados, i)
    buf = mapas.setdefault(mid, bytearray(128 * 128))
    for l in range(linhas):
        buf[(z + l) * 128 + x:(z + l) * 128 + x + colunas] = dados[i + l * colunas:i + (l + 1) * colunas]
    arquivo = "%s/mapa-%d.bin" % (pasta, mid)
    with open(arquivo, "wb") as f:
        f.write(bytes(buf))
    return mid, arquivo


def main():
    porta, nick = int(sys.argv[1]), sys.argv[2]
    import os
    pasta = os.environ.get("NM_MAPAS", ".")
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
    elif proto >= 761:  # 1.19.3 a 1.20.1: o UUID é opcional, e vai sem
        login += b"\0"
    elif proto == 760:  # 1.19.1 e 1.19.2: sem a chave de chat e sem o UUID
        login += b"\0\0"
    c.mandar(0x00, login)

    estado = "login"
    while True:
        pid, dados = c.pacote()
        if estado == "login":
            if pid == 0x00:
                print("SAIU " + legivel(dados), flush=True)
                return 1
            if pid == 0x04 and proto >= 393:  # pergunta de um canal de mod (Forge): "não entendo", como o vanilla
                c.mandar(0x02, varint(varint_de(dados)[0]) + b"\0")
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
    menu = MENU.get(proto)
    janela = [None]
    mapas = {}

    def comandos():
        for linha in sys.stdin:
            linha = linha.strip()
            if not linha:
                continue
            if linha.startswith("@clicar "):
                if menu is None or janela[0] is None:
                    print("SEMJANELA", flush=True)
                    continue
                c.mandar(menu[3], clique(proto, janela[0], int(linha.split()[1])))
                print("CLICOU " + linha.split()[1], flush=True)
                continue
            if proto >= 760:
                dados = texto(linha)
                if proto == 760:  # 1.19.1 e 1.19.2: o carimbo, sem assinaturas, sem prévia e sem mensagem vista
                    dados += struct.pack(">qq", int(time.time() * 1000), 0) + varint(0) + b"\0" + varint(0) + b"\0"
                elif proto <= 765:  # 1.20.1 a 1.20.4: o comando ainda leva o carimbo da assinatura, vazio
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
            elif menu and pid == menu[0]:
                janela[0], i = (dados[0], 1) if proto < 477 else varint_de(dados)
                print("JANELA %d %s" % (janela[0], legivel(dados[i:])), flush=True)
            elif menu and pid == menu[1]:
                m = mapa(proto, dados, mapas, pasta)
                if m:
                    print("MAPA %d %s" % m, flush=True)
            elif menu and menu[2] is not None and pid == menu[2] and not dados[3]:
                c.mandar(menu[4], dados[:3] + b"\1")
            elif pid == saida:
                print("SAIU " + legivel(dados), flush=True)
                return 1
    except (EOFError, OSError) as e:
        print("SAIU %s" % e, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
