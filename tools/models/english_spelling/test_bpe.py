import sentencepiece as spm

sp = spm.SentencePieceProcessor(model_file='tools/models/english_spelling/spm.model')

# Check if model is BPE: BPE models merge consecutive characters/pieces.
# Let's inspect encode_as_pieces on various sentences
sentences = [
    "i cant beleive its alredy friday",
    "the quick brown fox jumps over the lazy dog",
    "hello world",
    "supercalifragilisticexpialidocious"
]

for s in sentences:
    pieces = sp.encode_as_pieces(s)
    ids = sp.encode_as_ids(s)
    print(s)
    print("  Pieces:", pieces)
    print("  IDs:   ", ids)
    print("  Decoded:", sp.decode(ids))
