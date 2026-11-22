#!/usr/bin/env python3
"""Offline, read-only candidate frame inspection of exported LD36 RX stream.

IMPORTANT: USB reads can be fragmented/coalesced. A match is only a hypothesis.
No data is sent to any dongle; the script reads a local .bin file.
"""
import argparse
from pathlib import Path

TAGS = {b'CNYS': 'SYNC', b'RRIM': 'MIRR', b'_PPA': 'APP_', b'EVIL': 'LIVE'}


def scan(data: bytes, max_events: int = 100):
    pos = 0
    events = 0
    unknown = 0
    while pos + 4 <= len(data) and events < max_events:
        header = data[pos:pos + 4]
        if header == b'\0\0\0\0':
            if unknown:
                print(f'  ... {unknown} bytes sem enquadramento antes de 0x{pos:X}')
                unknown = 0
            print(f'0x{pos:08X}: heartbeat candidato (4 bytes)')
            pos += 4
            events += 1
            continue
        length = int.from_bytes(header, 'little')
        if (12 <= length <= 2 * 1024 * 1024
                and pos + length <= len(data)
                and data[pos + 4:pos + 8] in TAGS):
            if unknown:
                print(f'  ... {unknown} bytes sem enquadramento antes de 0x{pos:X}')
                unknown = 0
            tag = data[pos + 4:pos + 8]
            magic = data[pos + 8:pos + 12].hex(' ').upper()
            print(f'0x{pos:08X}: {TAGS[tag]:4s} len={length:6d} '
                  f'tag_raw={tag.decode()} magic={magic}')
            pos += length
            events += 1
            continue
        pos += 1
        unknown += 1
    if unknown:
        print(f'  ... {unknown} bytes sem enquadramento')
    if pos < len(data):
        print(f'  ... restam {len(data) - pos} bytes não analisados')
    if events == 0:
        print('Nenhum candidato reconhecido: examine o começo do stream em HEX.')
        print('Primeiros 256 bytes:', data[:256].hex(' ').upper())


def main():
    parser = argparse.ArgumentParser(description='Inspecionar stream binário RX do NURA LD36')
    parser.add_argument('capture', type=Path)
    parser.add_argument('--max-events', type=int, default=100)
    args = parser.parse_args()
    data = args.capture.read_bytes()
    print(f'Arquivo: {args.capture.name} ({len(data)} bytes)')
    scan(data, args.max_events)


if __name__ == '__main__':
    main()
