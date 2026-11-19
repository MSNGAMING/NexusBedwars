# 🛡️ NexusBedwars

> **Enterprise-grade, high-performance native Bedwars plugin for Paper, Purpur, and Folia (Minecraft 1.21.x – 26.x).**

[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://www.oracle.com/java/)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%7C%20Purpur%20%7C%20Folia-blue.svg)](https://papermc.io/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x--26.x-green.svg)](https://www.minecraft.net/)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

---

## ✨ Features

- **⚡ Native Folia Regionized Multi-Threading:** Engineered from the ground up for multi-threaded chunk execution. Uses dedicated Global, Region, Entity, and Async schedulers with zero cross-region thread blocking.
- **🔄 O(Changed Blocks) Change-Journal Rollback:** Only tracks blocks modified during gameplay instead of heavy world cloning or full-chunk restorations. Slices restorations per chunk to guarantee steady TPS with zero lag spikes.
- **🛡️ Registry-Backed Version Independence (1.21.x – 26.x):** Zero NMS or Paperweight requirements. All attributes, particles, enchantments, and effects are resolved through stable Minecraft resource keys.
- **💾 Multi-Database Architecture:**
  - **SQLite (WAL):** Zero-configuration local database for standalone servers.
  - **MySQL & PostgreSQL:** High-throughput pooled databases via HikariCP for proxy networks.
  - **Redis Pub/Sub Sync:** Instant cross-server statistic synchronization without database hammering.
- **⚔️ Authentic Game Mechanics:**
  - Fireball & TNT jumping with configurable knockback vectors and blast falloff.
  - Wide-platform **Bridge Eggs** with real-time trajectory paving.
  - Automatic cleanup and destruction of abandoned beds in partial lobbies.
  - Unbreakable gear, armor upgrade retention, and offhand currency counting.
  - Combat-logging prevention with instant forfeit and Elo penalty.
- **🛠️ Triple-Layer Admin System:**
  1. **In-game GUI Setup Wizard:** Step-by-step arena setup wand, corner selector, interactive checklist, and live validation.
  2. **Brigadier Command Engine:** Full tab-completion for arena modes, settings, and stats (`/bw`).
  3. **Hot-Reloaded YAML Configuration:** Live file-watcher monitoring with comment preservation.

---

## 🚀 Getting Started

### Requirements
- **Server:** Paper, Purpur, or Folia for Minecraft 1.21.x – 26.x
- **Java:** Java 21 LTS or newer
- **Optional Dependencies:** PlaceholderAPI, Vault (for economy rewards)

### Installation
1. Download the latest `NexusBedwars-1.0.0.jar` from the [Releases](https://github.com/) section.
2. Place the jar into your server's `plugins/` directory.
3. Restart the server. NexusBedwars will resolve runtime dependencies automatically via Paper's Library Loader.
4. Run `/bw admin` in-game to access the management dashboard or start configuring arenas.

---

## 📜 Commands & Permissions

| Command | Permission | Description |
|---|---|---|
| `/bw join [arena]` | `nexusbw.play` | Join an arena or the best available game |
| `/bw leave` | `nexusbw.play` | Leave the current match |
| `/bw stats [player]` | `nexusbw.play` | View Bedwars player stats and Elo rating |
| `/bw top <stat>` | `nexusbw.play` | Display top 10 leaderboard rankings |
| `/bw admin` | `nexusbw.admin` | Open the interactive GUI management dashboard |
| `/bw create <id> <solo\|doubles\|triples\|quads>` | `nexusbw.admin` | Create a new arena template |
| `/bw setup <arena>` | `nexusbw.admin` | Open the interactive setup wizard |
| `/bw enable <arena>` | `nexusbw.admin` | Validate and enable an arena |
| `/bw disable <arena>` | `nexusbw.admin` | Disable an arena |
| `/bw reload` | `nexusbw.admin` | Reload configuration from disk |

---

## 🏗️ Building from Source

To compile the plugin from source:

```bash
cd plugin
./gradlew build
```

The compiled jar will be located at:
`plugin/build/libs/NexusBedwars-1.0.0.jar`

---

## 📄 License
This project is licensed under the MIT License.
