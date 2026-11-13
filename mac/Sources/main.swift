import AppKit

/// The phone screen: video left, status top-left, CONTACT top-center, button column right.
final class ScreenView: NSView {
    let model: LinkModel
    private let video = NSView()
    private let demoView = DemoView()
    private let overlay = OverlayView()
    private let status = NSTextField(labelWithString: "")
    private let contact = NSTextField(labelWithString: "")
    private let vib = NSTextField(labelWithString: "VIB")
    private let toast = NSTextField(labelWithString: "")
    private let dim = DimView()
    private var buttons: [MilButton] = []
    private var det: MilButton!, vibBtn: MilButton!, nightBtn: MilButton!, menuBtn: MilButton!
    private var aspect: CGFloat = 16.0 / 9.0
    private(set) var night = false
    var videoOffset: CGFloat = 0 { didSet { needsLayout = true } }
    var onMenu: (() -> Void)?

    override var isFlipped: Bool { true }

    init(model: LinkModel) {
        self.model = model
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor

        video.wantsLayer = true
        video.layer?.addSublayer(model.renderer.layer)
        addSubview(video)
        demoView.isHidden = true
        video.addSubview(demoView)
        video.addSubview(overlay)

        status.font = Palette.mono(13)
        let shadow = NSShadow(); shadow.shadowBlurRadius = 3; shadow.shadowColor = .black
        status.shadow = shadow
        addSubview(status)

        contact.font = Palette.mono(22)
        contact.drawsBackground = true
        contact.alignment = .center
        contact.isHidden = true
        addSubview(contact)

        vib.font = Palette.mono(14)
        vib.drawsBackground = true
        vib.backgroundColor = Palette.red
        vib.textColor = .white
        vib.alignment = .center
        vib.isHidden = true
        addSubview(vib)

        toast.font = Palette.mono(13)
        toast.drawsBackground = true
        toast.backgroundColor = NSColor(white: 0.12, alpha: 0.95)
        toast.textColor = .white
        toast.alignment = .center
        toast.isHidden = true
        addSubview(toast)

        det = MilButton { [weak self] in guard let self else { return }; model.aiEnabled.toggle(); refresh() }
        vibBtn = MilButton { [weak self] in guard let self else { return }; model.alertsEnabled.toggle(); refresh() }
        nightBtn = MilButton { [weak self] in self?.night.toggle(); self?.refresh() }
        menuBtn = MilButton { [weak self] in self?.onMenu?() }
        buttons = [det, vibBtn, nightBtn, menuBtn]
        buttons.forEach(addSubview)

        dim.isHidden = true
        addSubview(dim) // night dimming on top of everything, click-through

        model.renderer.onAspect = { [weak self] a in self?.aspect = a; self?.needsLayout = true }
        model.onBoxes = { [weak self] in self?.overlay.boxes = $0 }
        model.onState = { [weak self] in self?.show($0) }
        model.onVibrate = { [weak self] on in self?.vib.isHidden = !on }
        videoOffset = model.videoOffset
        refresh()
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        let W = bounds.width, H = bounds.height
        var vh = H, vw = H * aspect
        if vw > W { vw = W; vh = W / aspect }
        video.frame = NSRect(x: videoOffset * max(0, W - vw), y: (H - vh) / 2, width: vw, height: vh)
        CATransaction.begin(); CATransaction.setDisableActions(true)
        model.renderer.layer.frame = video.bounds
        model.renderer.layer.setAffineTransform(model.videoFlip ? CGAffineTransform(rotationAngle: .pi) : .identity)
        model.renderer.layer.frame = video.bounds
        CATransaction.commit()
        demoView.frame = video.bounds
        overlay.frame = video.bounds

        let colW = max(110, W * 0.12), pad: CGFloat = 6
        let bh = (H - pad * 5) / 4
        for (i, b) in buttons.enumerated() {
            b.frame = NSRect(x: W - colW - pad, y: pad + CGFloat(i) * (bh + pad), width: colW, height: bh)
        }
        status.sizeToFit()
        status.frame.origin = NSPoint(x: 10, y: 8)
        contact.sizeToFit()
        contact.frame = NSRect(x: (W - contact.frame.width - 24) / 2, y: 8, width: contact.frame.width + 24, height: contact.frame.height + 6)
        vib.sizeToFit()
        vib.frame = NSRect(x: 10, y: H - vib.frame.height - 16, width: vib.frame.width + 16, height: vib.frame.height + 6)
        toast.sizeToFit()
        toast.frame = NSRect(x: (W - toast.frame.width - 24) / 2, y: H - toast.frame.height - 24, width: toast.frame.width + 24, height: toast.frame.height + 8)
        dim.frame = bounds
    }

