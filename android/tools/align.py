import struct, sys, zipfile
# zipalign replacement: rewrite in.apk → out.apk with every stored entry's data on a 4-byte boundary
src, dst = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2], 'w')
for i in src.infolist():
    zi = zipfile.ZipInfo(i.filename, i.date_time)
    zi.compress_type, zi.external_attr = i.compress_type, i.external_attr
    if i.compress_type == zipfile.ZIP_STORED:
        off = dst.fp.tell() + 30 + len(i.filename.encode()) + 6
        pad = -off % 4
        zi.extra = struct.pack('<HHH', 0xD935, 2 + pad, 4) + b'\0' * pad  # Android's alignment extra field
    dst.writestr(zi, src.read(i))
dst.close()
