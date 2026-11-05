import unicodedata
from sentencepiece import sentencepiece_model_pb2 as model
import sentencepiece as spm

sp = spm.SentencePieceProcessor(model_file='tools/models/english_spelling/spm.model')
m = model.ModelProto()
m.ParseFromString(open('tools/models/english_spelling/spm.model', 'rb').read())

piece2score = {}
piece2id = {}
for i, piece in enumerate(m.pieces):
    piece2score[piece.piece] = piece.score
    piece2id[piece.piece] = i

SPACE_CHAR = chr(9601) # LOWER ONE EIGHTH BLOCK

def viterbi_unigram(text):
    norm_text = text.strip()
    norm_text = norm_text.replace(' ', SPACE_CHAR)
    if not norm_text.startswith(SPACE_CHAR):
        norm_text = SPACE_CHAR + norm_text
    
    n = len(norm_text)
    best_score = [-1e18] * (n + 1)
    best_prev = [-1] * (n + 1)
    best_piece = [""] * (n + 1)
    best_score[0] = 0.0
    
    for i in range(n):
        if best_score[i] < -1e17:
            continue
        for length in range(1, min(n - i + 1, 32)):
            sub = norm_text[i:i+length]
            if sub in piece2score:
                sc = best_score[i] + piece2score[sub]
                if sc > best_score[i + length]:
                    best_score[i + length] = sc
                    best_prev[i + length] = i
                    best_piece[i + length] = sub

    tokens = []
    curr = n
    while curr > 0:
        p = best_prev[curr]
        if p == -1:
            print("Failed to tokenize at", curr, norm_text[curr-1])
            break
        tokens.append(best_piece[curr])
        curr = p
    tokens.reverse()
    token_ids = [piece2id[t] for t in tokens]
    return tokens, token_ids

test_sentences = [
    "i cant beleive its alredy friday",
    "the quick brown fox jumps over the lazy dog",
    "hello world",
    "how are you doing today?",
    "what's up",
    "pleas let me knwo if this works"
]

all_match = True
for s in test_sentences:
    sp_ids = sp.encode_as_ids(s)
    sp_pieces = sp.encode_as_pieces(s)
    my_pieces, my_ids = viterbi_unigram(s)
    match = (sp_ids == my_ids)
    print(f"Sentence: {s}")
    print(f"  Match: {match}")
    if not match:
        all_match = False
        print(f"  SP: {sp_pieces} {sp_ids}")
        print(f"  MY: {my_pieces} {my_ids}")

print("All test sentences matched:", all_match)
