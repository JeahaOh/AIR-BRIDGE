# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/com/airbridge/` holds the Java 21 source. Key areas: CLI commands in `cli/`, pipelines in `pipeline/`, frame formats in `frame/`, and ffmpeg integration in `ffmpeg/`.
- `src/main/resources/ffmpeg/` contains bundled ffmpeg binaries for `mac-arm64` and `win-x64`.
- Build outputs go to `build/` (fat JAR in `build/libs/`).

## Build, Test, and Development Commands
- `gradle build` builds a fat JAR via Shadow; output like `build/libs/airbridge-0.5.0.jar`.
- `java -jar build/libs/airbridge-*.jar --help` runs the CLI.
- `java -jar build/libs/airbridge-*.jar encode|play|decode ...` runs the main subcommands.

## Coding Style & Naming Conventions
- Java indentation uses 4 spaces; follow existing formatting in `src/main/java/`.
- Package naming follows `com.airbridge.*` and class names use UpperCamelCase.
- Keep CLI option names kebab-case (e.g., `--chunk-size`, `--work`).
- No formatter or linter is configured; keep changes consistent with existing style.

## Testing Guidelines
- There is no automated test suite in this repository yet.
- If you add tests, place them under `src/test/java/` and name classes `*Test` (e.g., `EncodePipelineTest`).
- Prefer fast, deterministic tests and document new test commands here.

## Commit & Pull Request Guidelines
- Existing history uses a Conventional Commits style prefix (example: `feat: air-bridge 초기 구현`). Keep using `feat:`, `fix:`, or `chore:` as appropriate.
- PRs should include a clear summary, the commands used to validate the change (if any), and sample CLI output or screenshots when modifying user-facing behavior.

## Security & Configuration Tips
- ffmpeg lookup prefers bundled binaries and falls back to system `ffmpeg`. Validate changes on at least one platform when touching `src/main/resources/ffmpeg/`.
- Generated artifacts (e.g., `transfer.mp4`, `work/`, `decoded-output/`) are expected and should remain in `.gitignore`.

## Agent-Specific Instructions
- Detailed implementation notes and pipeline behavior are captured in `AGENT.md`. Review it before changing core encode/decode/play flows.
