#!/usr/bin/env python3
"""build_user_dict_dataset.py: Builds the English Voice User-Dictionary evaluation dataset.

Includes:
- 38 active/control custom user dictionary entries (CamelCase, acronyms, Japanese names
  with Kana readings, symbol tech terms, multi-word phrases, disabled & common-word controls)
- 68 positive utterances testing target words in realistic spoken contexts
- 42 adversarial negative utterances with phonetic lookalikes and common words
"""

import json
import os

DICTIONARY_ENTRIES = [
    # Mixed-case / CamelCase / compounds (including bilingual Kana readings)
    {"id": "ud_01", "word": "JotInput", "reading": "じょっといんぷっと", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_02", "word": "ReazonSpeech", "reading": "reazonspeech", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_03", "word": "PyTorch", "reading": "pytorch", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_04", "word": "GraphQL", "reading": "graphql", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_05", "word": "gRPC", "reading": "grpc", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_06", "word": "Tailscale", "reading": "tailscale", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_07", "word": "FastConformer", "reading": "fastconformer", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_08", "word": "Zipformer", "reading": "zipformer", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_09", "word": "Datadog", "reading": "datadog", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_10", "word": "Cloudflare", "reading": "cloudflare", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_11", "word": "LangChain", "reading": "langchain", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_12", "word": "SymSpell", "reading": "symspell", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_13", "word": "Supabase", "reading": "supabase", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_14", "word": "KanaKanji", "reading": "kanakanji", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_15", "word": "SudachiDict", "reading": "sudachidict", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_16", "word": "vLLM", "reading": "vllm", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_17", "word": "OAuth", "reading": "oauth", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_18", "word": "NixOS", "reading": "nixos", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_19", "word": "Antigravity", "reading": "antigravity", "priority": "NORMAL", "is_enabled": True},
    # Proper nouns / Japanese names in English
    {"id": "ud_20", "word": "Kurihara", "reading": "くりはら", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_21", "word": "Kenichi", "reading": "けんいち", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_22", "word": "Anthropic", "reading": "anthropic", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_23", "word": "Qwen", "reading": "qwen", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_24", "word": "Mozc", "reading": "mozc", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_25", "word": "Konsist", "reading": "konsist", "priority": "NORMAL", "is_enabled": True},
    # Acronyms & symbol-bearing tech terms
    {"id": "ud_26", "word": "CI/CD", "reading": "しーあいしーでぃー", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_27", "word": "llama.cpp", "reading": "llama.cpp", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_28", "word": "Node.js", "reading": "node.js", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_29", "word": "C++", "reading": "c++", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_30", "word": "SLM", "reading": "slm", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_31", "word": "VAD", "reading": "vad", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_32", "word": "IME", "reading": "ime", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_33", "word": "UI", "reading": "ui", "priority": "NORMAL", "is_enabled": True},
    {"id": "ud_34", "word": "kubectl", "reading": "kubectl", "priority": "HIGH", "is_enabled": True},
    # Multi-word phrases
    {"id": "ud_35", "word": "Google DeepMind", "reading": "google deepmind", "priority": "HIGH", "is_enabled": True},
    {"id": "ud_36", "word": "Pixel 10 Pro", "reading": "pixel 10 pro", "priority": "HIGH", "is_enabled": True},
    # Controls
    {"id": "ud_37", "word": "DisabledTerm", "reading": "disabledterm", "priority": "HIGH", "is_enabled": False},
    {"id": "ud_38", "word": "Go", "reading": "go", "priority": "NORMAL", "is_enabled": True},
]

POSITIVE_CASES = [
    # Mixed-case / CamelCase / bilingual Kana-reading entries
    {"category": "camelcase_compound", "target": "JotInput", "spoken": "I use JotInput on my Android phone every day.", "expected": "I use JotInput on my Android phone every day."},
    {"category": "camelcase_compound", "target": "JotInput", "spoken": "JotInput's voice recognition runs locally.", "expected": "JotInput's voice recognition runs locally."},
    {"category": "camelcase_compound", "target": "ReazonSpeech", "spoken": "ReazonSpeech transcribes Japanese audio accurately.", "expected": "ReazonSpeech transcribes Japanese audio accurately."},
    {"category": "camelcase_compound", "target": "ReazonSpeech", "spoken": "We benchmarked ReazonSpeech on mobile CPUs.", "expected": "We benchmarked ReazonSpeech on mobile CPUs."},
    {"category": "camelcase_compound", "target": "PyTorch", "spoken": "We trained the acoustic model using PyTorch.", "expected": "We trained the acoustic model using PyTorch."},
    {"category": "camelcase_compound", "target": "PyTorch", "spoken": "PyTorch's eager execution makes debugging easy.", "expected": "PyTorch's eager execution makes debugging easy."},
    {"category": "camelcase_compound", "target": "GraphQL", "spoken": "Our backend exposes a unified GraphQL schema.", "expected": "Our backend exposes a unified GraphQL schema."},
    {"category": "camelcase_compound", "target": "gRPC", "spoken": "The microservices communicate over gRPC streams.", "expected": "The microservices communicate over gRPC streams."},
    {"category": "camelcase_compound", "target": "Tailscale", "spoken": "I connected to the build server via Tailscale.", "expected": "I connected to the build server via Tailscale."},
    {"category": "camelcase_compound", "target": "Tailscale", "spoken": "Tailscale's mesh network simplifies remote debugging.", "expected": "Tailscale's mesh network simplifies remote debugging."},
    {"category": "camelcase_compound", "target": "FastConformer", "spoken": "The FastConformer encoder runs in a single pass.", "expected": "The FastConformer encoder runs in a single pass."},
    {"category": "camelcase_compound", "target": "Zipformer", "spoken": "The Zipformer transducer achieves low character error rate.", "expected": "The Zipformer transducer achieves low character error rate."},
    {"category": "camelcase_compound", "target": "Datadog", "spoken": "Check the memory metrics in Datadog right now.", "expected": "Check the memory metrics in Datadog right now."},
    {"category": "camelcase_compound", "target": "Cloudflare", "spoken": "We deployed the worker edge script to Cloudflare.", "expected": "We deployed the worker edge script to Cloudflare."},
    {"category": "camelcase_compound", "target": "Cloudflare", "spoken": "Cloudflare's DNS resolver lowered our latency.", "expected": "Cloudflare's DNS resolver lowered our latency."},
    {"category": "camelcase_compound", "target": "LangChain", "spoken": "We built a retrieval agent with LangChain.", "expected": "We built a retrieval agent with LangChain.", "context_before": "We are building an LLM pipeline. "},
    {"category": "camelcase_compound", "target": "SymSpell", "spoken": "The English typo corrector relies on SymSpell.", "expected": "The English typo corrector relies on SymSpell."},
    {"category": "camelcase_compound", "target": "Supabase", "spoken": "Authentication and storage are hosted on Supabase.", "expected": "Authentication and storage are hosted on Supabase."},
    {"category": "camelcase_compound", "target": "Supabase", "spoken": "Supabase's row level security protects user records.", "expected": "Supabase's row level security protects user records."},
    {"category": "camelcase_compound", "target": "KanaKanji", "spoken": "The KanaKanji lattice search runs in milliseconds.", "expected": "The KanaKanji lattice search runs in milliseconds."},
    {"category": "camelcase_compound", "target": "SudachiDict", "spoken": "We downloaded the optional SudachiDict lexicon.", "expected": "We downloaded the optional SudachiDict lexicon."},
    {"category": "camelcase_compound", "target": "vLLM", "spoken": "We benchmarked throughput using vLLM on Linux.", "expected": "We benchmarked throughput using vLLM on Linux."},
    {"category": "camelcase_compound", "target": "OAuth", "spoken": "The client refreshes OAuth access tokens automatically.", "expected": "The client refreshes OAuth access tokens automatically."},
    {"category": "camelcase_compound", "target": "NixOS", "spoken": "Our reproducible build box runs NixOS.", "expected": "Our reproducible build box runs NixOS."},
    {"category": "camelcase_compound", "target": "Antigravity", "spoken": "Let's test this workflow inside Antigravity.", "expected": "Let's test this workflow inside Antigravity."},

    # Proper nouns / Japanese names in English
    {"category": "proper_nouns_ja", "target": "Kurihara", "spoken": "Please send the report to Kurihara before noon.", "expected": "Please send the report to Kurihara before noon."},
    {"category": "proper_nouns_ja", "target": "Kurihara", "spoken": "Kurihara's pull request fixed the regression.", "expected": "Kurihara's pull request fixed the regression."},
    {"category": "proper_nouns_ja", "target": "Kenichi", "spoken": "Kenichi reviewed the architectural design document.", "expected": "Kenichi reviewed the architectural design document."},
    {"category": "proper_nouns_ja", "target": "Anthropic", "spoken": "We read the safety paper published by Anthropic.", "expected": "We read the safety paper published by Anthropic."},
    {"category": "proper_nouns_ja", "target": "Qwen", "spoken": "The on-device reranker uses Qwen for scoring.", "expected": "The on-device reranker uses Qwen for scoring.", "context_before": "We loaded the GGUF language model. "},
    {"category": "proper_nouns_ja", "target": "Qwen", "spoken": "Qwen achieved high top-one accuracy after tuning.", "expected": "Qwen achieved high top-one accuracy after tuning.", "context_before": "Benchmarking local language models: "},
    {"category": "proper_nouns_ja", "target": "Mozc", "spoken": "We updated the Mozc dictionary.", "expected": "We updated the Mozc dictionary.", "context_before": "Working on Japanese IME conversion. "},
    {"category": "proper_nouns_ja", "target": "Mozc", "spoken": "The Mozc connection matrix fits inside the package.", "expected": "The Mozc connection matrix fits inside the package.", "context_before": "Japanese morphological analysis: "},
    {"category": "proper_nouns_ja", "target": "Konsist", "spoken": "Run Konsist architecture tests.", "expected": "Run Konsist architecture tests.", "context_before": "Verify the DDD bounded contexts. "},
    {"category": "proper_nouns_ja", "target": "Konsist", "spoken": "Our CI pipeline checks Kotlin modules with Konsist.", "expected": "Our CI pipeline checks Kotlin modules with Konsist."},

    # Acronyms & symbol-bearing tech terms
    {"category": "acronyms_symbols", "target": "CI/CD", "spoken": "Our C I slash C D workflow builds the debug package.", "expected": "Our CI/CD workflow builds the debug package."},
    {"category": "acronyms_symbols", "target": "CI/CD", "spoken": "Check the CI/CD logs for any failing unit test.", "expected": "Check the CI/CD logs for any failing unit test."},
    {"category": "acronyms_symbols", "target": "llama.cpp", "spoken": "Inference runs natively through llama dot cpp.", "expected": "Inference runs natively through llama.cpp."},
    {"category": "acronyms_symbols", "target": "llama.cpp", "spoken": "We updated the llama dot cpp submodule yesterday.", "expected": "We updated the llama.cpp submodule yesterday."},
    {"category": "acronyms_symbols", "target": "Node.js", "spoken": "The script requires Node dot JS version twenty.", "expected": "The script requires Node.js version twenty."},
    {"category": "acronyms_symbols", "target": "C++", "spoken": "The core decoder is written in C plus plus.", "expected": "The core decoder is written in C++."},
    {"category": "acronyms_symbols", "target": "SLM", "spoken": "Our S L M reranker runs completely offline.", "expected": "Our SLM reranker runs completely offline."},
    {"category": "acronyms_symbols", "target": "SLM", "spoken": "We evaluated the SLM latency on mobile cores.", "expected": "We evaluated the SLM latency on mobile cores."},
    {"category": "acronyms_symbols", "target": "VAD", "spoken": "The V A D module detects pauses within two hundred milliseconds.", "expected": "The VAD module detects pauses within two hundred milliseconds."},
    {"category": "acronyms_symbols", "target": "VAD", "spoken": "Tune the VAD silence threshold carefully.", "expected": "Tune the VAD silence threshold carefully."},
    {"category": "acronyms_symbols", "target": "IME", "spoken": "The Android I M E handles both speech and touch.", "expected": "The Android IME handles both speech and touch."},
    {"category": "acronyms_symbols", "target": "IME", "spoken": "Switch the default IME in system settings.", "expected": "Switch the default IME in system settings."},
    {"category": "acronyms_symbols", "target": "UI", "spoken": "We simplified the main U I into three tabs.", "expected": "We simplified the main UI into three tabs."},
    {"category": "acronyms_symbols", "target": "UI", "spoken": "Inspect the UI layout hierarchy on the phone.", "expected": "Inspect the UI layout hierarchy on the phone."},
    {"category": "acronyms_symbols", "target": "kubectl", "spoken": "Run kubectl apply on the production manifest.", "expected": "Run kubectl apply on the production manifest.", "context_before": "Check the Kubernetes cluster. "},
    {"category": "acronyms_symbols", "target": "kubectl", "spoken": "Use kuber control get pods to inspect the cluster.", "expected": "Use kubectl get pods to inspect the cluster.", "context_before": "Kubernetes deployment debug: "},
    {"category": "acronyms_symbols", "target": "gRPC", "spoken": "We defined the service interface with G R P C.", "expected": "We defined the service interface with gRPC."},

    # Multi-word phrases
    {"category": "multiword_phrases", "target": "Google DeepMind", "spoken": "Researchers at Google DeepMind published the benchmark.", "expected": "Researchers at Google DeepMind published the benchmark."},
    {"category": "multiword_phrases", "target": "Google DeepMind", "spoken": "She joined Google DeepMind as a research engineer.", "expected": "She joined Google DeepMind as a research engineer."},
    {"category": "multiword_phrases", "target": "Pixel 10 Pro", "spoken": "We verified the keyboard layout on Pixel 10 Pro.", "expected": "We verified the keyboard layout on Pixel 10 Pro."},
    {"category": "multiword_phrases", "target": "Pixel 10 Pro", "spoken": "Deploy the debug build to Pixel ten Pro over Wi-Fi.", "expected": "Deploy the debug build to Pixel 10 Pro over Wi-Fi."},

    # Additional multi-term sentences & bilingual Kana entries
    {"category": "multi_term_sentences", "target": "PyTorch", "spoken": "Export the PyTorch checkpoint into ONNX format.", "expected": "Export the PyTorch checkpoint into ONNX format."},
    {"category": "multi_term_sentences", "target": "GraphQL", "spoken": "Compare GraphQL queries against REST endpoints.", "expected": "Compare GraphQL queries against REST endpoints."},
    {"category": "multi_term_sentences", "target": "Datadog", "spoken": "Datadog alerts triggered when CPU usage spiked.", "expected": "Datadog alerts triggered when CPU usage spiked."},
    {"category": "multi_term_sentences", "target": "FastConformer", "spoken": "Parakeet uses a FastConformer backbone for streaming.", "expected": "Parakeet uses a FastConformer backbone for streaming."},
    {"category": "multi_term_sentences", "target": "Zipformer", "spoken": "ReazonSpeech relies on a Zipformer encoder.", "expected": "ReazonSpeech relies on a Zipformer encoder."},
    {"category": "multi_term_sentences", "target": "KanaKanji", "spoken": "Our KanaKanji conversion uses Mozc bigram costs.", "expected": "Our KanaKanji conversion uses Mozc bigram costs."},
    {"category": "multi_term_sentences", "target": "SudachiDict", "spoken": "SudachiDict expands proper noun coverage offline.", "expected": "SudachiDict expands proper noun coverage offline."},
    {"category": "multi_term_sentences", "target": "vLLM", "spoken": "Compare llama dot cpp latency against vLLM serving.", "expected": "Compare llama.cpp latency against vLLM serving."},
    {"category": "multi_term_sentences", "target": "SymSpell", "spoken": "SymSpell generates typo candidates in under a millisecond.", "expected": "SymSpell generates typo candidates in under a millisecond."},
    {"category": "multi_term_sentences", "target": "NixOS", "spoken": "We provisioned the CI runner on NixOS.", "expected": "We provisioned the CI runner on NixOS."},
    {"category": "multi_term_sentences", "target": "Antigravity", "spoken": "Antigravity orchestrated the verification steps automatically.", "expected": "Antigravity orchestrated the verification steps automatically."},
    {"category": "multi_term_sentences", "target": "JotInput", "spoken": "Open JotInput settings to configure voice punctuation.", "expected": "Open JotInput settings to configure voice punctuation."},
]

ADVERSARIAL_NEGATIVES = [
    {"category": "adversarial_lookalike", "lookalike_for": "Konsist", "sentence": "The tests consist of three parts."},
    {"category": "adversarial_lookalike", "lookalike_for": "Mozc", "sentence": "We visited the blue mosque in Istanbul."},
    {"category": "adversarial_lookalike", "lookalike_for": "LangChain", "sentence": "This caused a long chain of events."},
    {"category": "adversarial_lookalike", "lookalike_for": "JotInput", "sentence": "Please jot down your input before the meeting."},
    {"category": "adversarial_lookalike", "lookalike_for": "VAD", "sentence": "That was a bad idea from the start."},
    {"category": "adversarial_lookalike", "lookalike_for": "IME", "sentence": "I am happy to help you today."},
    {"category": "adversarial_common_case", "lookalike_for": "Go", "sentence": "Let's go home after we finish."},
    {"category": "adversarial_common_case", "lookalike_for": "Go", "sentence": "Don't go too far ahead on the trail."},
    {"category": "adversarial_common_case", "lookalike_for": "Go", "sentence": "I had to go to the grocery store."},
    {"category": "adversarial_lookalike", "lookalike_for": "OAuth", "sentence": "He took an oath to tell the truth."},
    {"category": "adversarial_lookalike", "lookalike_for": "Qwen", "sentence": "My friend Quinn lives in Seattle."},
    {"category": "adversarial_lookalike", "lookalike_for": "PyTorch", "sentence": "He ate apple pie by the torch."},
    {"category": "adversarial_lookalike", "lookalike_for": "Tailscale", "sentence": "The cat's tail tipped the scale."},
    {"category": "adversarial_lookalike", "lookalike_for": "Datadog", "sentence": "The data shows my dog is healthy."},
    {"category": "adversarial_lookalike", "lookalike_for": "Kurihara", "sentence": "Let's eat curry for dinner tonight."},
    {"category": "adversarial_lookalike", "lookalike_for": "NixOS", "sentence": "Nick's office is on the fifth floor."},
    {"category": "adversarial_lookalike", "lookalike_for": "OAuth", "sentence": "Meet me at the train station."},
    {"category": "adversarial_lookalike", "lookalike_for": "ReazonSpeech", "sentence": "There is no reason for a long speech today."},
    {"category": "adversarial_lookalike", "lookalike_for": "FastConformer", "sentence": "We need a fast confirmation of the schedule."},
    {"category": "adversarial_lookalike", "lookalike_for": "Zipformer", "sentence": "Zip up the former folder first."},
    {"category": "adversarial_lookalike", "lookalike_for": "Supabase", "sentence": "The super base coat dried very quickly."},
    {"category": "adversarial_lookalike", "lookalike_for": "Cloudflare", "sentence": "There was a bright flare above the cloud."},
    {"category": "adversarial_lookalike", "lookalike_for": "SymSpell", "sentence": "Can you spell the simple word clearly?"},
    {"category": "adversarial_lookalike", "lookalike_for": "GraphQL", "sentence": "The graph has a cool curve at the end."},
    {"category": "adversarial_lookalike", "lookalike_for": "gRPC", "sentence": "Grip the handle tightly while climbing."},
    {"category": "adversarial_lookalike", "lookalike_for": "llama.cpp", "sentence": "The llama walked slowly across the meadow."},
    {"category": "adversarial_lookalike", "lookalike_for": "Node.js", "sentence": "Check the central node on the network map."},
    {"category": "adversarial_lookalike", "lookalike_for": "kubectl", "sentence": "The wooden cube fell off the table."},
    {"category": "adversarial_lookalike", "lookalike_for": "Antigravity", "sentence": "Gravity pulls every object toward the ground."},
    {"category": "adversarial_lookalike", "lookalike_for": "Anthropic", "sentence": "She read a textbook on anthropology."},
    {"category": "adversarial_lookalike", "lookalike_for": "Pixel 10 Pro", "sentence": "I see a bright pixel near the corner."},
    {"category": "adversarial_lookalike", "lookalike_for": "Google DeepMind", "sentence": "He has a deep mind for complex puzzles."},
    {"category": "adversarial_lookalike", "lookalike_for": "CI/CD", "sentence": "See if the music CD plays properly."},
    {"category": "adversarial_lookalike", "lookalike_for": "UI", "sentence": "Look at you, I am doing well."},
    {"category": "adversarial_lookalike", "lookalike_for": "SLM", "sentence": "The slim binder sits on the desk."},
    {"category": "adversarial_lookalike", "lookalike_for": "Kenichi", "sentence": "Ken had an itchy throat this morning."},
    {"category": "adversarial_lookalike", "lookalike_for": "SymSpell", "sentence": "The sum of two numbers is five."},
    {"category": "adversarial_lookalike", "lookalike_for": "Tailscale", "sentence": "Wait until the scale settles down."},
    {"category": "adversarial_lookalike", "lookalike_for": "DisabledTerm", "sentence": "The disabled term was removed from the contract."},
    {"category": "adversarial_lookalike", "lookalike_for": "C++", "sentence": "See what happens plus what we planned."},
    {"category": "adversarial_lookalike", "lookalike_for": "vLLM", "sentence": "The film received great reviews yesterday."},
    {"category": "adversarial_lookalike", "lookalike_for": "Antigravity", "sentence": "Please handle fragile glassware with gravity and care."},
]

VOICES = [
    "en-US-AndrewNeural",
    "en-US-AriaNeural",
    "en-US-GuyNeural",
    "en-US-JennyNeural",
]

def build():
    positive_items = []
    for i, item in enumerate(POSITIVE_CASES, start=1):
        positive_items.append({
            "id": f"ud_pos_{i:04d}",
            "split": "positive",
            "category": item["category"],
            "target": item["target"],
            "sentence": item["spoken"],
            "expected": item["expected"],
            "context_before": item.get("context_before", ""),
            "voice": VOICES[(i - 1) % len(VOICES)],
        })

    negative_items = []
    for i, item in enumerate(ADVERSARIAL_NEGATIVES, start=1):
        negative_items.append({
            "id": f"ud_neg_{i:04d}",
            "split": "adversarial_negative",
            "category": item["category"],
            "lookalike_for": item["lookalike_for"],
            "sentence": item["sentence"],
            "expected": item["sentence"],
            "context_before": "",
            "voice": VOICES[(i - 1) % len(VOICES)],
        })

    out_dir = os.path.dirname(os.path.abspath(__file__))
    out_file = os.path.join(out_dir, "user_dict_dataset.json")
    payload = {
        "dictionary_entries": DICTIONARY_ENTRIES,
        "positive_cases": positive_items,
        "adversarial_negatives": negative_items,
    }
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(
        f"Built English voice user-dict dataset: "
        f"{len(DICTIONARY_ENTRIES)} dict entries, "
        f"{len(positive_items)} positive cases, "
        f"{len(negative_items)} adversarial negatives -> {out_file}"
    )

if __name__ == "__main__":
    build()
