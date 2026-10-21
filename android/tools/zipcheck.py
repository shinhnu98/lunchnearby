import struct, sys, zipfile
# prints stored entries whose data doesn't start on a 4-byte boundary (reads the real local headers)
bad = 0
for f in sys.argv[1:]:
    raw = open(f, 'rb').read()
    for i in zipfile.ZipFile(f).infolist():
        if i.compress_type: continue
        n, e = struct.unpack('<HH', raw[i.header_offset+26:i.header_offset+30])
        off = i.header_offset + 30 + n + e
        if off % 4: bad += 1; print(f, i.filename, 'misaligned by', off % 4)
print('alignment ok' if not bad else f'{bad} misaligned'); sys.exit(1 if bad else 0)
