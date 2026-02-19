Bundled ffmpeg policy (macOS arm64):

Use a standalone ffmpeg binary that does not depend on Homebrew cellar dylibs.

Do NOT copy:
- /opt/homebrew/Cellar/ffmpeg/.../bin/ffmpeg

Reason:
- That binary usually links to /opt/homebrew/Cellar/.../lib/*.dylib
- AirBridge extracts bundled ffmpeg to a temp dir, where those dylibs are not available.

Replace procedure:
1) Put standalone binary at:
   src/main/resources/ffmpeg/mac-arm64/ffmpeg
2) Make executable:
   chmod +x src/main/resources/ffmpeg/mac-arm64/ffmpeg
3) Verify dependencies:
   otool -L src/main/resources/ffmpeg/mac-arm64/ffmpeg
   Expected: no /opt/homebrew/Cellar/... entries
4) Verify execution:
   src/main/resources/ffmpeg/mac-arm64/ffmpeg -version
5) Build:
   gradle build

Recommended acceptance check:
- Run encode once and check encode_report.json `ffmpegBinary`.
- If bundled works, path is extracted temp binary path (not plain "ffmpeg").
- If not runnable, AirBridge falls back to system ffmpeg and prints a WARN.
