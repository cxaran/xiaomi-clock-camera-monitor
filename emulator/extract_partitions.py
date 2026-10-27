#!/usr/bin/env python3
"""Read files only; extract GPT/liblp partitions into NEW output directories.

No block devices, no mount, no ADB, no firmware writes. All image commands
require an expected SHA-256 obtained independently when validating the backup.
Formats: 512/4096-sector GPT; Android sparse v1; single-device liblp v10.x.
"""
import argparse
import bisect
import hashlib
import json
from pathlib import Path
import re
import stat
import struct
import sys
import zlib

CHUNK = 1024 * 1024


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(CHUNK), b''):
            h.update(b)
    return h.hexdigest()


class Image:
    """Random-access raw or Android-sparse view; never materializes next to input."""
    def __init__(self, path, expected):
        self.path = Path(path).resolve(strict=True)
        require(stat.S_ISREG(self.path.stat().st_mode), 'Input must be a regular file')
        require(re.fullmatch(r'[0-9a-fA-F]{64}', expected) is not None, 'Expected SHA-256 required')
        before = self.path.stat()
        self.sha256 = digest(self.path)
        require(self.sha256 == expected.lower(), 'SHA-256 mismatch; input not accepted')
        self.fd = self.path.open('rb')
        after = self.path.stat()
        require((before.st_size, before.st_mtime_ns) == (after.st_size, after.st_mtime_ns),
                'Input changed during validation')
        self.initial_stat = after
        self.physical_size = after.st_size
        self.size = self.physical_size
        self.sparse = None
        self.chunks = []
        if self.physical_size >= 4 and self._physical(0, 4) == b'\x3a\xff\x26\xed':
            self._sparse_index()

    def _physical(self, offset, size):
        require(offset >= 0 and size >= 0 and offset + size <= self.physical_size,
                'Read outside file / truncated input')
        self.fd.seek(offset)
        data = self.fd.read(size)
        require(len(data) == size, 'Short read')
        return data

    def _sparse_index(self):
        magic, major, minor, fhs, chs, block, blocks, count, crc = struct.unpack(
            '<IHHHHIIII', self._physical(0, 28))
        require(major == 1 and fhs >= 28 and chs >= 12 and block >= 4 and block % 4 == 0,
                'Unsupported sparse header')
        require(count <= self.physical_size // 12, 'Impossible sparse chunk count')
        offset, logical = fhs, 0
        for _ in range(count):
            kind, _, nblocks, total = struct.unpack('<HHII', self._physical(offset, 12))
            require(total >= chs and offset + total <= self.physical_size, 'Invalid sparse chunk length')
            size, payload = nblocks * block, offset + chs
            if kind == 0xCAC1:
                require(total == chs + size, 'Invalid RAW chunk')
                value = payload
            elif kind == 0xCAC2:
                require(total == chs + 4, 'Invalid FILL chunk')
                value = self._physical(payload, 4)
            elif kind == 0xCAC3:
                require(total == chs, 'Invalid DONT_CARE chunk')
                value = None
            elif kind == 0xCAC4:
                require(nblocks == 0 and total == chs + 4, 'Invalid CRC32 chunk')
                offset += total
                continue
            else:
                raise ValueError(f'Unknown sparse chunk {kind:#x}')
            if size:
                self.chunks.append((logical, logical + size, kind, value))
                logical += size
            offset += total
        require(offset == self.physical_size and logical == blocks * block, 'Sparse length mismatch')
        self.size = logical
        self.starts = [x[0] for x in self.chunks]
        self.sparse = {'major': major, 'minor': minor, 'block_size': block, 'chunks': count,
                       'image_crc32': crc, 'crc32_verified': False,
                       'note': 'DONT_CARE reads as zero; global sparse CRC is not a backup validator'}

    def read(self, offset, size):
        require(offset >= 0 and size >= 0 and offset + size <= self.size, 'Read outside logical image')
        if not self.sparse:
            return self._physical(offset, size)
        data = bytearray()
        while size:
            index = bisect.bisect_right(self.starts, offset) - 1
            require(index >= 0, 'Sparse gap')
            start, end, kind, value = self.chunks[index]
            n = min(size, end - offset)
            require(n > 0, 'Sparse gap')
            if kind == 0xCAC1:
                data.extend(self._physical(value + offset - start, n))
            elif kind == 0xCAC2:
                phase = (offset - start) % 4
                data.extend((value * ((n + phase + 3) // 4))[phase:phase + n])
            else:
                data.extend(bytes(n))
            offset, size = offset + n, size - n
        return bytes(data)

    def unchanged(self):
        now = self.path.stat()
        require((now.st_size, now.st_mtime_ns) ==
                (self.initial_stat.st_size, self.initial_stat.st_mtime_ns), 'Input changed during operation')

    def close(self):
        self.fd.close()


def safe_name(name):
    require(bool(re.fullmatch(r'[A-Za-z0-9_.-]{1,80}', name)) and name not in ('.', '..'),
            f'Unsafe partition name {name!r}')
    return name


def gpt(image, sector):
    h = image.read(sector, sector)
    require(h[:8] == b'EFI PART', 'No primary GPT signature')
    revision, hsize, checksum = struct.unpack_from('<III', h, 8)
    require(92 <= hsize <= sector, 'Invalid GPT header size')
    check = bytearray(h[:hsize]); check[16:20] = bytes(4)
    require(zlib.crc32(check) == checksum, 'GPT header CRC mismatch')
    current, backup, first, last = struct.unpack_from('<QQQQ', h, 24)
    entry_lba, count, esize, ecrc = struct.unpack_from('<QIII', h, 72)
    require(current == 1 and 0 < first <= last < image.size // sector, 'GPT LBA range invalid')
    require(count <= 4096 and 128 <= esize <= 4096 and count * esize <= 16 * CHUNK,
            'GPT table size unsupported')
    table = image.read(entry_lba * sector, count * esize)
    require(zlib.crc32(table) == ecrc, 'GPT entries CRC mismatch')
    entries = []
    for i in range(count):
        e = table[i * esize:(i + 1) * esize]
        if e[:16] == bytes(16):
            continue
        start, end, attrs = struct.unpack_from('<QQQ', e, 32)
        name = e[56:128].decode('utf-16-le', errors='strict').split('\x00')[0]
        require(first <= start <= end <= last, f'Partition outside usable GPT area: {name}')
        entries.append({'index': i, 'name': name, 'first_lba': start, 'last_lba': end,
                        'offset': start * sector, 'size': (end - start + 1) * sector,
                        'attributes': attrs})
    ordered = sorted(entries, key=lambda p: p['offset'])
    require(all(a['offset'] + a['size'] <= b['offset'] for a, b in zip(ordered, ordered[1:])),
            'GPT partitions overlap')
    return {'format': 'gpt', 'revision': revision, 'sector_size': sector,
            'primary_header_crc_ok': True, 'entries_crc_ok': True,
            'backup_header_lba': backup, 'backup_header_verified': False, 'partitions': entries}


def geometry(image, offset):
    b = image.read(offset, 4096)
    magic, size, check, maximum, slots, block = struct.unpack_from('<II32sIII', b)
    require(magic == 0x616C4467 and 52 <= size <= 4096, 'Invalid liblp geometry')
    b = bytearray(b[:size]); b[8:40] = bytes(32)
    require(hashlib.sha256(b).digest() == check, 'liblp geometry checksum mismatch')
    require(maximum >= 128 and maximum % 512 == 0 and 1 <= slots <= 8 and
            block >= 512 and block % 512 == 0, 'Unsupported liblp geometry size')
    return {'metadata_max_size': maximum, 'slot_count': slots, 'logical_block_size': block,
            'geometry_offset': offset, 'geometry_sha256_ok': True}


def super_metadata(image, slot):
    errors = []
    geo = None
    for off in (4096, 8192):
        try:
            geo = geometry(image, off)
            break
        except (ValueError, struct.error) as e:
            errors.append(str(e))
    require(geo is not None, 'No valid geometry: ' + '; '.join(errors))
    require(0 <= slot < geo['slot_count'], 'Metadata slot outside available range')
    maximum = geo['metadata_max_size']
    for label, off in [('primary', 12288 + maximum * slot),
                       ('backup', 12288 + maximum * (geo['slot_count'] + slot))]:
        try:
            b = image.read(off, maximum)
            magic, major, minor, hsize, hcheck, tsize, tcheck = struct.unpack_from('<IHHI32sI32s', b)
            require(magic == 0x414C5030 and major == 10 and 0 <= minor <= 2,
                    'Unsupported liblp metadata version')
            require(128 <= hsize <= maximum and hsize + tsize <= maximum, 'liblp header/tables overflow')
            hb = bytearray(b[:hsize]); hb[12:44] = bytes(32)
            require(hashlib.sha256(hb).digest() == hcheck, 'liblp header checksum mismatch')
            tables = b[hsize:hsize + tsize]
            require(hashlib.sha256(tables).digest() == tcheck, 'liblp tables checksum mismatch')
            groups = []
            def table_entries(position, fmt):
                start, count, esize = struct.unpack_from('<III', b, position)
                needed = struct.calcsize(fmt)
                require(esize >= needed and start + count * esize <= tsize, 'liblp table outside bounds')
                return [struct.unpack_from(fmt, tables, start + i * esize) for i in range(count)]
            blocks = []
            for first, alignment, alignoff, size, name, flags in table_entries(116, '<QIIQ36sI'):
                blocks.append({'name': name.split(b'\0')[0].decode('ascii'), 'first_sector': first,
                               'size': size, 'alignment': alignment, 'alignment_offset': alignoff, 'flags': flags})
            require(len(blocks) == 1 and blocks[0]['size'] <= image.size,
                    'Only single-device super images are supported; missing device or truncated image')
            for name, flags, size in table_entries(104, '<36sIQ'):
                groups.append({'name': name.split(b'\0')[0].decode('ascii'), 'flags': flags, 'max_size': size})
            extents = []
            for sectors, kind, target, source in table_entries(92, '<QIQI'):
                require(kind in (0, 1), 'Unsupported liblp extent type')
                if kind == 0:
                    require(source == 0 and target >= blocks[0]['first_sector'] and
                            (target + sectors) * 512 <= blocks[0]['size'], 'liblp extent outside device')
                else:
                    require(target == 0, 'Invalid zero extent')
                extents.append({'size': sectors * 512, 'type': 'linear' if kind == 0 else 'zero',
                                'offset': target * 512, 'source': source})
            parts = []
            for name, attrs, first, count, group in table_entries(80, '<36sIIII'):
                name = safe_name(name.split(b'\0')[0].decode('ascii'))
                require(first + count <= len(extents) and group < len(groups), 'Invalid extent/group index')
                owned = extents[first:first + count]
                parts.append({'name': name, 'attributes': attrs, 'group': groups[group]['name'],
                              'size': sum(x['size'] for x in owned), 'extents': owned})
            require(len({p['name'] for p in parts}) == len(parts), 'Duplicate partition name')
            return {'format': 'liblp', 'version': f'{major}.{minor}', 'slot': slot,
                    'metadata_copy': label, 'metadata_offset': off, 'geometry': geo,
                    'header_sha256_ok': True, 'tables_sha256_ok': True,
                    'block_devices': blocks, 'groups': groups, 'partitions': parts,
                    'fallback_errors': errors}
        except (ValueError, struct.error) as e:
            errors.append(f'{label}: {e}')
    raise ValueError('No valid liblp metadata: ' + '; '.join(errors))


def copy_range(image, target, offset, size, zero=False):
    h = hashlib.sha256()
    with target.open('xb') as f:
        while size:
            n = min(size, CHUNK)
            data = bytes(n) if zero else image.read(offset, n)
            f.write(data); h.update(data)
            offset, size = offset + n, size - n
    return h.hexdigest()


def extract(image, report, out, selected):
    out = Path(out).resolve()
    require(out != image.path.parent and image.path not in out.parents, 'Output must be separate')
    out.mkdir(parents=True, exist_ok=False)
    items = report['partitions']
    if selected:
        require(set(selected) <= {p['name'] for p in items}, 'Requested partition not found')
        items = [p for p in items if p['name'] in selected]
    outputs = []
    for p in items:
        target = out / (safe_name(p['name']) + '.img')
        if report['format'] == 'gpt':
            sha = copy_range(image, target, p['offset'], p['size'])
        else:
            h = hashlib.sha256()
            with target.open('xb') as f:
                for extent in p['extents']:
                    size, offset = extent['size'], extent['offset']
                    while size:
                        n = min(size, CHUNK)
                        b = bytes(n) if extent['type'] == 'zero' else image.read(offset, n)
                        f.write(b); h.update(b)
                        size, offset = size - n, offset + n
            sha = h.hexdigest()
        require(target.stat().st_size == p['size'], 'Extracted partition size mismatch')
        outputs.append({'name': p['name'], 'path': str(target), 'size': p['size'], 'sha256': sha})
    return outputs


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation', choices=['probe', 'gpt-list', 'gpt-extract', 'super-list', 'super-extract'])
    p.add_argument('image')
    p.add_argument('--sha256', required=True, help='Hash independently recorded for a validated backup/copy')
    p.add_argument('--sector-size', type=int, choices=[512, 4096], default=512)
    p.add_argument('--slot', type=int, default=0)
    p.add_argument('--partition', action='append', help='Repeat to select; default extracts ALL partitions')
    p.add_argument('--out-dir', help='Extraction only: directory must NOT already exist')
    p.add_argument('--report', help='Optional new JSON report; default is stdout')
    a = p.parse_args()
    image = Image(a.image, a.sha256)
    report = {'input': str(image.path), 'sha256': image.sha256, 'physical_size': image.physical_size,
              'logical_size': image.size, 'sparse': image.sparse}
    if a.operation.startswith('gpt'):
        report.update(gpt(image, a.sector_size))
    elif a.operation.startswith('super'):
        report.update(super_metadata(image, a.slot))
    else:
        sig = image.read(0, min(4096, image.size))
        report['signatures'] = {'gpt_512': sig[512:520] == b'EFI PART',
                                'gpt_4096': image.size >= 4104 and image.read(4096, 8) == b'EFI PART',
                                'liblp_geometry': image.size >= 4100 and image.read(4096, 4) == b'gDla',
                                'ext4': len(sig) > 1082 and sig[1080:1082] == b'\x53\xef',
                                'zip_apk': sig[:4] == b'PK\x03\x04'}
    if a.operation.endswith('extract'):
        require(a.out_dir is not None, '--out-dir required')
        report['outputs'] = extract(image, report, a.out_dir, a.partition)
    image.unchanged()
    image.close()
    report['input_stat_unchanged'] = True
    data = json.dumps(report, indent=2, ensure_ascii=False)
    if a.report:
        with Path(a.report).open('x', encoding='utf-8') as f:
            f.write(data + '\n')
    else:
        print(data)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, struct.error) as e:
        print(f'REFUSED: {e}', file=sys.stderr)
        sys.exit(2)
