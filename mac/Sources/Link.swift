import AppKit
import CoreLocation
import CoreWLAN
import Darwin

// Wire protocol shared with pi/bodycam/server.py, see pi/README.md. All big-endian.
enum Proto {
    static let port: UInt16 = 5600
    static let video: UInt8 = 0x01
    static let detect: UInt8 = 0x02
    static let status: UInt8 = 0x03
    static let wifiAck: UInt8 = 0x04
    static let hello: UInt8 = 0x10
    static let keyReq: UInt8 = 0x11
    static let setWifi: UInt8 = 0x12
    static let videoHdr = 14   // + u32 capture_ms
    static let detectHdr = 7
    static let boxSize = 9

    static func u16(_ b: [UInt8], _ o: Int) -> Int { Int(b[o]) << 8 | Int(b[o + 1]) }
    static func u32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) << 24 | UInt32(b[o + 1]) << 16 | UInt32(b[o + 2]) << 8 | UInt32(b[o + 3])
    }
    static func be32(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24 & 0xFF), UInt8(v >> 16 & 0xFF), UInt8(v >> 8 & 0xFF), UInt8(v & 0xFF)] }
    static func helloPacket(_ seq: UInt32, _ wantAi: Bool, _ wantFan: UInt8, _ wantNight: UInt8) -> [UInt8] {
        [hello] + be32(seq) + [wantAi ? 1 : 0, wantFan, wantNight]
    }
    /// Signed a - b of two u32 ms clocks.
    static func diffU32(_ a: UInt32, _ b: UInt32) -> Int64 { Int64(Int32(bitPattern: a &- b)) }
    static func setWifiPacket(_ id: UInt32, _ ssid: String, _ pass: String) -> [UInt8] {
        let s = Array(ssid.utf8), p = Array(pass.utf8)
        return [setWifi] + be32(id) + [UInt8(s.count)] + s + [UInt8(p.count)] + p
    }
}

/// Person box, coordinates 0..1 of the video frame.
struct Box { let x1, y1, x2, y2, score: CGFloat }

struct PiStatus { let aiEnabled, hailoOk: Bool; let encFps, inferFps, tempC: Int; var fanIndicator: Bool? = nil; var nightExposure: Bool? = nil }

func uptime() -> Double { ProcessInfo.processInfo.systemUptime }

/// UDP link to the Pi: HELLO at 10 Hz, frame reassembly, keyframe resync, detections, status.
final class StreamClient {
    struct Callbacks {
        let onFrame: ([UInt8], Bool, UInt32) -> Void  // rx thread (data, key, captureMs)
        let onDetections: ([Box]) -> Void         // rx thread
        let onStatus: (PiStatus, Int) -> Void     // rx thread
    }

    private let host: String
    private let port: UInt16
    private let cb: Callbacks
    private let fd: Int32
    private let lock = NSLock()
    private var running = true
    private var _wantAi = true
    private var _wantFan: UInt8 = 255
    private var _wantNight: UInt8 = 255
    private var offsetSamples: [(rtt: Int, offset: UInt32)] = []
    private var _clockOffset: UInt32?
    private var needKey = true
    private var lastKeyReq = 0.0
    private var sentAt = [Double](repeating: 0, count: 64)
    private var wifiReq: (id: UInt32, packet: [UInt8], deadline: Double, done: (Bool, String) -> Void)?

    // reassembly state (rx thread only)
    private var curId: Int64 = -1
    private var parts: [[UInt8]?] = []
    private var got = 0
    private var curKey = false
    private var lastDoneId: Int64 = -1

    var wantAi: Bool {
        get { lock.withLock { _wantAi } }
        set { lock.withLock { _wantAi = newValue } }
    }
    private var isRunning: Bool { lock.withLock { running } }
    var wantFan: UInt8 {
        get { lock.withLock { _wantFan } }
        set { lock.withLock { _wantFan = newValue } }
    }
    var wantNight: UInt8 {
        get { lock.withLock { _wantNight } }
        set { lock.withLock { _wantNight = newValue } }
    }
    /// Pi clock minus this Mac's uptime clock, ms mod 2^32 (from the fastest status replies).
    var clockOffsetMs: UInt32? { lock.withLock { _clockOffset } }

