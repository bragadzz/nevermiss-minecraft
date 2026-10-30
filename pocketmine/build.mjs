// Monta build/NeverMiss-PocketMine-<versão>.phar sem precisar de PHP, sempre igual: os mesmos arquivos dão o mesmo SHA-256
// (quem compila confere com o publicado). Uso: node build.mjs
// Formato: https://www.php.net/manual/en/phar.fileformat.phar.php (stub, manifesto, arquivos e a assinatura SHA-256).
import { createHash } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { crc32 } from "node:zlib";

const raiz = dirname(fileURLToPath(import.meta.url));
const versao = /^version:\s*(\S+)\s*$/m.exec(readFileSync(join(raiz, "plugin.yml"), "utf8"))[1];
// A data de todos os arquivos dentro do phar: fixa, para o hash não mudar a cada build.
const DATA = 1735689600;
const SHA256 = 0x0003;
const TEM_ASSINATURA = 0x00010000;

const dentro = (pasta) =>
  readdirSync(join(raiz, pasta), { withFileTypes: true }).flatMap((f) => (f.isDirectory() ? dentro(`${pasta}/${f.name}`) : [`${pasta}/${f.name}`]));
const nomes = ["plugin.yml", ...dentro("resources"), ...dentro("src")].sort();

const u32 = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(n >>> 0);
  return b;
};
const entradas = [];
const conteudos = [];
for (const nome of nomes) {
  const dados = readFileSync(join(raiz, nome));
  const n = Buffer.from(nome, "utf8");
  // tamanho do nome, nome, tamanho, data, tamanho comprimido (sem compressão), CRC32, permissão 0644, metadados (nenhum)
  entradas.push(u32(n.length), n, u32(dados.length), u32(DATA), u32(dados.length), u32(crc32(dados)), u32(0o644), u32(0));
  conteudos.push(dados);
}
// quantos arquivos, versão 1.1.1 do formato, com assinatura, sem alias, sem metadados
const manifesto = Buffer.concat([u32(nomes.length), Buffer.from([0x11, 0x10]), u32(TEM_ASSINATURA), u32(0), u32(0), ...entradas]);
const corpo = Buffer.concat([Buffer.from("<?php __HALT_COMPILER(); ?>\r\n"), u32(manifesto.length), manifesto, ...conteudos]);
const phar = Buffer.concat([corpo, createHash("sha256").update(corpo).digest(), u32(SHA256), Buffer.from("GBMB")]);

const saida = join(raiz, "build", `NeverMiss-PocketMine-${versao}.phar`);
mkdirSync(dirname(saida), { recursive: true });
writeFileSync(saida, phar);
console.log(`${createHash("sha256").update(phar).digest("hex")}  build/NeverMiss-PocketMine-${versao}.phar (${nomes.length} arquivos)`);
