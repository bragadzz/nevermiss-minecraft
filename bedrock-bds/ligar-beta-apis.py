#!/usr/bin/env python3
# Liga o experimento "Beta APIs" num mundo do BDS (o @minecraft/server-net e o @minecraft/server-admin só existem em beta).
# É o mesmo que marcar "Beta APIs" em Experimentos ao editar o mundo no Minecraft. Pare o servidor antes.
# Uso: python3 ligar-beta-apis.py "worlds/<level-name>/level.dat"   (guarda uma cópia em level.dat.antes-da-nevermiss)
# Formato: 4 bytes da versão, 4 do tamanho e o NBT little-endian (https://wiki.bedrock.dev/nbt/nbt-in-depth).
import shutil
import struct
import sys

LIGAR = ("gametest", "experiments_ever_used", "saved_with_toggled_experiments")
FIXO = {1: "<b", 2: "<h", 3: "<i", 4: "<q", 5: "<f", 6: "<d"}


def ler(b, i, tipo):
    if tipo in FIXO:
        n = struct.calcsize(FIXO[tipo])
        return struct.unpack_from(FIXO[tipo], b, i)[0], i + n
    if tipo in (7, 11, 12):
        (n,) = struct.unpack_from("<i", b, i)
        f = {7: "b", 11: "i", 12: "q"}[tipo]
        return list(struct.unpack_from(f"<{n}{f}", b, i + 4)), i + 4 + n * struct.calcsize(f)
    if tipo == 8:
        (n,) = struct.unpack_from("<H", b, i)
        return b[i + 2 : i + 2 + n].decode("utf-8"), i + 2 + n
    if tipo == 9:
        t, n = struct.unpack_from("<bi", b, i)
        i += 5
        itens = []
        for _ in range(n):
            v, i = ler(b, i, t)
            itens.append(v)
        return (t, itens), i
    if tipo == 10:
        tags = {}
        while b[i] != 0:
            t = b[i]
            nome, i = ler(b, i + 1, 8)
            v, i = ler(b, i, t)
            tags[nome] = (t, v)
        return tags, i + 1
    raise ValueError(f"tag NBT desconhecida: {tipo}")


def escrever(tipo, v):
    if tipo in FIXO:
        return struct.pack(FIXO[tipo], v)
    if tipo in (7, 11, 12):
        f = {7: "b", 11: "i", 12: "q"}[tipo]
        return struct.pack(f"<i{len(v)}{f}", len(v), *v)
    if tipo == 8:
        t = v.encode("utf-8")
        return struct.pack("<H", len(t)) + t
    if tipo == 9:
        t, itens = v
        return struct.pack("<bi", t, len(itens)) + b"".join(escrever(t, x) for x in itens)
    if tipo == 10:
        return b"".join(bytes([t]) + escrever(8, nome) + escrever(t, x) for nome, (t, x) in v.items()) + b"\0"
    raise ValueError(f"tag NBT desconhecida: {tipo}")


def main(caminho):
    with open(caminho, "rb") as f:
        dados = f.read()
    versao, tamanho = struct.unpack_from("<ii", dados, 0)
    if dados[8] != 10 or tamanho != len(dados) - 8:
        sys.exit(f"{caminho} não parece um level.dat do Bedrock")
    nome, i = ler(dados, 9, 8)
    raiz, _ = ler(dados, i, 10)
    experimentos = raiz.setdefault("experiments", (10, {}))[1]
    if all(experimentos.get(k) == (1, 1) for k in LIGAR):
        print("Beta APIs já estava ligado.")
        return
    for k in LIGAR:
        experimentos[k] = (1, 1)
    nbt = bytes([10]) + escrever(8, nome) + escrever(10, raiz)
    shutil.copyfile(caminho, caminho + ".antes-da-nevermiss")
    with open(caminho, "wb") as f:
        f.write(struct.pack("<ii", versao, len(nbt)) + nbt)
    print("Beta APIs ligado. A cópia de antes ficou em level.dat.antes-da-nevermiss.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__ or 'Uso: python3 ligar-beta-apis.py "worlds/<level-name>/level.dat"')
    main(sys.argv[1])
