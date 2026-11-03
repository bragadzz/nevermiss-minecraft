// Monta build/NeverMiss-BDS-<versão>.mcpack (um zip) sempre igual: os mesmos arquivos dão o mesmo SHA-256 (quem compila confere
// com o publicado). Sem compressão e com a data fixa, então não depende da versão do Node nem do zlib. Uso: node build.mjs
// Formato: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT (cabeçalho local, diretório central e o fim).
import { createHash } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { crc32 } from "node:zlib";

const raiz = dirname(fileURLToPath(import.meta.url));
const pacote = join(raiz, "pacote");
const versao = JSON.parse(readFileSync(join(pacote, "manifest.json"), "utf8")).header.version.join(".");
// 01/01/2025 00:00 no formato do DOS.
const DATA = ((2025 - 1980) << 9) | (1 << 5) | 1;
const UTF8 = 0x0800;

const dentro = (pasta) =>
  readdirSync(join(pacote, pasta), { withFileTypes: true }).flatMap((f) => (f.isDirectory() ? dentro(`${pasta}/${f.name}`) : [`${pasta}/${f.name}`]));
const nomes = ["manifest.json", ...dentro("scripts")].sort();

const u16 = (n) => Buffer.from([n & 0xff, (n >>> 8) & 0xff]);
const u32 = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32LE(n >>> 0);
  return b;
};
const partes = [];
const central = [];
let posicao = 0;
for (const nome of nomes) {
  const dados = readFileSync(join(pacote, nome));
  const n = Buffer.from(nome, "utf8");
  const crc = crc32(dados);
  // versão 1.0, UTF-8, sem compressão, hora 0, a data fixa, CRC, tamanhos, nome, sem extra
  const local = Buffer.concat([u32(0x04034b50), u16(10), u16(UTF8), u16(0), u16(0), u16(DATA), u32(crc), u32(dados.length), u32(dados.length), u16(n.length), u16(0), n]);
  // feito no Unix (3), arquivo comum 0644
  central.push(Buffer.concat([u32(0x02014b50), u16(0x030a), u16(10), u16(UTF8), u16(0), u16(0), u16(DATA), u32(crc), u32(dados.length), u32(dados.length), u16(n.length), u16(0), u16(0), u16(0), u16(0), u32((0o100644 << 16) >>> 0), u32(posicao), n]));
  partes.push(local, dados);
  posicao += local.length + dados.length;
}
const diretorio = Buffer.concat(central);
const fim = Buffer.concat([u32(0x06054b50), u16(0), u16(0), u16(nomes.length), u16(nomes.length), u32(diretorio.length), u32(posicao), u16(0)]);
const zip = Buffer.concat([...partes, diretorio, fim]);

const saida = join(raiz, "build", `NeverMiss-BDS-${versao}.mcpack`);
mkdirSync(dirname(saida), { recursive: true });
writeFileSync(saida, zip);
console.log(`${createHash("sha256").update(zip).digest("hex")}  build/NeverMiss-BDS-${versao}.mcpack (${nomes.length} arquivos)`);
