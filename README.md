# NEXUS: Universal Application Interoperability Framework

NEXUS is a modern Android application built with Kotlin and Jetpack Compose that serves as a **Universal Application Interoperability Control Center and Communication Bridge**.

It bridges communication across diverse protocols, systems, and devices, including REST / HTTP, gRPC (Protobuf), WebSockets, GraphQL, Android Inter-Process Intents, and Event Streams.

---

## 🌟 Key Features

1. **Mesh Telemetry & Overview Dashboard**
   - Real-time telemetry monitoring: Active node health, throughput rate (msgs/sec), mean network latency (ms), and error rate metrics.
   - Quick action shortcuts to trigger signals, execute pipelines, inspect traffic, and register new nodes.
   - Live stream preview of inter-process packets.

2. **Connected Node & Service Registry**
   - Catalog of integrated services: PayBridge Gateway (gRPC), Identity Exchange (OAuth2 / REST), IoT Telemetry Stream (WebSocket), Android Intent Dispatcher (IPC), Catalog GraphQL Mesh, and Universal Event Streams.
   - Search & protocol filtering (REST, gRPC, GraphQL, WebSocket, Android Intent, EventStream).
   - Real-time ping testing and dynamic status toggling (Online / Offline).
   - Dynamic Node Registration dialog to add custom microservice or intent endpoints.

3. **Interop Orchestration Pipelines**
   - Visual step-by-step interoperability sequence pipelines (e.g. *Omni-Commerce Order Sync*, *Telemetry Normalization & Anomaly Alert*, *Cross-App Identity Token Exchange*).
   - Automated protocol translation across pipeline stages (e.g. Android Intent -> JSON to Proto3 -> gRPC Authorization -> EventBus Publish).
   - Interactive pipeline execution engine with animated progress indicator and live stage-by-stage console logs.

4. **Universal Test Console & Dispatcher (Sandbox)**
   - Interactive dispatcher to send arbitrary test signals to any registered node or custom endpoint.
   - Preset payload templates for quick testing (Payment JSON, Telemetry WS, GraphQL Query).
   - Multiline monospace payload editor and RPC method configuration.
   - Real-time response inspector showing status codes, routing latency, transformed output payloads, and headers.

5. **Traffic & Packet Inspector**
   - Continuous real-time traffic feed with pause/resume and buffer clear controls.
   - Filter by search keyword (Trace ID, Node name, Method) and quick "Errors Only" switch.
   - Deep trace inspection modal detailing network hops, latency benchmarks, raw request payload, and transformed response payload.

6. **Universal Protocol Codec & Schema Transformer**
   - Live bidirectional schema and data transformer supporting JSON, YAML, Protobuf (proto3), GraphQL Schemas, and URL Form-Encoding.
   - Instant conversion preview and copy-to-clipboard functionality.

---

## 🛠 Tech Stack & Architecture

- **Runtime & Language**: Android SDK 36, Kotlin 2.2+, Jetpack Compose
- **Design System**: Material Design 3 (M3) with cyber-mesh high-tech dark/light color schemes
- **Architecture**: MVVM with reactive Kotlin StateFlow and Coroutines
- **Icons & Assets**: Custom adaptive launcher icon and Material Symbols
- **Build System**: Gradle 9.3+ with Kotlin DSL & Version Catalog (`gradle/libs.versions.toml`)
