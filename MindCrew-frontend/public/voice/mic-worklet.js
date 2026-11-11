/**
 * Microphone capture worklet for voice call
 *
 *  - Input: 1-channel Float32 PCM at AudioContext sample rate (browser default 44100/48000)
 *  - Output to main thread:
 *      { type: 'pcm16', buffer: ArrayBuffer (16-bit little-endian PCM @ 16kHz) }
 *      { type: 'level', rms: number }   // 用于客户端 VAD
 *
 *  Resampling: nearest-neighbor with linear-interp 落点。够用，开销低。
 */

class MicWorklet extends AudioWorkletProcessor {
  constructor() {
    super()
    this.targetSampleRate = 16000
    this.resampleRatio = sampleRate / this.targetSampleRate  // sampleRate 是 AudioWorkletGlobalScope 全局
    this.flushSamples = Math.floor(this.targetSampleRate * 0.05)  // 50ms 一帧
    this.buffer = []
  }

  process(inputs) {
    const input = inputs[0]
    if (!input || input.length === 0) return true
    const ch0 = input[0]
    if (!ch0) return true

    // Resample → push 到 buffer
    for (let i = 0; i < ch0.length; i += this.resampleRatio) {
      const lo = Math.floor(i)
      const hi = Math.min(ch0.length - 1, lo + 1)
      const frac = i - lo
      const sample = ch0[lo] * (1 - frac) + ch0[hi] * frac
      this.buffer.push(sample)
    }

    // RMS for VAD
    let sumSq = 0
    for (let i = 0; i < ch0.length; i++) sumSq += ch0[i] * ch0[i]
    const rms = Math.sqrt(sumSq / ch0.length)

    if (this.buffer.length >= this.flushSamples) {
      // 转 int16 LE
      const f32 = this.buffer.splice(0, this.flushSamples)
      const out = new Int16Array(f32.length)
      for (let i = 0; i < f32.length; i++) {
        let s = f32[i]
        if (s > 1) s = 1
        else if (s < -1) s = -1
        out[i] = s < 0 ? s * 0x8000 : s * 0x7fff
      }
      this.port.postMessage({ type: 'pcm16', buffer: out.buffer, rms }, [out.buffer])
    } else {
      this.port.postMessage({ type: 'level', rms })
    }
    return true
  }
}

registerProcessor('mic-worklet', MicWorklet)
