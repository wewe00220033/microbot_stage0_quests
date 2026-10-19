# Microbot F2P Progression & Utility Suite

A modular, event-driven suite of plugins for RuneLite with Microbot integration, designed for autonomous task automation, quest progression, and early-game skill milestone management.

---

## 📦 Architecture Overview

The suite is structured strictly under the standard RuneLite client plugin package: `net.runelite.client.plugins.microbot`

Each module follows a decoupled 4-component architectural pattern:
1. **Plugin (`*Plugin.java`)**: Manages the plugin lifecycle (`startUp`, `shutDown`), registers overlay renderers, and initiates the background worker thread.
2. **Script (`*Script.java`)**: Encapsulates the execution logic, finite-state machine (FSM), corridor checks, and API interactions off the client thread.
3. **Config (`*Config.java`)**: Configurable properties, thresholds, toggles, and user preferences exposed through the RuneLite ConfigManager.
4. **Overlay (`*Overlay.java`)**: Lightweight visual feedback rendered on the game canvas displaying real-time state, elapsed time, and metrics.

---

## 🧩 Included Modules

### 1. `stronghold` (Security Stronghold Assistant)
- **Package**: `net.runelite.client.plugins.microbot.stronghold`
- **Objective**: Autonomous traversal of all 4 floors of the Stronghold of Security.
- **Key Features**:
  - Deterministic door question answering based on upstream question tables.
  - Non-blocking pathfinding with corridor safety bounds.
  - Safe reward verification and exit ladder traversal.

### 2. `tradeunlock` (F2P Quest Progression)
- **Package**: `net.runelite.client.plugins.microbot.tradeunlock`
- **Objective**: Automated completion of the core F2P quest quartet (12 Quest Points total):
  - *Cook's Assistant* (1 QP)
  - *Romeo & Juliet* (5 QP)
  - *Sheep Shearer* (1 QP)
  - *Goblin Diplomacy* (5 QP)
- **Key Features**:
  - Pre-sourcing item verification (zero mid-quest resource gathering).
  - Varp and Varbit milestone tracking (`varp 29`, `varp 144`, `varp 179`, `varbit 2378`).
  - Dialogue state machines with dynamic widget option resolving.

### 3. `totallevel` (Skill Milestone Progression)
- **Package**: `net.runelite.client.plugins.microbot.totallevel`
- **Objective**: Structured, low-risk skill progression targeting Total Level 100.
- **Key Features**:
  - Balanced multi-skill rotation (Woodcutting, Firemaking, Fishing, Cooking).
  - Game-tick total-level latch to preempt background actions immediately upon reaching target.
  - Localized area safety bounds (Lumbridge Castle & riverbanks) avoiding hostile NPC corridors.

---

## 🛠️ Build & Installation

### Prerequisites
- Java Development Kit (JDK) 11 or higher
- RuneLite client core with Microbot API binaries

### Compilation
Ensure your project references the appropriate RuneLite client and Microbot API dependencies:
```bash
# Clone and build with Gradle / Maven
mvn clean install
# or
gradle build
```

---

## 📜 License & Compliance
This software is provided for educational and research purposes in user interface automation and event-driven robotics.
