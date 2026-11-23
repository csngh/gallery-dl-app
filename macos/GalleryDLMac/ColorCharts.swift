import Charts
import CoreGraphics
import SwiftUI

/// What one picture looks like in numbers: how its brightness is spread, and a few figures that say where its blacks, whites
/// and colors are. Measured on a small copy of the picture.
struct ColorStats: Equatable {
    /// The share of the picture at each of 48 brightness steps, from black to white.
    var histogram: [Double]
    var mean: Double
    /// The brightness that 1% of the picture is darker than, and that 1% is brighter than.
    var black: Double, white: Double
    /// Average saturation of the colored parts, 0 (gray) to 1.
    var saturation: Double
    /// Average of each color channel, 0 to 1.
    var red: Double, green: Double, blue: Double

    static let bins = 48

    static func measure(_ image: CGImage) -> ColorStats? {
        let width = 192, height = 108
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        var histogram = [Double](repeating: 0, count: bins), levels = [Double](repeating: 0, count: 256)
        var sum = 0.0, red = 0.0, green = 0.0, blue = 0.0, saturation = 0.0, colored = 0.0
        let count = Double(width * height)
        for i in 0..<(width * height) {
            let r = Double(pixels[i * 4]), g = Double(pixels[i * 4 + 1]), b = Double(pixels[i * 4 + 2])
            let luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            histogram[min(bins - 1, Int(luma / 256 * Double(bins)))] += 1
            levels[min(255, Int(luma.rounded()))] += 1
            sum += luma; red += r; green += g; blue += b
            let top = max(r, g, b)
            if top > 38 { saturation += (top - min(r, g, b)) / top; colored += 1 }
        }
        func percentile(_ share: Double) -> Double {
            var seen = 0.0
            for (level, n) in levels.enumerated() { seen += n; if seen / count >= share { return Double(level) / 255 } }
            return 1
        }
        return ColorStats(
            histogram: histogram.map { $0 / count }, mean: sum / count / 255, black: percentile(0.01), white: percentile(0.99),
            saturation: colored >= count * 0.2 ? saturation / colored : 0, red: red / count / 255, green: green / count / 255, blue: blue / count / 255)
    }
}

/// The small panel beside the comparison: before and after in charts and numbers, and what the color fix did at this moment.
struct ColorChartsPanel: View {
    let before: ColorStats?
    let after: ColorStats?

    private let beforeColor = Color.white.opacity(0.55)
    private var afterColor: Color { Brand.primary }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            Text("Color: before and after").textStyle(.titleSmall)
            if let before, let after {
                legend
                brightnessChart(before, after)
                channelChart(before, after)
                numbers(before, after)
            } else {
                Text("Measuring…").textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            }
            Spacer(minLength: 0)
        }
        .padding(Spacing.sm)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: Radius.sm, style: .continuous).fill(Brand.surfaceContainerHighest))
    }

    private var legend: some View {
        HStack(spacing: Spacing.md) {
            HStack(spacing: 4) { Circle().fill(beforeColor).frame(width: 8, height: 8); Text("Before").textStyle(.labelSmall, color: Brand.onSurfaceVariant) }
            HStack(spacing: 4) { Circle().fill(afterColor).frame(width: 8, height: 8); Text("After").textStyle(.labelSmall, color: Brand.onSurfaceVariant) }
        }
    }

    private func brightnessChart(_ before: ColorStats, _ after: ColorStats) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Brightness (dark to light)").textStyle(.labelSmall, color: Brand.onSurfaceVariant)
            Chart {
                ForEach(0..<ColorStats.bins, id: \.self) { bin in
                    AreaMark(x: .value("Level", bin), y: .value("Share", after.histogram[bin]), series: .value("Picture", "After"))
                        .foregroundStyle(afterColor.opacity(0.35))
                    LineMark(x: .value("Level", bin), y: .value("Share", before.histogram[bin]), series: .value("Picture", "Before"))
                        .foregroundStyle(beforeColor)
                        .lineStyle(StrokeStyle(lineWidth: 1.4))
                    LineMark(x: .value("Level", bin), y: .value("Share", after.histogram[bin]), series: .value("Picture", "AfterLine"))
                        .foregroundStyle(afterColor)
                        .lineStyle(StrokeStyle(lineWidth: 1.4))
                }
            }
            .chartXAxis(.hidden)
            .chartYAxis(.hidden)
            .frame(height: 78)
        }
    }

    private func channelChart(_ before: ColorStats, _ after: ColorStats) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Average red, green and blue").textStyle(.labelSmall, color: Brand.onSurfaceVariant)
            Chart {
                ForEach([("R", before.red, after.red), ("G", before.green, after.green), ("B", before.blue, after.blue)], id: \.0) { name, was, now in
                    BarMark(x: .value("Channel", name), y: .value("Level", was))
                        .position(by: .value("Picture", "Before")).foregroundStyle(beforeColor)
                    BarMark(x: .value("Channel", name), y: .value("Level", now))
                        .position(by: .value("Picture", "After")).foregroundStyle(afterColor)
                }
            }
            .chartYScale(domain: 0...1)
            .chartYAxis(.hidden)
            .frame(height: 70)
        }
    }

    private func numbers(_ before: ColorStats, _ after: ColorStats) -> some View {
        VStack(spacing: 3) {
            row("Average brightness", before.mean, after.mean)
            row("Blacks (darkest 1%)", before.black, after.black)
            row("Whites (brightest 1%)", before.white, after.white)
            row("Saturation", before.saturation, after.saturation)
        }
    }

    private func row(_ name: String, _ was: Double, _ now: Double) -> some View {
        HStack(spacing: 4) {
            Text(name).textStyle(.bodySmall, color: Brand.onSurfaceVariant)
            Spacer(minLength: 0)
            Text(String(format: "%.2f → %.2f", was, now)).textStyle(.labelMedium)
            Text(String(format: "%+.2f", now - was)).textStyle(.labelMedium, color: abs(now - was) < 0.005 ? Brand.onSurfaceVariant : Brand.primary)
                .frame(width: 42, alignment: .trailing)
        }
    }
}
