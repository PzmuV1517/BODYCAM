import AppKit

/// MENU and PI WI-FI sheets. Rows are LABEL [control] (?), explanations only behind the ?.
final class MenuController {
    private unowned let app: AppDelegate
    private var sheet: NSWindow?
    private let popover = NSPopover()

    init(app: AppDelegate) {
        self.app = app
        popover.behavior = .transient
    }

    private var model: LinkModel { app.model }
    private var window: NSWindow { app.window }

    private func help(_ text: String, from view: NSView) {
        let label = NSTextField(wrappingLabelWithString: text)
        label.font = .systemFont(ofSize: 13)
        label.preferredMaxLayoutWidth = 260
        let vc = NSViewController()
        let box = NSView(frame: NSRect(x: 0, y: 0, width: 284, height: label.intrinsicContentSize.height + 24))
        label.frame = box.bounds.insetBy(dx: 12, dy: 12)
        box.addSubview(label)
        vc.view = box
        popover.contentViewController = vc
        popover.show(relativeTo: view.bounds, of: view, preferredEdge: .maxX)
    }

    private func row(_ label: String, _ control: NSView, _ helpText: String) -> [NSView] {
        let l = NSTextField(labelWithString: label)
        l.font = Palette.mono(13)
        let help = HelpButton(text: helpText) { [weak self] text, v in self?.help(text, from: v) }
        return [l, control, help]
    }

    private func field(_ value: String) -> NSTextField {
        let f = NSTextField(string: value)
        f.font = Palette.mono(13)
        f.widthAnchor.constraint(equalToConstant: 300).isActive = true
        return f
    }

    private func present(title: String, rows: [[NSView]], buttons: [NSButton]) -> NSWindow {
        let grid = NSGridView(views: rows)
        grid.rowSpacing = 10
        grid.columnSpacing = 12
        grid.column(at: 0).xPlacement = .leading
        grid.rowAlignment = .firstBaseline
        let titleLabel = NSTextField(labelWithString: title)
        titleLabel.font = Palette.mono(16)
        let buttonRow = NSStackView(views: buttons)
        buttonRow.spacing = 10
        let stack = NSStackView(views: [titleLabel, grid, buttonRow])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 16
        stack.edgeInsets = NSEdgeInsets(top: 18, left: 20, bottom: 18, right: 20)
        let s = NSWindow(contentRect: .zero, styleMask: [.titled], backing: .buffered, defer: false)
        s.appearance = NSAppearance(named: .darkAqua)
        s.contentView = stack
        s.setContentSize(stack.fittingSize)
        sheet = s
        window.beginSheet(s)
        return s
    }

    private func close() {
        if let s = sheet { window.endSheet(s) }
        sheet = nil
    }

    func showMenu() {
        guard sheet == nil else { return }
        let ssid = field(model.ssid)
        let pass = field(model.password)
        let host = field(model.host)
        let startOffset = app.screen.videoOffset
        let offset = NSSlider(value: Double(startOffset), minValue: 0, maxValue: 1, target: nil, action: nil)
        offset.isContinuous = true
        let offsetHandler = SliderHandler { [weak self] v in self?.app.screen.videoOffset = CGFloat(v) } // live preview
        offset.target = offsetHandler
        offset.action = #selector(SliderHandler.changed(_:))
        let flip = NSButton(checkboxWithTitle: "", target: nil, action: nil)
        flip.state = model.flip ? .on : .off
        let videoFlip = NSButton(checkboxWithTitle: "", target: nil, action: nil)
        videoFlip.state = model.videoFlip ? .on : .off
        let demo = NSButton(checkboxWithTitle: "", target: nil, action: nil)
        demo.state = model.demo ? .on : .off
        let piFan = model.piFanIndicator
        let fanStart = NSButton(checkboxWithTitle: "", target: nil, action: nil)
        fanStart.state = piFan == true ? .on : .off
        fanStart.isEnabled = piFan != nil // stored on the Pi: needs a link
        let piNight = model.piNightExposure
        let nightExp = NSButton(checkboxWithTitle: "", target: nil, action: nil)
        nightExp.state = piNight == true ? .on : .off
        nightExp.isEnabled = piNight != nil
        let piWifi = ClosureButton(title: "SET") { [weak self] in
            self?.app.screen.videoOffset = startOffset
            self?.close()
            self?.showPiWifi()
        }
        let shutdown = ClosureButton(title: "SHUT DOWN") { [weak self] in
            self?.app.screen.videoOffset = startOffset
            self?.close()
            self?.confirmShutdown()
        }
        shutdown.contentTintColor = Palette.red

        let cancel = ClosureButton(title: "CANCEL") { [weak self] in
            self?.app.screen.videoOffset = startOffset
            self?.close()
        }
        cancel.keyEquivalent = "\u{1b}"
        let save = ClosureButton(title: "SAVE") { [weak self] in
            guard let self else { return }
            model.videoOffset = app.screen.videoOffset
            model.flip = flip.state == .on
            model.videoFlip = videoFlip.state == .on
            app.holder.flip = model.flip
            model.saveSettings(ssid: ssid.stringValue, password: pass.stringValue, host: host.stringValue)
            model.setDemo(demo.state == .on)
            if let piFan, (fanStart.state == .on) != piFan { model.setFanIndicator(fanStart.state == .on) }
            if let piNight, (nightExp.state == .on) != piNight { model.setNightExposure(nightExp.state == .on) }
            app.screen.refresh()
            close()
        }
        save.keyEquivalent = "\r"

        let s = present(title: "MENU", rows: [
            row("SSID", ssid, "Network name of the Pi. Leave blank to use the network the Mac is already on (bench test). When set, the Mac joins it automatically."),
            row("PASSWORD", pass, "WPA2 password of the Pi network."),
            row("PI ADDRESS", host, "IP address of the Pi. Default \(LinkModel.defaultHost) on its own network."),
            row("VIDEO POS", offset, "Moves the video left or right. Left end puts the video on the left edge."),
            row("FLIP 180", flip, "Rotates the whole display 180 degrees to match the phone mount."),
            row("VIDEO FLIP", videoFlip, "Rotates only the video 180 degrees. Use when the camera is mounted upside down."),
            row("DEMO", demo, "Simulated feed and contacts for training. Stops the network search."),
            row("NIGHT EXP", nightExp, "Low light: the camera may lengthen exposure up to 1/15 s when the scene is dark. Brighter image, frame rate drops toward 15 FPS and delay rises about 30 MS only while dark. Stored on the Pi. Requires an active link to change."),
            row("FAN START", fanStart, "Pi spins its fan in two short bursts when its Wi-Fi is up after power-on, so you can hear it is ready. Stored on the Pi. Requires an active link to change."),
            row("PI WI-FI", piWifi, "Changes the SSID and password broadcast by the Pi. Requires an active link."),
            row("LINK", shutdown, "Stops the link and alerts and closes the app."),
        ], buttons: [cancel, save])
        objc_setAssociatedObject(s, "offsetHandler", offsetHandler, .OBJC_ASSOCIATION_RETAIN)
    }

