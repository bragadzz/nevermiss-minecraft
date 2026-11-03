// O QR Code do mapa da /loja, lido como a câmera do banco leria: os 128 x 128 pixels que o jogador falso recebeu
// (teste/cliente.py, um byte de cor por pixel) viram uma imagem e o leitor de QR do macOS (CoreImage) tenta ler.
// Uso: swift teste/lerqr.swift <mapa.bin> [imagem.png]   Imprime o texto do QR, ou sai com 1 se não leu.
import AppKit
import CoreImage
import Foundation

let args = CommandLine.arguments
guard args.count >= 2, let dados = FileManager.default.contents(atPath: args[1]), dados.count == 128 * 128 else {
    FileHandle.standardError.write("Uso: swift lerqr.swift <mapa.bin> [imagem.png]\n".data(using: .utf8)!)
    exit(2)
}
// Cada pixel do mapa vira 4 x 4, com borda branca: o leitor quer o QR grande e com margem
let escala = 4, borda = 32, lado = 128 * escala + 2 * borda
var px = [UInt8](repeating: 255, count: lado * lado)
let bytes = [UInt8](dados)
for y in 0..<128 {
    for x in 0..<128 where bytes[y * 128 + x] != 34 {
        for dy in 0..<escala { for dx in 0..<escala { px[(borda + y * escala + dy) * lado + borda + x * escala + dx] = 0 } }
    }
}
let cs = CGColorSpaceCreateDeviceGray()
let ctx = CGContext(data: &px, width: lado, height: lado, bitsPerComponent: 8, bytesPerRow: lado, space: cs, bitmapInfo: CGImageAlphaInfo.none.rawValue)!
let imagem = ctx.makeImage()!
if args.count >= 3 {
    let rep = NSBitmapImageRep(cgImage: imagem)
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))
}
let leitor = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
let achados = leitor.features(in: CIImage(cgImage: imagem)).compactMap { ($0 as? CIQRCodeFeature)?.messageString }
guard let texto = achados.first else {
    FileHandle.standardError.write("Nenhum QR Code no mapa\n".data(using: .utf8)!)
    exit(1)
}
print(texto)
