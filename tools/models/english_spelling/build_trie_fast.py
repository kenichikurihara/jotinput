import math
import os
import time

model_dir = "tools/models/futo_swipe"
vocab = [line.strip() for line in open(os.path.join(model_dir, "vocab.txt"), encoding='utf-8')]

# Build BK-tree or Length-bucketed index
length_buckets = {}
for i, word in enumerate(vocab):
    w_lower = word.lower()
    l = len(w_lower)
    if l not in length_buckets:
        length_buckets[l] = []
    # Store word, frequency rank (i is rank, 0 is most frequent), first letter
    length_buckets[l].append((w_lower, word, i))

print("Vocabulary loaded:", len(vocab))
