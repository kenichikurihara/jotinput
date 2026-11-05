# ReazonSpeech Model & On-Device Optimization

This document details the selection, architecture, and optimization of the speech recognition model used in JotInput.

---

## 🎯 Why ReazonSpeech?

**ReazonSpeech** is an open-source Japanese speech recognition project developed by Reazon Human Interaction Lab. It is trained on over **35,000 hours** of broadcast speech data, making it one of the most accurate and robust acoustic models for natural spoken Japanese.

### Evolutionary Path of ReazonSpeech Models:

| Version | Architecture | Framework | Target / Characteristics |
| :--- | :--- | :--- | :--- |
| **v1.0** | Conformer-CTC | ESPnet | High accuracy, primarily server-side |
| **v2.0** | Conformer-CTC | NeMo | Enhanced vocabulary, server deployment |
| **v2.1 (K2)** | **Zipformer Transducer** | **Next-gen Kaldi / K2** | **Edge / On-Device optimized, low latency** |

JotInput specifically adopts the **Zipformer Transducer** model (`reazonspeech-k2-v2`).

---

## 🏗️ Zipformer Transducer Architecture

A neural Transducer consists of three components:

1. **Acoustic Encoder**: Processes acoustic feature frames (80-dimensional log Mel filterbanks extracted from the input waveform) and generates high-level acoustic representations.
2. **Predictor (Decoder)**: Evaluates preceding text tokens to model Japanese linguistic probability.
3. **Joiner**: Merges acoustic representations from the encoder with language representations from the decoder to predict the probability distribution over the vocabulary at each time step.

```
Waveform (16kHz)
      |
      v
[Acoustic Encoder] (Zipformer, int8)  <-- Heavy compute (~90%)
      |
      +------> [ Joiner ] ------> Softmax / Greedy Search ------> Japanese Text
                  ^
                  |
[ Predictor / Decoder ] (Embedding + Stateless RNN)
      ^
      |
Tokens (history)
```

### Why Zipformer over Whisper for On-Device Mobile?
1. **Linear Time Complexity**: Zipformer downsamples audio features along the time axis through multi-tier temporal pooling (U-Net-like structure), reducing quadratic attention costs.
2. **No Hallucinations**: Unlike autoregressive encoder-decoder models (e.g., Whisper), transducers align strictly with acoustic timestamps, eliminating looping or hallucination issues on long silences.
3. **Instant Startup**: Does not require maintaining large key-value (KV) attention caches in RAM.

---

## ⚡ Quantization & Size Footprint

Deploying deep neural networks to edge mobile devices requires balancing **recognition accuracy** with **RAM usage** and **storage**.

In standard 32-bit floating point (`fp32`), the ReazonSpeech Zipformer encoder alone exceeds **560 MB**, which is too large for fast loading and low memory usage.

### Quantization Strategy:

We employ a **hybrid precision strategy**:
- **Encoder**: Quantized to **int8** weights (`encoder-epoch-99-avg-1.int8.onnx`). The encoder contains ~90% of model parameters; quantizing to 8-bit integers yields a **~74% reduction** in file size and accelerated matrix multiplications via CPU SIMD/NEON instructions.
- **Decoder & Joiner**: Retained as float32 (`decoder-epoch-99-avg-1.onnx` and `joiner-epoch-99-avg-1.onnx`). Because they are small (~11 MB and ~10 MB), quantizing them provides minimal storage gain while maintaining maximum decoding fidelity.

### Model File Breakdown:

| File Name | Format | Size | Function |
| :--- | :--- | :--- | :--- |
| `encoder-epoch-99-avg-1.int8.onnx` | ONNX (int8) | **147.5 MB** | Multi-rate acoustic feature extraction |
| `decoder-epoch-99-avg-1.onnx` | ONNX (fp32) | **11.2 MB** | Text token context modeling |
| `joiner-epoch-99-avg-1.onnx` | ONNX (fp32) | **10.2 MB** | Joint probability distribution layer |
| `tokens.txt` | UTF-8 Text | **45 KB** | Vocabulary (Kanji, Hiragana, Katakana, Punctuation) |
| **Total** | | **~169 MB** | Fits comfortably within mobile RAM |

---

## ⚙️ Runtime Configuration

Inside [`AsrManager.kt`](file:///home/kenichikurihara/repos/myasr/app/src/main/java/com/example/myasr/AsrManager.kt), the inference runtime is tuned for mobile constraints:

```kotlin
val modelConfig = OfflineModelConfig(
    transducer = OfflineTransducerModelConfig(
        encoder = ...,
        decoder = ...,
        joiner = ...
    ),
    tokens = ...,
    modelType = "transducer",
    modelingUnit = "cjkchar",
    numThreads = getOptimalThreadCount(),
    provider = "cpu"
)
```

- **`numThreads = getOptimalThreadCount()`**: `(availableProcessors / 2).coerceIn(2, 4)`, i.e. 2–4 threads depending on the device. Capping the count prevents mobile CPUs from excessive power draw or thermal throttling while achieving real-time factors (RTF) well below $0.5$ on modern processors.
- **`provider = "cpu"`**: Standard CPU execution provider ensures universal compatibility across all Android architectures (the shipped ABIs `arm64-v8a` and `x86_64`) without proprietary NPU driver dependencies.
