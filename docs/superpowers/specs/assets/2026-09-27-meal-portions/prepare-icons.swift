import AppKit

let source = URL(fileURLWithPath: CommandLine.arguments[1])
let destination = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
let bitmap = NSBitmapImageRep(data: try Data(contentsOf: source))!
let sheet = bitmap.cgImage!
precondition(sheet.width == 1536 && sheet.height == 1024)
let names = ["meal_portion_small", "meal_portion_medium", "meal_portion_large",
             "meal_profile_fast", "meal_profile_mixed", "meal_profile_slow"]
for (index, name) in names.enumerated() {
    let rect = CGRect(x: (index % 3) * 512, y: (index / 3) * 512, width: 512, height: 512)
    let cell = sheet.cropping(to: rect)!
    let pixels = NSBitmapImageRep(cgImage: cell)
    var left = 512, top = 512, right = 0, bottom = 0
    for y in 0..<512 {
        for x in 0..<512 where pixels.colorAt(x: x, y: y)!.alphaComponent > 0.04 {
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = max(bottom, y)
        }
    }
    precondition(right > left && bottom > top)
    let crop = cell.cropping(to: CGRect(x: left, y: top, width: right-left+1, height: bottom-top+1))!
    let context = CGContext(data: nil, width: 192, height: 192, bitsPerComponent: 8,
        bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.interpolationQuality = .high
    let scale = 176.0 / Double(max(crop.width, crop.height))
    let width = Double(crop.width) * scale, height = Double(crop.height) * scale
    context.draw(crop, in: CGRect(x: (192-width)/2, y: (192-height)/2, width: width, height: height))
    let output = NSBitmapImageRep(cgImage: context.makeImage()!)
    try output.representation(using: .png, properties: [:])!.write(to: destination.appendingPathComponent(name + ".png"))
    print("\(name): \(output.pixelsWide)x\(output.pixelsHigh)")
}
