"""Fixed byte-protocol entry for the credential-free, offline decoder image."""
import struct
import sys


def main():
    sys.path.insert(0, "/work/lib")
    try:
        from app.ai.meal_vision_image import normalize_meal_jpeg
        header = sys.stdin.buffer.read(4)
        if len(header) != 4:
            raise ValueError()
        size = struct.unpack("!I", header)[0]
        if not 1 <= size <= 1_048_576:
            raise ValueError()
        raw = sys.stdin.buffer.read(size)
        if len(raw) != size:
            raise ValueError()
        result = normalize_meal_jpeg(raw)
        sys.stdout.buffer.write(struct.pack("!III", len(result.jpeg), result.width, result.height) + result.jpeg)
        sys.stdout.buffer.flush()
        # Remain identifiable until the parent independently stops the unit.
        if sys.stdin.buffer.read(1):
            raise ValueError()
    except Exception:
        raise SystemExit(70) from None
    raise SystemExit(0)


if __name__ == "__main__":
    main()