    func refresh() {
        det.style(model.aiEnabled ? "DET ON" : "DET OFF", on: model.aiEnabled, night: night)
        vibBtn.style(model.alertsEnabled ? "VIB ON" : "VIB OFF", on: model.alertsEnabled, night: night)
        nightBtn.style(night ? "NIGHT ON" : "NIGHT", on: night, night: night)
        menuBtn.style("MENU", on: false, night: night)
        demoView.isHidden = !model.demo
        demoView.flip = model.videoFlip
        overlay.flip = model.videoFlip
        overlay.night = night
        dim.isHidden = !night
        needsLayout = true
    }

    func showToast(_ text: String) {
        toast.stringValue = text
        toast.isHidden = false
        needsLayout = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) { [weak self] in
            if self?.toast.stringValue == text { self?.toast.isHidden = true }
        }
    }

    private func show(_ s: LinkModel.State) {
        let net = model.ssid.isEmpty ? "WI-FI" : model.ssid
        let text: String, color: NSColor
        switch s.phase {
        case .live:
            var det = ""
            if let pi = s.pi { det = !pi.hailoOk ? "  DET FAIL" : pi.aiEnabled ? "  DET \(pi.inferFps)" : "  DET OFF" }
            let dly = s.delayMs.map { "  DLY \($0)MS" } ?? ""
            text = "\(s.demo ? "DEMO" : "LINK UP")  \(s.fps)FPS\(dly)  \(s.rttMs)MS\(det)  \(s.pi?.tempC ?? 0)C"
            color = Palette.green
        case .stalled: text = "LINK DEGRADED  RESYNC"; color = Palette.amber
        case .noPi: text = "NO PI  SRCH \(net)"; color = Palette.amber
        case .noWifi: text = "NO LINK  SRCH \(net)"; color = Palette.red
        }
        if status.stringValue != text { status.stringValue = text; needsLayout = true }
        status.textColor = night ? Palette.nightRed : color
        overlay.stale = s.phase != .live
        if s.persons > 0 {
            contact.stringValue = "CONTACT \(s.persons)"
            contact.textColor = night ? .black : .white
            contact.backgroundColor = night ? Palette.nightRed : NSColor(srgbRed: 0.67, green: 0, blue: 0, alpha: 1)
            if contact.isHidden { contact.isHidden = false; needsLayout = true }
        } else if !contact.isHidden {
            contact.isHidden = true
        }
    }
}

/// Night mode: the Mac can't drop the panel brightness like the phone, so darken the window instead.
final class DimView: NSView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
    override func draw(_ rect: NSRect) {
        NSColor(white: 0, alpha: 0.6).setFill()
        rect.fill()
    }
}

/// Window content: holds the screen and rotates it 180 degrees for FLIP 180.
final class Holder: NSView {
    let screen: ScreenView
    init(screen: ScreenView) {
        self.screen = screen
        super.init(frame: .zero)
        addSubview(screen)
    }
    required init?(coder: NSCoder) { fatalError() }
    var flip = false { didSet { needsLayout = true } }
    override func layout() {
        super.layout()
        screen.frameCenterRotation = 0
        screen.frame = bounds
        screen.frameCenterRotation = flip ? 180 : 0
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    let model = LinkModel()
    var window: NSWindow!
    var screen: ScreenView!
    var holder: Holder!
    var menu: MenuController!
    var activity: NSObjectProtocol?

    func applicationDidFinishLaunching(_ note: Notification) {
        // Phone shape, landscape, 20:9. Resizable but locked to that ratio.
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1000, height: 450),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "BodyCam"
        window.contentAspectRatio = NSSize(width: 20, height: 9)
        window.contentMinSize = NSSize(width: 600, height: 270)
        window.appearance = NSAppearance(named: .darkAqua)
        window.backgroundColor = .black
        screen = ScreenView(model: model)
        holder = Holder(screen: screen)
        holder.flip = model.flip
        window.contentView = holder
        window.center()
        window.makeKeyAndOrderFront(nil)

        menu = MenuController(app: self)
        screen.onMenu = { [weak self] in self?.menu.showMenu() }

        // Screen never sleeps while the app is open.
        activity = ProcessInfo.processInfo.beginActivity(options: [.idleDisplaySleepDisabled, .idleSystemSleepDisabled, .userInitiated],
                                                         reason: "BodyCam live view")
        model.start()
        if CommandLine.arguments.contains("--demo") { model.setDemo(true); screen.refresh() }
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ app: NSApplication) -> Bool { true }
}

let app = NSApplication.shared
app.setActivationPolicy(.regular)
let delegate = AppDelegate()
app.delegate = delegate
app.run()