    private func confirmShutdown() {
        let a = NSAlert()
        a.messageText = "SHUT DOWN LINK"
        a.informativeText = "Stops video, detection alerts and reconnection."
        a.addButton(withTitle: "SHUT DOWN")
        a.addButton(withTitle: "CANCEL")
        a.beginSheetModal(for: window) { r in if r == .alertFirstButtonReturn { NSApp.terminate(nil) } }
    }

    private func showPiWifi() {
        let ssid = field(model.ssid)
        let pass = field("")
        let error = NSTextField(labelWithString: "")
        error.textColor = Palette.red
        error.font = Palette.mono(12)
        var apply: ClosureButton!
        let cancel = ClosureButton(title: "CANCEL") { [weak self] in self?.close() }
        cancel.keyEquivalent = "\u{1b}"
        apply = ClosureButton(title: "APPLY") { [weak self] in
            guard let self else { return }
            let newSsid = ssid.stringValue.trimmingCharacters(in: .whitespaces)
            let newPass = pass.stringValue
            if newSsid.isEmpty || newSsid.utf8.count > 32 { error.stringValue = "SSID: 1-32 CHARACTERS"; return }
            if !(8...63).contains(newPass.count) || !newPass.unicodeScalars.allSatisfy({ (32...126).contains($0.value) }) {
                error.stringValue = "PASSWORD: 8-63 CHARACTERS"; return
            }
            error.stringValue = "SENDING"
            apply.isEnabled = false
            model.changePiWifi(ssid: newSsid, pass: newPass) { ok, msg in
                if ok {
                    self.close()
                    self.app.screen.showToast("PI WI-FI SET: \(newSsid). RECONNECTING")
                } else {
                    error.stringValue = "FAILED: \(msg.uppercased())"
                    apply.isEnabled = true
                }
            }
        }
        apply.keyEquivalent = "\r"
        _ = present(title: "PI WI-FI", rows: [
            row("NEW SSID", ssid, "New network name, 1 to 32 characters. The Pi restarts its Wi-Fi and the link follows automatically."),
            row("NEW PASSWORD", pass, "8 to 63 characters."),
            [NSTextField(labelWithString: ""), error, NSView()],
        ], buttons: [cancel, apply])
    }
}

/// The (?) button: shows its help text in a popover.
final class HelpButton: NSButton {
    private let text: String
    private let show: (String, NSView) -> Void
    init(text: String, show: @escaping (String, NSView) -> Void) {
        self.text = text
        self.show = show
        super.init(frame: .zero)
        title = "?"
        bezelStyle = .circular
        target = self
        action = #selector(tap)
    }
    required init?(coder: NSCoder) { fatalError() }
    @objc private func tap() { show(text, self) }
}

final class SliderHandler: NSObject {
    let onChange: (Double) -> Void
    init(_ onChange: @escaping (Double) -> Void) { self.onChange = onChange }
    @objc func changed(_ s: NSSlider) { onChange(s.doubleValue) }
}
