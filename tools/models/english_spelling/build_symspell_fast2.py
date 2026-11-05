import math
import os
import time

model_dir = "tools/models/futo_swipe"
vocab = [line.strip() for line in open(os.path.join(model_dir, "vocab.txt"), encoding='utf-8')]

# QWERTY Key Proximity Map
QWERTY_ROWS = [
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm"
]
key_coords = {}
for r, row in enumerate(QWERTY_ROWS):
    for c, char in enumerate(row):
        offset = 0.0 if r == 0 else (0.5 if r == 1 else 1.0)
        key_coords[char] = (c + offset, float(r))

def key_dist(c1, c2):
    if c1 == c2: return 0.0
    if c1 in key_coords and c2 in key_coords:
        x1, y1 = key_coords[c1]
        x2, y2 = key_coords[c2]
        return math.sqrt((x1 - x2)**2 + (y1 - y2)**2)
    return 3.0

def edit_and_spatial_cost(typed, cand):
    m, n = len(typed), len(cand)
    dp = [[0.0] * (n + 1) for _ in range(m + 1)]
    for i in range(m + 1): dp[i][0] = i * 1.5
    for j in range(n + 1): dp[0][j] = j * 1.5
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            kd = key_dist(typed[i-1], cand[j-1])
            sub_cost = 0.0 if kd == 0.0 else (0.8 + 0.3 * min(kd, 2.0))
            dp[i][j] = min(
                dp[i-1][j] + 1.2,
                dp[i][j-1] + 1.2,
                dp[i-1][j-1] + sub_cost
            )
            if i > 1 and j > 1 and typed[i-1] == cand[j-2] and typed[i-2] == cand[j-1]:
                dp[i][j] = min(dp[i][j], dp[i-2][j-2] + 0.6)
    return dp[m][n]

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
deletes_dict = {}
word_to_count = {}
for rank, word in enumerate(vocab):
    w_lower = word.lower()
    count = int(10000000.0 / (rank + 1))
    if w_lower not in word_to_count:
        word_to_count[w_lower] = (word, count)
        for d in get_deletes(w_lower, max_edit_distance=2):
            if d not in deletes_dict:
                deletes_dict[d] = []
            deletes_dict[d].append(w_lower)

print(f"Deletes 2 built in {time.time()-t0:.2f}s: {len(deletes_dict)} keys")

def lookup_spatial(typed, top_n=5):
    typed = typed.lower()
    cands = set()
    if typed in word_to_count:
        cands.add(typed)
    for d in get_deletes(typed, max_edit_distance=2):
        if d in word_to_count:
            cands.add(d)
        if d in deletes_dict:
            for w in deletes_dict[d]:
                cands.add(w)
    
    scored = []
    for w in cands:
        orig_word, count = word_to_count[w]
        cost = edit_and_spatial_cost(typed, w)
        if cost <= 3.0:
            log_freq = math.log(count + 1)
            # Total score = log_freq - 2.5 * cost
            score = log_freq - 2.8 * cost
            scored.append((orig_word, score, cost))
    
    scored.sort(key=lambda x: x[1], reverse=True)
    return [x[0] for x in scored[:top_n]]

for test in ["teh", "quikc", "helo", "alredy", "wrok", "morngin", "definately", "thier"]:
    t_start = time.perf_counter()
    res = lookup_spatial(test)
    t_elapsed = (time.perf_counter() - t_start) * 1000
    print(f"Lookup '{test}': {res} ({t_elapsed:.2f} ms)")
