import AppKit

enum Palette {
    static let green = NSColor(srgbRed: 0, green: 0.86, blue: 0.31, alpha: 1)
    static let amber = NSColor(srgbRed: 1, green: 0.69, blue: 0, alpha: 1)
    static let red = NSColor(srgbRed: 1, green: 0.16, blue: 0.16, alpha: 1)
    static let nightRed = NSColor(srgbRed: 0.67, green: 0, blue: 0, alpha: 1)
    static func mono(_ size: CGFloat) -> NSFont { .monospacedSystemFont(ofSize: size, weight: .bold) }
}

/// Scripted fake scene, a pure function of time (same script as the phone).
enum DemoScene {
    static func at(_ t: Double) -> [Box] {
        let s = t.truncatingRemainder(dividingBy: 14)
        var out: [Box] = []
        if (3...12).contains(s) { out.append(person(0.05 + (s - 3) / 9 * 0.7, 0.42, 0.30, t)) }
        if (7...12.5).contains(s) { out.append(person(0.88 - (s - 7) / 5.5 * 0.35, 0.38, 0.36, t + 0.777)) }
        return out
    }

    private static func person(_ x: Double, _ top: Double, _ h: Double, _ seed: Double) -> Box {
        let w = h * 0.32
        let j = sin(seed * 11) * 0.004
        return Box(x1: x + j, y1: top, x2: x + w + j, y2: top + h, score: 0.72 + 0.15 * sin(seed * 2.5))
    }
}

/// Rotates the drawing 180 degrees when the video is flipped.
private func applyFlip(_ ctx: CGContext, _ flip: Bool, _ w: CGFloat, _ h: CGFloat) {
    if flip { ctx.translateBy(x: w, y: h); ctx.rotate(by: .pi) }
}

final class DemoView: NSView {
    var flip = false
    private var timer: Timer?
    override var isFlipped: Bool { true }

    override func viewDidMoveToWindow() {
        timer?.invalidate()
        timer = window == nil ? nil : Timer.scheduledTimer(withTimeInterval: 1.0 / 30, repeats: true) { [weak self] _ in
            guard let self, !self.isHidden else { return }
            self.needsDisplay = true
        }
    }

    override func draw(_ rect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext else { return }
        let w = bounds.width, h = bounds.height
        ctx.saveGState()
        applyFlip(ctx, flip, w, h)
        let sky = CGGradient(colorsSpace: nil, colors: [NSColor(srgbRed: 0.27, green: 0.32, blue: 0.27, alpha: 1).cgColor,
                                                       NSColor(srgbRed: 0.16, green: 0.2, blue: 0.16, alpha: 1).cgColor] as CFArray,
                             locations: [0, 1])!
        ctx.saveGState()
        ctx.clip(to: CGRect(x: 0, y: 0, width: w, height: h * 0.7))
        ctx.drawLinearGradient(sky, start: .zero, end: CGPoint(x: 0, y: h * 0.7), options: [])
        ctx.restoreGState()
        ctx.setFillColor(NSColor(srgbRed: 0.11, green: 0.14, blue: 0.11, alpha: 1).cgColor)
        ctx.fill(CGRect(x: 0, y: h * 0.7, width: w, height: h * 0.3))
        ctx.setFillColor(NSColor(srgbRed: 0.07, green: 0.1, blue: 0.07, alpha: 1).cgColor)
        for i in 0..<7 {
            let x = w * (0.07 + CGFloat(i) * 0.145)
            ctx.fill(CGRect(x: x - w * 0.012, y: h * 0.3, width: w * 0.024, height: h * 0.42))
            ctx.fillEllipse(in: CGRect(x: x - w * 0.05, y: h * 0.28 - w * 0.05, width: w * 0.1, height: w * 0.1))
        }
        ctx.setFillColor(NSColor(srgbRed: 0.05, green: 0.055, blue: 0.05, alpha: 1).cgColor)
        for b in DemoScene.at(uptime()) {
            let bw = (b.x2 - b.x1) * w, cx = (b.x1 + b.x2) / 2 * w
            let r = bw * 0.28
            ctx.fillEllipse(in: CGRect(x: cx - r, y: b.y1 * h, width: r * 2, height: r * 2))
            ctx.addPath(CGPath(roundedRect: CGRect(x: cx - bw / 2, y: b.y1 * h + bw * 0.6, width: bw, height: b.y2 * h - b.y1 * h - bw * 0.6),
                               cornerWidth: bw * 0.2, cornerHeight: bw * 0.2, transform: nil))
            ctx.fillPath()
        }
        ctx.restoreGState()
        let label = NSAttributedString(string: "DEMO", attributes: [.font: Palette.mono(14), .foregroundColor: NSColor(white: 1, alpha: 0.45)])
        label.draw(at: NSPoint(x: (w - label.size().width) / 2, y: h * 0.06))
    }
}

