import json
import os

def generate_100_words():
    vocab_path = "tools/models/futo_swipe/vocab.txt"
    if not os.path.exists(vocab_path):
        raise FileNotFoundError(f"{vocab_path} not found")

    selected_words = []
    seen = set()

    with open(vocab_path, "r", encoding="utf-8") as f:
        for line in f:
            word = line.strip()
            # Clean word: pure ascii alphabet, lowercase preferred or simple, length >= 5
            word_lower = word.lower()
            if not word.isalpha():
                continue
            if len(word) < 5 or len(word) > 13:
                continue
            if word_lower in seen:
                continue
            
            seen.add(word_lower)
            selected_words.append(word_lower)
            if len(selected_words) >= 100:
                break

    # Ensure "example" is explicitly included in the 100 words (user prompt requirement)
    if "example" not in selected_words:
        selected_words[99] = "example"

    # Generate test cases: for each target word, create prefix samples
    # e.g., for "example" (len 7), prefixes: "exam" (len 4), "examp" (len 5), "exampl" (len 6)
    dataset = []
    for rank, word in enumerate(selected_words):
        L = len(word)
        # Choose a representative primary prefix (like user mentioned: examp -> example)
        # typically around L - 2 or L - 3
        primary_prefix_len = max(3, L - 2)
        prefixes = []
        for plen in range(max(3, L - 3), L):
            prefixes.append(word[:plen])
        
        dataset.append({
            "rank": rank + 1,
            "target": word,
            "length": L,
            "primary_prefix": word[:primary_prefix_len],
            "prefixes": prefixes
        })

    out_path = "eval/english_completion/target_100_words.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(dataset, f, indent=2, ensure_ascii=False)

    print(f"Generated {len(dataset)} words with prefixes in {out_path}")
    print("Sample words:", [d["target"] for d in dataset[:10]])
    ex = next(d for d in dataset if d["target"] == "example")
    print("Example case:", ex)

if __name__ == "__main__":
    generate_100_words()
