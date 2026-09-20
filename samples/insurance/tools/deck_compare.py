"""Build proof only: compare 80-byte decks, columns 1..72, excluding END."""

import argparse
from pathlib import Path


def normalized(raw: bytes) -> list[bytes]:
    if not raw or len(raw) % 80:
        raise ValueError("empty or truncated FB80 object deck")
    cards = [raw[i:i + 80] for i in range(0, len(raw), 80)]
    end = bytes.fromhex("02c5d5c4")
    if sum(card.startswith(end) for card in cards) != 1 or not cards[-1].startswith(end):
        raise ValueError("expected exactly one final END card")
    allowed = {bytes.fromhex(s) for s in ("02c5e2c4", "02e3e7e3", "02d9d3c4")}
    for card in cards[:-1]:
        if card[:4] not in allowed:
            raise ValueError(f"unexpected object card {card[:4].hex()}")
    return [card[:72] for card in cards[:-1]]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", type=Path)
    parser.add_argument("guest", type=Path)
    args = parser.parse_args()
    left, right = normalized(args.host.read_bytes()), normalized(args.guest.read_bytes())
    if len(left) != len(right):
        raise SystemExit(f"FAIL: normalized card counts {len(left)} != {len(right)}")
    for index, (host, guest) in enumerate(zip(left, right), 1):
        if host != guest:
            offset = next(i for i, pair in enumerate(zip(host, guest)) if pair[0] != pair[1])
            raise SystemExit(f"FAIL: card {index}, column {offset + 1}: "
                             f"host={host[offset]:02x}, guest={guest[offset]:02x}")
    print(f"PASS: {len(left)} cards equal in columns 1..72 excluding END; build proof only")


if __name__ == "__main__":
    main()