/// Person boxes over the video, red edge when anyone is detected, dimmed when the link is not live.
final class OverlayView: NSView {
    var boxes: [Box] = [] { didSet { needsDisplay = true } }
    var stale = false { didSet { if stale != oldValue { needsDisplay = true } } }
    var night = false { didSet { needsDisplay = true } }
    var flip = false { didSet { needsDisplay = true } }
    override var isFlipped: Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func draw(_ rect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext else { return }
        let w = bounds.width, h = bounds.height
        if stale {
            ctx.setFillColor(NSColor(white: 0, alpha: 0.55).cgColor)
            ctx.fill(bounds)
        }
        guard !boxes.isEmpty else { return }
        let col = (night ? Palette.nightRed : NSColor.red).cgColor
        ctx.setStrokeColor(col)
        ctx.setLineWidth(4)
        ctx.stroke(bounds.insetBy(dx: 2, dy: 2))
        ctx.saveGState()
        applyFlip(ctx, flip, w, h)
        ctx.setLineWidth(2)
        for b in boxes { ctx.stroke(CGRect(x: b.x1 * w, y: b.y1 * h, width: (b.x2 - b.x1) * w, height: (b.y2 - b.y1) * h)) }
        ctx.restoreGState()
        for b in boxes {
            var x = b.x1 * w + 3, y = b.y1 * h - 16
            if flip { x = (1 - b.x2) * w + 3; y = (1 - b.y2) * h - 16 }
            NSAttributedString(string: "\(Int(b.score * 100))%", attributes: [.font: Palette.mono(11), .foregroundColor: night ? Palette.nightRed : NSColor.red])
                .draw(at: NSPoint(x: x, y: max(0, y)))
        }
    }
}

/// Big flat touch-style button with haptic confirmation.
final class MilButton: NSView {
    var action: () -> Void
    private let label = NSTextField(labelWithString: "")
    override var isFlipped: Bool { true }

    init(action: @escaping () -> Void) {
        self.action = action
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = 4
        label.font = Palette.mono(16)
        label.alignment = .center
        addSubview(label)
    }

    required init?(coder: NSCoder) { fatalError() }

    func style(_ text: String, on: Bool, night: Bool) {
        label.stringValue = text
        label.textColor = night ? Palette.nightRed : .white
        layer?.backgroundColor = (on ? (night ? NSColor(srgbRed: 0.24, green: 0, blue: 0, alpha: 1) : NSColor(srgbRed: 0, green: 0.35, blue: 0.12, alpha: 1))
                                     : (night ? NSColor(srgbRed: 0.05, green: 0, blue: 0, alpha: 1) : NSColor(white: 0.1, alpha: 0.85))).cgColor
    }

    override func layout() {
        super.layout()
        let h = label.intrinsicContentSize.height
        label.frame = NSRect(x: 0, y: (bounds.height - h) / 2, width: bounds.width, height: h)
    }

    override func mouseDown(with event: NSEvent) {
        NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now)
        layer?.opacity = 0.6
    }

    override func mouseUp(with event: NSEvent) {
        layer?.opacity = 1
        if bounds.contains(convert(event.locationInWindow, from: nil)) { action() }
    }
}

/// NSButton with a closure.
final class ClosureButton: NSButton {
    private var handler: () -> Void = {}
    convenience init(title: String, handler: @escaping () -> Void) {
        self.init(frame: .zero)
        self.title = title
        bezelStyle = .rounded
        self.handler = handler
        target = self
        action = #selector(fire)
    }
    @objc private func fire() { handler() }
}
