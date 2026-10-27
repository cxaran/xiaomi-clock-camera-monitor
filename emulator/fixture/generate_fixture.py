"""Generate public synthetic media using an already installed host FFmpeg.

No network, camera input, Android, or package installation. Run manually.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def generate(ffmpeg: Path, output: Path) -> dict:
    if not ffmpeg.is_file():
        raise ValueError("existing_ffmpeg_required")
    output.mkdir(parents=True, exist_ok=True)
    names = ("testpattern_640x360_15.h264", "tone_440hz_8000.alaw", "manifest.json")
    if any((output / name).exists() for name in names):
        raise ValueError("fixture_destination_already_exists")
    common = [str(ffmpeg), "-hide_banner", "-nostdin", "-loglevel", "error", "-n"]
    video = ["-f", "lavfi", "-i", "testsrc2=size=640x360:rate=15:duration=4",
             "-an", "-c:v", "libx264", "-profile:v", "baseline", "-level:v", "3.0",
             "-pix_fmt", "yuv420p", "-preset", "veryfast", "-tune", "zerolatency",
             "-threads", "1", "-b:v", "350k", "-maxrate", "350k", "-bufsize", "350k",
             "-x264-params", "keyint=15:min-keyint=15:scenecut=0:repeat-headers=1:aud=1:bframes=0",
             "-f", "h264", str(output / names[0])]
    audio = ["-f", "lavfi", "-i", "sine=frequency=440:sample_rate=8000:duration=4",
             "-af", "volume=0.25", "-ac", "1", "-ar", "8000", "-c:a", "pcm_alaw",
             "-f", "alaw", str(output / names[1])]
    version = subprocess.run([str(ffmpeg), "-version"], check=True, capture_output=True,
                             text=True, timeout=10).stdout.splitlines()[0]
    commands = []
    for arguments in (video, audio):
        command = common + arguments
        completed = subprocess.run(command, capture_output=True, timeout=60)
        if completed.returncode != 0:
            raise RuntimeError("synthetic_ffmpeg_generation_failed")
        # The shareable manifest records the recipe, not local user/host paths.
        commands.append(["ffmpeg", *command[1:-1], Path(command[-1]).name])
    report = {"synthetic_only": True, "camera_or_microphone_input": False,
              "ffmpeg": {"sha256": sha(ffmpeg), "version": version},
              "video": {"width": 640, "height": 360, "fps": 15, "seconds": 4,
                        "codec": "H264 constrained baseline", "format": "AnnexB with AUD and repeated SPS/PPS"},
              "audio": {"codec": "PCMA G711A", "sample_rate": 8000, "channels": 1,
                        "seconds": 4, "source": "440Hz sine; FFmpeg default amplitude times0.25"},
              "commands": commands,
              "files": {name: {"bytes": (output / name).stat().st_size,
                               "sha256": sha(output / name)} for name in names[:2]}}
    (output / "manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", type=Path, default=shutil.which("ffmpeg"))
    parser.add_argument("--output", type=Path, default=HERE / "media")
    args = parser.parse_args()
    if args.ffmpeg is None:
        parser.error("FFmpeg must already exist; this tool never installs it")
    result = generate(Path(args.ffmpeg).resolve(), args.output.resolve())
    print(json.dumps({"synthetic_fixture_generated": True, "files": result["files"]}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
