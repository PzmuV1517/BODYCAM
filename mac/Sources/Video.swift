import AVFoundation
import CoreMedia

/// Annex-B H.264 access units -> AVSampleBufferDisplayLayer (hardware decode, shown immediately).
/// Starts and resyncs on keyframes only; asks the Pi for one whenever the decoder fails.
final class H264Renderer {
    let layer = AVSampleBufferDisplayLayer()
    var onNeedKey: (() -> Void)?
    var onAspect: ((CGFloat) -> Void)?

    private var sps: [UInt8]?
    private var pps: [UInt8]?
    private var format: CMVideoFormatDescription?
    private var waitKey = true

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor(gray: 0, alpha: 1)
    }

    func flush() {
        layer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        waitKey = true
    }

    func submit(_ au: [UInt8], key: Bool) {
        let r = layer.sampleBufferRenderer
        if r.status == .failed || r.requiresFlushToResumeDecoding {
            r.flush()
            waitKey = true
            onNeedKey?()
        }
        if waitKey && !key { return }

        var avcc: [UInt8] = []
        avcc.reserveCapacity(au.count + 16)
        for nal in Self.nalUnits(au) {
            switch nal.first! & 0x1F {
            case 7: if sps != Array(nal) { sps = Array(nal); format = nil }
            case 8: if pps != Array(nal) { pps = Array(nal); format = nil }
            case 9: continue // access unit delimiter
            default:
                let n = UInt32(nal.count)
                avcc += [UInt8(n >> 24 & 0xFF), UInt8(n >> 16 & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)]
                avcc += nal
            }
        }
        if format == nil { makeFormat() }
        guard let format, !avcc.isEmpty else { return }

        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
                                                 blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                                                 dataLength: avcc.count, flags: 0, blockBufferOut: &block) == noErr,
              let block else { return }
        avcc.withUnsafeBytes { _ = CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: avcc.count) }
        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
                                        sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr,
              let sample else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true) {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        r.enqueue(sample)
        waitKey = false
    }

    private func makeFormat() {
        guard let sps, let pps else { return }
        var fmt: CMVideoFormatDescription?
        sps.withUnsafeBufferPointer { s in
            pps.withUnsafeBufferPointer { p in
                let ptrs = [s.baseAddress!, p.baseAddress!]
                let sizes = [sps.count, pps.count]
                _ = CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: kCFAllocatorDefault, parameterSetCount: 2,
                                                                        parameterSetPointers: ptrs, parameterSetSizes: sizes,
                                                                        nalUnitHeaderLength: 4, formatDescriptionOut: &fmt)
            }
        }
        format = fmt
        if let fmt {
            let dim = CMVideoFormatDescriptionGetDimensions(fmt)
            if dim.height > 0 { onAspect?(CGFloat(dim.width) / CGFloat(dim.height)) }
        }
    }

    /// Split an Annex-B buffer on 00 00 01 / 00 00 00 01 start codes.
    static func nalUnits(_ b: [UInt8]) -> [ArraySlice<UInt8>] {
        var starts: [Int] = []
        var i = 0
        while i + 2 < b.count {
            if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 { starts.append(i + 3); i += 3 } else { i += 1 }
        }
        var out: [ArraySlice<UInt8>] = []
        for (k, s) in starts.enumerated() {
            var e = k + 1 < starts.count ? starts[k + 1] - 3 : b.count
            if k + 1 < starts.count && e > s && b[e - 1] == 0 { e -= 1 } // 4-byte start code
            if e > s { out.append(b[s..<e]) }
        }
        return out
    }
}
