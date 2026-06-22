# 🚀 MarketEdge: Real-Time Algorithmic Trading Terminal

MarketEdge is a **high-performance, real-time algorithmic trading dashboard and execution engine**. It ingests live market data, evaluates quantitative trading strategies, persists execution logs, and streams real-time updates to a dark-terminal style UI.

---

## 🧭 Overview

MarketEdge is designed for **low-latency data processing and real-time visualization**, combining:

* Live market data ingestion
* Multi-strategy quantitative evaluation
* Persistent trade signal logging
* Real-time WebSocket streaming
* Reactive terminal-style UI

---

## 🏛 Architecture Overview

MarketEdge follows a **decoupled client-server architecture** optimized for streaming systems:

### 🔄 Data Flow Pipeline

1. **Ingestion Pipeline**

   * Scheduled services fetch OHLCV candle data from external APIs (e.g., Twelve Data)

2. **Strategy Engine**

   * Processes incoming ticks through modular strategy implementations

3. **Persistence Layer**

   * Stores candles and trade signals in a relational database

4. **Distribution Layer**

   * **REST API** → historical + metadata
   * **WebSocket (STOMP)** → live streaming events

5. **Frontend UI**

   * React-based terminal dashboard consuming REST + WebSocket streams

---

## 🛠 Tech Stack

### 🔧 Backend

* **Framework:** Spring Boot 3.2.5 (Java 17)
* **ORM:** Spring Data JPA / Hibernate
* **Build Tool:** Maven
* **WebSocket:** STOMP Broker
* **Utilities:** Lombok

### 🎨 Frontend

* **Framework:** React 18 (Hooks + Context API)
* **Build Tool:** Vite
* **HTTP Client:** Axios
* **WebSocket Client:** StompJS + SockJS
* **Styling:** Pure CSS (Dark Terminal Theme)

---

## 📂 Project Structure

```
marketedge/
├── marketedge-backend/
│   ├── controller/
│   ├── model/
│   ├── repository/
│   ├── strategy/
│   │   └── impl/
│   └── MarketEdgeApplication.java
│
└── marketedge-frontend/
    ├── components/
    ├── hooks/
    ├── services/
    └── App.css
```

---

## 📊 Data Models

### 🕯 Candle

Represents OHLCV market data.

| Field               | Type       | Description              |
| ------------------- | ---------- | ------------------------ |
| id                  | Long       | Primary key              |
| symbol              | String     | Asset (e.g., XAU/USD)    |
| timeframe           | String     | Timeframe (5m, 1h, etc.) |
| timestamp           | Instant    | Candle open time         |
| open/high/low/close | BigDecimal | Price values             |
| volume              | BigDecimal | Trade volume             |

---

### 📈 TradeRecord

Represents strategy-generated signals.

| Field           | Description            |
| --------------- | ---------------------- |
| signalId        | Unique identifier      |
| strategyName    | Strategy used          |
| signalType      | BUY / SELL / NO_TRADE  |
| entryPrice      | Entry point            |
| stopLoss        | Risk control           |
| takeProfit      | Exit target            |
| confidenceScore | 0–100%                 |
| tradeOutcome    | TP_HIT / SL_HIT / etc. |

---

## ⚡ Strategy Engines

### 1. Fusion Flow

* Institutional liquidity-based strategy
* Runs: **Mon–Wed (15min timeframe)**
* Detects liquidity sweeps and structure breaks

---

### 2. Alpha Matrix

* Session breakout strategy
* Runs: **All weekdays, multi-timeframe**
* Uses ATR + Fibonacci + trend validation

---

### 3. SigmaStream

* Gold (XAUUSD) focused strategy
* Runs: **NY Session (08:30–12:00 EST)**
* Detects high-velocity breakout moves

---

## ⚙️ Environment Setup

### 🔑 Step 1: Configure Environment Variables

```bash
# Database
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/marketedge
export SPRING_DATASOURCE_USERNAME=your_db_user
export SPRING_DATASOURCE_PASSWORD=your_db_password

# Market Data API
export TWELVE_DATA_API_KEY=your_api_key
```

---

## ▶️ Running the Application

### 🧠 Backend

```bash
cd marketedge-backend

mvn clean install
mvn spring-boot:run
```

* Runs on: `http://localhost:8080`
* WebSocket endpoint: `/ws`

---

### 🖥 Frontend

```bash
cd marketedge-frontend

npm install
npm run dev
```

* Runs on: `http://localhost:5173`

---

## 🔌 API & Streaming

### REST Endpoints

* Fetch candles
* Fetch 5-day historical signals
* Metadata & analytics

### WebSocket Topics

* Live candles
* Strategy signals
* Heartbeats

---

## 🎯 Key Features

* ⚡ **Real-Time Streaming (WebSocket)**
* 📊 **Multi-Timeframe Charting**
* 🧠 **Pluggable Strategy Engine**
* 🗄 **Persistent Trade Logging**
* 🚀 **Zero-Lag UI Initialization**
* ⏱ **Live UTC Clock Synchronization**
* 📡 **Operational Strategy Timetables**

---

## 🖥 UI Highlights

* Terminal-style dark interface
* Instant data preload (no blank screens)
* Strategy activity indicators
* Live infrastructure telemetry

---

## 📈 Future Enhancements

* 📡 WebSocket-based chart rendering optimization
* 📊 Advanced analytics dashboard
* 🤖 AI-driven strategy modules
* ☁️ Cloud-native deployment (Docker + Kubernetes)
* 🔐 Role-based authentication

---

## 🤝 Contributing

Contributions are welcome! Feel free to:

* Fork the repo
* Create feature branches
* Submit pull requests

---

## 💡 Author Note

MarketEdge is built as a **low-latency trading infrastructure prototype**, focusing on:

* Real-time systems design
* Streaming architecture
* Quantitative strategy modeling

---

**⚠️ Disclaimer:**
This project is for educational and research purposes only. It does not constitute financial advice or a production-ready trading system.
