import os
import time

model_dir = "tools/models/futo_swipe"
vocab = [line.strip() for line in open(os.path.join(model_dir, "vocab.txt"), encoding='utf-8')]

# Build frequency dictionary:
# vocab.txt is ordered by frequency (the, to, of, and, in, a, was, is ...)
# Rank 0 is ~100,000,000 count, Zipf distribution
deletes_dict = {}

def get_deletes(word, max_edit_distance=2):
    deletes = set()
    queue = [word]
    for _ in range(max_edit_distance):
        temp = []
        for w in queue:
            if len(w) > 1:
                for i in range(len(w)):
                    del_word = w[:i] + w[i+1:]
                    if del_word not in deletes:
                        deletes.add(del_word)
                        temp.append(del_word)
        queue = temp
    return deletes

t0 = time.time()
word_to_count = {}
N = len(vocab)
for rank, word in enumerate(vocab):
    w_lower = word.lower()
    # Estimate frequency count using Zipf: count = 10^7 / (rank + 1)
    count = int(10000000.0 / (rank + 1))
    if w_lower not in word_to_count:
        word_to_count[w_lower] = (word, count)
        # Generate deletes of distance 1 and 2
        for d in get_deletes(w_lower, max_edit_distance=1):
            if d not in deletes_dict:
                deletes_dict[d] = []
            deletes_dict[d].append(w_lower)

dt = time.time() - t0
print(f"Built SymSpell deletes index in {dt:.2f}s: {len(deletes_dict)} delete keys")

# Test lookup speed
def lookup(typed):
    typed = typed.lower()
    cands = set()
    if typed in word_to_count:
        cands.add(typed)
    # distance 1 deletes of typed
    for d in get_deletes(typed, max_edit_distance=1):
        if d in word_to_count:
            cands.add(d)
        if d in deletes_dict:
            for w in deletes_dict[d]:
                cands.add(w)
    
    # rank by frequency
    ranked = sorted(list(cands), key=lambda w: word_to_count[w][1], reverse=True)
    return [word_to_count[w][0] for w in ranked[:5]]

for test in ["teh", "quikc", "helo", "alredy", "wrok", "morngin"]:
    t_start = time.perf_counter()
    res = lookup(test)
    t_elapsed = (time.perf_counter() - t_start) * 1000000
    print(f"Lookup '{test}': {res} ({t_elapsed:.1f} µs)")
