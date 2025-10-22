# ChunkCleaners

A well sought after feature for Factions and Survival servers for 1.21+, chunk cleaners allows players to clear chunks with ease.
This plugin is exceptionally optimized to run async with TPS-aware throttling, and CoreProtect-friendly batch logging.

## Key features

- Placeable chunk-cleaner items (configurable types: small, medium, large).
- Top-down cleaning (surface first) to avoid underground holes while working.
- Multi-chunk support (1x1, 2x2, 4x4, etc.) with size-aware throughput scaling.
- TPS-adaptive throttling — reduces workload automatically when server TPS drops.
- Holograms and Actionbars for added user friendliness!
- Integrations:
  - WorldGuard (region build flag checks)
  - GriefPrevention (claim checks)
  - Factions (FactionsUUID and MassiveCore)
  - CoreProtect (batch-per-chunk summaries, throttled enqueue + periodic flush)
- Persistence with autosave and per-task progress saving (reduces lost progress after a restart).
- Admin tooling to save state and inspect running tasks.

## Commands

Primary command: `/chunkcleaners` (alias `/cc`)

Player commands
- `/chunkcleaners give <player> <type> [amount]` — give a cleaner item to a player (permission: `chunkcleaners.give`).
- `/chunkcleaners list` — show active cleaner count or a brief list.
- `/chunkcleaners reload` — reload plugin config (admin permission required).

Admin subcommands (permission: `chunkcleaners.admin`)
- `/chunkcleaners admin save` — force-save all active tasks to disk.
- `/chunkcleaners admin savetask <uuid>` — save progress for a single task immediately.
- `/chunkcleaners admin list` — list all active tasks with percent progress.
- `/chunkcleaners admin status <uuid>` — show detailed status (ETA, chunk pointer, progress).

## Holograms and Visuals

Configurable under `hologram.*`:
- `hologram.lines` — a list of lines for multi-line holograms (supports placeholders).
- `hologram.text` — fallback single-line text with `\n` support.
- `hologram.offset` — vertical offset above the placed block.
Placeholders available: `{remaining}`, `{progress}`, `{coords}`, `{type}`, `{amount}`, `{player}`.

## Performance tuning & options

All tuning options live under `performance.*` in `config.yml`. 

Important knobs
- `performance.max_chunks_per_interval` — baseline number of chunks prepared per scheduler run.
- `performance.y_batch_size` — baseline vertical layers processed per chunk job.
- `performance.ticks_per_chunk_interval` — scheduling interval (ticks).
- `performance.size_scale_enabled` — whether larger cleaners automatically do more work.
- `performance.size_scale_multiplier` — multiplier used with cleaner size to compute scale factor.
- `performance.size_scale_cap` — upper cap for scale factor (safe default = 8).
- `performance.aggressive_interval_divisor` — (advanced) allow smaller schedule interval for large cleaners.
- `performance.tps_threshold` & `performance.tps_smoothing` — used to detect low TPS and smoothly reduce workload.
- `performance.eta_window_seconds` — window for the moving-average ETA estimator.

CoreProtect safety
- `coreprotect.log_chunk_summary` (recommended) — log one summary entry per finished chunk instead of each block.
- `coreprotect.flush_interval_ticks` & `coreprotect.max_entries_per_flush` — control the CoreProtect logging throughput.
- `coreprotect.queue_max_size` — prevents memory blowup by bounding queued entries.

Persistence
- `persistence-file` — path to saved active tasks (default `data/active-cleaners.yml`).
- `persistence.autosave_enabled` & `persistence.autosave_interval_seconds` — periodic autosave settings.
- The plugin also saves per-task progress when a chunk completes.

Recommended Settings
1. Start with defaults in `config.yml` 
2. Test on a new world with a few different types of cleaner sizes.
3. Monitor server TPS and tick times. If TPS remains stable (≥ ~19), gradually:
   - increase `size_scale_multiplier` or `size_scale_cap`, or
   - increase `max_chunks_per_interval` and `y_batch_size`.
4. Keep CoreProtect batch logging enabled and monitor CoreProtect performance/IO.
5. Use `persistence.autosave_interval_seconds` (default 60s) to balance resilience vs disk writes.

## Integrations

- If protection integrations are enabled, the plugin will refuse to edit a chunk on behalf of an offline player.
- WorldGuard/GriefPrevention/Factions checks are performed via reflection.

## Support

- Need support with the plugin or would like to request new features? Contact me on Discord @d15co7d.
