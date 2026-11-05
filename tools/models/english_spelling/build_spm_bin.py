import struct
from sentencepiece import sentencepiece_model_pb2 as model

m = model.ModelProto()
m.ParseFromString(open('tools/models/english_spelling/spm.model', 'rb').read())

# Binary format:
# Header:
#   magic: 4 bytes "SPM1"
#   count: uint32
# Array of entries:
#   piece_len: uint16 (bytes)
#   piece_bytes: bytes (utf-8)
#   score: float32
with open('tools/models/english_spelling/spm_unigram.bin', 'wb') as f:
    f.write(b'SPM1')
    f.write(struct.pack('<I', len(m.pieces)))
    for p in m.pieces:
        b = p.piece.encode('utf-8')
        f.write(struct.pack('<Hf', len(b), p.score))
        f.write(b)

print(f"Generated spm_unigram.bin with {len(m.pieces)} pieces")