    init(host: String, port: UInt16, cb: Callbacks) {
        self.host = host
        self.port = port
        self.cb = cb
        fd = socket(AF_INET, SOCK_DGRAM, 0)
        var rcv: Int32 = 1 << 21
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcv, socklen_t(MemoryLayout<Int32>.size))
        var tos: Int32 = 0xB8 // DSCP EF -> WMM video queue
        setsockopt(fd, IPPROTO_IP, IP_TOS, &tos, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 0, tv_usec: 250_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var any = sockaddr_in()
        any.sin_family = sa_family_t(AF_INET)
        any.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafePointer(to: &any) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        Thread { [self] in txLoop() }.start()
        Thread { [self] in rxLoop() }.start()
    }

    func close() {
        lock.withLock { running = false }
        Darwin.close(fd)
    }

    func requestKeyframe() { lock.withLock { needKey = true } }

    func setPiWifi(ssid: String, pass: String, done: @escaping (Bool, String) -> Void) {
        let id = UInt32(truncatingIfNeeded: Int(uptime() * 1000))
        lock.withLock { wifiReq = (id, Proto.setWifiPacket(id, ssid, pass), uptime() + 4, done) }
    }

    private func resolve() -> sockaddr_in? {
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_port = port.bigEndian
        if inet_pton(AF_INET, host, &addr.sin_addr) == 1 { return addr }
        var hints = addrinfo(ai_flags: 0, ai_family: AF_INET, ai_socktype: SOCK_DGRAM, ai_protocol: 0,
                             ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil)
        var res: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &res) == 0, let r = res, let sa = r.pointee.ai_addr else { return nil }
        defer { freeaddrinfo(res) }
        sa.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { addr.sin_addr = $0.pointee.sin_addr }
        return addr
    }

    private func send(_ bytes: [UInt8], _ addr: inout sockaddr_in) {
        _ = bytes.withUnsafeBytes { buf in
            withUnsafePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, buf.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    private func txLoop() {
        var addr: sockaddr_in?
        var seq: UInt32 = 0
        while isRunning {
            if addr == nil { addr = resolve() }
            if var a = addr {
                seq &+= 1
                let now = uptime()
                let (ai, fanWish, nightWish, req, key): (Bool, UInt8, UInt8, (id: UInt32, packet: [UInt8], deadline: Double, done: (Bool, String) -> Void)?, Bool) = lock.withLock {
                    sentAt[Int(seq & 63)] = now
                    var k = false
                    if needKey && now - lastKeyReq > 0.3 { lastKeyReq = now; k = true }
                    return (_wantAi, _wantFan, _wantNight, wifiReq, k)
                }
                send(Proto.helloPacket(seq, ai, fanWish, nightWish), &a)
                if let r = req {
                    if now > r.deadline {
                        lock.withLock { wifiReq = nil }
                        r.done(false, "Pi didn't answer")
                    } else {
                        send(r.packet, &a)
                    }
                }
                if key { send([Proto.keyReq], &a) }
            }
            usleep(100_000)
        }
    }

    private func rxLoop() {
        var buf = [UInt8](repeating: 0, count: 2048)
        while isRunning {
            let n = recv(fd, &buf, buf.count, 0)
            if n <= 0 { continue }
            let b = Array(buf[0..<n])
            switch b[0] {
            case Proto.video where n > Proto.videoHdr: onVideo(b)
            case Proto.detect where n >= Proto.detectHdr: onDetect(b)
            case Proto.status where n >= 10: onStatus(b)
            case Proto.wifiAck where n >= 7: onWifiAck(b)
            default: break
            }
        }
    }

    private func lost() { lock.withLock { needKey = true } }

    private func onVideo(_ b: [UInt8]) {
        let id = Int64(Proto.u32(b, 1))
        let idx = Proto.u16(b, 5)
        let count = Proto.u16(b, 7)
        let key = b[9] & 1 != 0
        guard count > 0, idx < count else { return }
        let captureMs = Proto.u32(b, 10)
        if id != curId {
            if id < curId && curId - id < 1000 && !key { return } // late packet; keyframes win (Pi restart)
            if curId >= 0 && got > 0 && got < parts.count { lost() }
            curId = id
            parts = Array(repeating: nil, count: count)
            got = 0
            curKey = key
        }
        guard got < parts.count, parts[idx] == nil else { return }
        parts[idx] = Array(b[Proto.videoHdr...])
        got += 1
        guard got == parts.count else { return }
        if lastDoneId >= 0 && id != lastDoneId + 1 && !curKey { lost() } // whole frame vanished
        lastDoneId = id
        let skip: Bool = lock.withLock {
            if curKey { needKey = false }
            return needKey
        }
        if skip { return }
        cb.onFrame(parts.flatMap { $0! }, curKey, captureMs)
    }

    private func onDetect(_ b: [UInt8]) {
        let count = Int(b[6])
        var boxes: [Box] = []
        var o = Proto.detectHdr
        for _ in 0..<count where o + Proto.boxSize <= b.count {
            boxes.append(Box(x1: CGFloat(Proto.u16(b, o)) / 65535, y1: CGFloat(Proto.u16(b, o + 2)) / 65535,
                             x2: CGFloat(Proto.u16(b, o + 4)) / 65535, y2: CGFloat(Proto.u16(b, o + 6)) / 65535,
                             score: CGFloat(b[o + 8]) / 255))
            o += Proto.boxSize
        }
        cb.onDetections(boxes)
    }

    private func onStatus(_ b: [UInt8]) {
        let seq = Proto.u32(b, 1)
        let recv = uptime()
        let sent = lock.withLock { sentAt[Int(seq & 63)] }
        let rtt = max(0, min(9999, Int((recv - sent) * 1000)))
        var st = PiStatus(aiEnabled: b[5] != 0, hailoOk: b[6] != 0, encFps: Int(b[7]), inferFps: Int(b[8]), tempC: Int(b[9]))
        if b.count >= 15 {
            let piNow = Proto.u32(b, 10)
            let offset = piNow &+ UInt32(rtt / 2) &- UInt32(truncatingIfNeeded: Int64(recv * 1000))
            lock.withLock {
                offsetSamples.append((rtt, offset))
                if offsetSamples.count > 50 { offsetSamples.removeFirst() }
                _clockOffset = offsetSamples.min { $0.rtt < $1.rtt }?.offset
            }
            st.fanIndicator = b[14] != 0
        }
        if b.count >= 16 { st.nightExposure = b[15] != 0 }
        cb.onStatus(st, rtt)
    }

    private func onWifiAck(_ b: [UInt8]) {
        let r: (id: UInt32, packet: [UInt8], deadline: Double, done: (Bool, String) -> Void)? = lock.withLock {
            guard let r = wifiReq, Proto.u32(b, 1) == r.id else { return nil }
            wifiReq = nil
            return r
        }
        guard let r else { return }
        let len = min(Int(b[6]), b.count - 7)
        r.done(b[5] == 1, String(decoding: b[7..<(7 + len)], as: UTF8.self))
    }
}

/// Joins the Pi's Wi-Fi with CoreWLAN when an SSID is set and the link is down.
/// SSID blank (the Mac default) = never touch Wi-Fi, use whatever network the Mac is on.
final class WifiJoiner: NSObject, CLLocationManagerDelegate {
    private var ssid = ""
    private var pass = ""
    private let location = CLLocationManager()
    private var lastTry = 0.0
    private var busy = false

    var wifiOff: Bool { CWWiFiClient.shared().interface()?.powerOn() == false }

    func configure(ssid: String, pass: String) {
        self.ssid = ssid
        self.pass = pass
        lastTry = 0
        if !ssid.isEmpty { // macOS needs location permission to scan Wi-Fi by name
            location.delegate = self
            location.requestWhenInUseAuthorization()
        }
    }

    func tick(linkUp: Bool) {
        guard !ssid.isEmpty, !linkUp, !busy else { return }
        let now = uptime()
        guard now - lastTry > 8 else { return }
        lastTry = now
        guard let iface = CWWiFiClient.shared().interface(), iface.powerOn() else { return }
        if iface.ssid() == ssid { return }
        busy = true
        let s = ssid, p = pass
        DispatchQueue.global().async {
            defer { DispatchQueue.main.async { self.busy = false } }
            do {
                if let net = try iface.scanForNetworks(withName: s).first {
                    try iface.associate(to: net, password: p.isEmpty ? nil : p)
                    NSLog("BodyCam: joined \(s)")
                }
            } catch {
                NSLog("BodyCam: join \(s) failed: \(error.localizedDescription)")
            }
        }
    }
}

/// Owns the link, stats, demo and alert logic. Main thread only.
final class LinkModel {
    enum Phase { case noWifi, noPi, stalled, live }
    struct State { let phase: Phase; let fps: Int; let rttMs: Int; let pi: PiStatus?; let persons: Int; let demo: Bool; let delayMs: Int? }

    static let defaultHost = "10.42.0.1"
    private let d = UserDefaults.standard
    let renderer = H264Renderer()
    private let joiner = WifiJoiner()

    var onState: ((State) -> Void)?
    var onBoxes: (([Box]) -> Void)?
    var onVibrate: ((Bool) -> Void)? // pulse on/off, drives the on-screen VIB indicator

    private var client: StreamClient?
    private(set) var demo = false
    private var demoTimer: Timer?
    private var demoTick = 0

    private var lastStatusAt = 0.0, lastFrameAt = 0.0, lastBoxesAt = 0.0
    private var piStatus: PiStatus?
    private var rtt = 0, framesThisSecond = 0, fps = 0
    private var fpsWindowStart = 0.0
    private(set) var persons = 0
    private var delays: [Int64] = []
    private var lastDelayAt = 0.0
    var piFanIndicator: Bool? { piStatus?.fanIndicator }
    var piNightExposure: Bool? { piStatus?.nightExposure }

    func setNightExposure(_ on: Bool) {
        client?.wantNight = on ? 1 : 0
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.client?.wantNight = 255 }
    }

    func setFanIndicator(_ on: Bool) {
        client?.wantFan = on ? 1 : 0 // sent in HELLOs for a few seconds, then back to "leave it"
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in self?.client?.wantFan = 255 }
    }

    private var recentHits = [Bool](repeating: false, count: 5)
    private var hitIdx = 0
    private var present = false
    private var lastSeenAt = 0.0, lastAlertAt = 0.0
    private var alertedCount = 0

    // settings
    var ssid: String { d.string(forKey: "ssid") ?? "" }
    var password: String { d.string(forKey: "pass") ?? "" }
    var host: String { d.string(forKey: "host") ?? LinkModel.defaultHost }
    var aiEnabled: Bool {
        get { d.object(forKey: "ai") as? Bool ?? true }
        set { d.set(newValue, forKey: "ai"); client?.wantAi = newValue }
    }
    var alertsEnabled: Bool {
        get { d.object(forKey: "alerts") as? Bool ?? true }
        set { d.set(newValue, forKey: "alerts") }
    }
    var flip: Bool {
        get { d.bool(forKey: "flip") }
        set { d.set(newValue, forKey: "flip") }
    }
    var videoFlip: Bool {
        get { d.bool(forKey: "video_flip") }
        set { d.set(newValue, forKey: "video_flip") }
    }
    var videoOffset: CGFloat {
        get { CGFloat(d.double(forKey: "video_offset")) }
        set { d.set(Double(newValue), forKey: "video_offset") }
    }

    func start() {
        renderer.onNeedKey = { [weak self] in self?.client?.requestKeyframe() }
        reconnect()
        Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in self?.tick() }
    }

    func saveSettings(ssid: String, password: String, host: String) {
        d.set(ssid.trimmingCharacters(in: .whitespaces), forKey: "ssid")
        d.set(password, forKey: "pass")
        let h = host.trimmingCharacters(in: .whitespaces)
        d.set(h.isEmpty ? LinkModel.defaultHost : h, forKey: "host")
        reconnect()
    }

    func reconnect() {
        client?.close()
        client = nil
        if demo { return }
        joiner.configure(ssid: ssid, pass: password)
        let c = StreamClient(host: host, port: Proto.port, cb: .init(
            onFrame: { [weak self] data, key, cap in DispatchQueue.main.async { self?.frame(data, key, cap) } },
            onDetections: { [weak self] boxes in DispatchQueue.main.async { self?.detections(boxes) } },
            onStatus: { [weak self] st, rtt in DispatchQueue.main.async { self?.status(st, rtt) } }
        ))
        c.wantAi = aiEnabled
        client = c
    }

    func setDemo(_ on: Bool) {
        guard on != demo else { return }
        demo = on
        if on {
            client?.close()
            client = nil
            renderer.flush()
            demoTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30, repeats: true) { [weak self] _ in self?.demoStep() }
        } else {
            demoTimer?.invalidate()
            demoTimer = nil
            persons = 0
            onBoxes?([])
            reconnect()
        }
    }

    func changePiWifi(ssid newSsid: String, pass newPass: String, done: @escaping (Bool, String) -> Void) {
        guard let c = client, !demo else { done(false, "Not connected to the Pi"); return }
        c.setPiWifi(ssid: newSsid, pass: newPass) { ok, msg in
            DispatchQueue.main.async {
                if ok && !self.ssid.isEmpty { self.saveSettings(ssid: newSsid, password: newPass, host: self.host) }
                done(ok, msg)
            }
        }
    }

    private func demoStep() {
        frame([], false, 0)
        if demoTick % 2 == 0 && aiEnabled { detections(DemoScene.at(uptime())) }
        if demoTick % 3 == 0 { status(PiStatus(aiEnabled: aiEnabled, hailoOk: true, encFps: 30, inferFps: aiEnabled ? 30 : 0, tempC: 48), 3) }
        demoTick += 1
    }

    private func frame(_ data: [UInt8], _ key: Bool, _ captureMs: UInt32) {
        lastFrameAt = uptime()
        framesThisSecond += 1
        guard !data.isEmpty else { return }
        renderer.submit(data, key: key)
        // Delay readout: capture -> hand-off to the display layer, plus ~1 frame for decode/display
        // (the Mac has no per-frame "on screen" callback like Android).
        if let off = client?.clockOffsetMs {
            let piNow = UInt32(truncatingIfNeeded: Int64(uptime() * 1000)) &+ off
            let d = Proto.diffU32(piNow, captureMs) + 17
            if (0...10_000).contains(d) {
                delays.append(d)
                if delays.count > 30 { delays.removeFirst() }
                lastDelayAt = uptime()
            }
        }
    }

    private func delayMedian() -> Int? {
        guard !demo, uptime() - lastDelayAt < 1.5, !delays.isEmpty else { return nil }
        return Int(delays.sorted()[delays.count / 2])
    }

    private func detections(_ boxes: [Box]) {
        lastBoxesAt = uptime()
        persons = boxes.count
        onBoxes?(boxes)
        handleAlert(boxes.count)
    }

    private func status(_ st: PiStatus, _ rtt: Int) {
        lastStatusAt = uptime()
        piStatus = st
        self.rtt = rtt
    }

    /// Same rule as the phone: 3 of the last 5 inferences must agree, re-alert on more people
    /// or after 2 s with nobody.
    private func handleAlert(_ count: Int) {
        let now = uptime()
        recentHits[hitIdx] = count > 0
        hitIdx = (hitIdx + 1) % recentHits.count
        let hits = recentHits.filter { $0 }.count
        if count > 0 { lastSeenAt = now }
        if !present && hits >= 3 {
            present = true
            alertedCount = count
            alert(now, double: false)
        } else if present && count > alertedCount && now - lastAlertAt > 3 {
            alertedCount = count
            alert(now, double: true)
        } else if present && now - lastSeenAt > 2 {
            present = false
            alertedCount = 0
        }
    }

    /// Mac stand-in for the phone's vibration: trackpad haptic pulses + on-screen VIB indicator.
    private func alert(_ now: Double, double: Bool) {
        guard alertsEnabled && aiEnabled else { return }
        lastAlertAt = now
        let pulses: [(Double, Double)] = double ? [(0, 0.09), (0.16, 0.09), (0.32, 0.09)] : [(0, 0.12), (0.2, 0.12)]
        for (start, len) in pulses {
            DispatchQueue.main.asyncAfter(deadline: .now() + start) { [weak self] in
                NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
                self?.onVibrate?(true)
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + start + len) { [weak self] in self?.onVibrate?(false) }
        }
    }

    private func tick() {
        let now = uptime()
        if now - fpsWindowStart >= 1 {
            fps = Int(Double(framesThisSecond) / max(0.001, now - fpsWindowStart))
            framesThisSecond = 0
            fpsWindowStart = now
        }
        let phase: Phase
        if demo { phase = .live }
        else if joiner.wifiOff { phase = .noWifi }
        else if now - lastStatusAt > 1.5 { phase = .noPi }
        else if now - lastFrameAt > 1.0 { phase = .stalled }
        else { phase = .live }
        if now - lastBoxesAt > 0.5 && persons != 0 {
            persons = 0
            onBoxes?([])
        }
        if !demo { joiner.tick(linkUp: phase == .live || phase == .stalled) }
        onState?(State(phase: phase, fps: fps, rttMs: rtt,
                       pi: (phase == .live || phase == .stalled) ? piStatus : nil, persons: persons, demo: demo, delayMs: delayMedian()))
    }
}
