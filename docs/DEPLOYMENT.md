# Docker Compose 部署

Compose 管理 PostgreSQL/PostGIS、一次性 schema 初始化、預測 API 與私有 YouBike 採集器。App 的站點與目前數量直接來自 YouBike，不依賴這個部署。同一個 Go image 提供 API 與採集器，資料直接取自 YouBike 統一來源，不需要登入、API key 或 TDX 憑證。

## 啟動與重建

```sh
cp .env.example .env
# 正式部署前更換 POSTGRES_PASSWORD；使用 URL 安全字元，例如 openssl rand -hex 32。
docker compose up --build -d --wait
docker compose ps
docker compose logs --tail=100 collector
```

目前使用新的資料庫基準結構，**不支援舊 TDX 資料庫升級**。舊部署請先清除本專案資料再啟動：

```sh
docker compose down --volumes
docker compose up --build -d --wait
```

這會刪除本專案的站點歷史及舊資料。舊來源的 App 資料可直接重建，不做舊資料相容處理。資料結構變更時直接重建，不維護歷史資料轉換。初始化只接受目前的 schema checksum；不符時會拒絕啟動。

API 預設 `http://127.0.0.1:18080`，可用 `YOUCOMMUTER_HTTP_PORT` 更換埠。資料庫與採集器埠不公開。手機 release 仍需 HTTPS；目前 Compose 不包含公開 TLS 入口或自動備份服務。

## 設定

| 變數 | 預設 | 用途 |
|---|---|---|
| `POSTGRES_PASSWORD` | 開發用密碼 | 公開部署前更換，使用 URL 安全字元 |
| `YOUCOMMUTER_HTTP_PORT` | `18080` | 主機 loopback 上的 API 埠 |
| `PREDICTION_API_KEY` | 空白 | 空白開放匿名預測；設定後要求 bearer key |
| `YOUBIKE_BASE_URL` | `https://apis.youbike.com.tw/` | YouBike API 主機；測試可指定隔離 fixture |
| `FEED_POLL_INTERVAL` | `1m` | 每輪完成後的等待時間，至少 1 分鐘 |
| `FEED_STALE_AFTER` | `5m` | 過期檢查門檻，至少為採集間隔的 3 倍 |
| `SNAPSHOT_RETENTION_DAYS` | `90` | 90–365 天，保護預測需要的 84 天歷史 |

所有服務預設一起啟動，沒有 ingestion profile 或額外帳號設定。舊 `TDX_*` 變數已移除。

## App 連線

設定頁填寫完整預測網址，例如 `https://host/v1/forecasts:batch`，需要時另填 API key。留空停用預測，仍可查站、建立路線與使用目前數量。開發模擬器可用 `http://10.0.2.2:18080/v1/forecasts:batch`；release 要求 HTTPS。沒有內建公共服務網址，也不需要建立訪客帳號。

對外業務端點只有 `POST /v1/forecasts:batch`。健康檢查另提供 `/health/live`、`/health/ready`。所有服務均遵循 [v1 預測協定](PREDICTION_PROTOCOL.md)。公開入口應在反向代理設定 TLS 與限流；應用層預設按直接連線 IP 限制 20 批／分鐘、burst 10，不自動信任 forwarded IP。

## 採集與快取

採集器首次載入 `area-all.json`，之後每日更新區域清單；若快照出現新的區域，會立即重抓區域清單，再用同一份已下載快照解析。每輪只抓一次 `station-yb2.json`，同時取得所有站點的目錄、一般車、電輔車及可還空位，包含新竹科學園區。

Go HTTP client 自動協商 gzip；解壓後上限 32 MiB。ETag／Last-Modified 只有在整個快照交易成功後才保存，與區域、站點、epoch、即時值和歷史一起提交。任一步驟失敗會整批回滾，不會提交未套用資料的快取標記。HTTP 304 不新增歷史、不修改來源時間，只更新成功檢查時間；本地沒有快照時不送條件式請求。

429 依 `Retry-After` 等待，其他錯誤使用最多 5 分鐘的指數退避與 jitter，重試不早於設定的採集間隔。重試狀態存入 PostgreSQL，程序重啟後接續。採集器持有全域 session advisory lock；失去該連線會停止工作，由 Compose 重啟。執行一個 collector 即可。

`time` 以 Asia/Taipei 解析並保留為上游庫存快照時間，品質標記為 `feed_snapshot`，不表示車端或個人借還事件。沒有 `time` 的資料為 `provider_only`，不進入預測。`updated_at` 和本次接收時間分開儲存；304 或重複來源時間不會製造新觀測。實驗性預測使用新的 `analog-delta-feed-v1`，附 `FEED_SNAPSHOT_TIMESTAMPS`，短缺機率仍保持空值。

## 狀態

```sh
curl --fail http://127.0.0.1:18080/health/ready
docker compose exec -T collector /healthcheck http://127.0.0.1:8081/health/ready
docker compose logs --tail=100 collector
```

採集器回傳最後檢查、最後完整快照、下次執行、錯誤代碼，以及每區域的最新 feed 時間。沒有區域資料、成功檢查過期、來源快照過期或超前超過 1 分鐘，都會使 readiness 回傳 HTTP 503。此為區域檢查，每站仍各自判定新鮮度。

| 狀態 | 處理 |
|---|---|
| `collection_failed` | 檢查上游與網路；會自動退避 |
| `snapshot_rejected` | 快照寫入未完成；保留原資料與 validator，檢查資料庫與來源格式 |
| `rate_limited` | 等待 `nextAttemptAt`，不需重啟 |
| 取得 304 但 `fresh=false` | 上游快照未更新，來源時間仍然過期 |
| collector lock unavailable | 確認只有一個 collector 使用此資料庫 |

來源失效會反映在採集器 readiness 與預測結果，App 的目前數量仍自行向 YouBike 取得。沒有對外 `/v1/coverage` 或站點查詢端點；API 的資料庫 readiness 獨立判定。Compose 的 `unhealthy` 不會觸發自動重啟；程序退出才由 `restart: unless-stopped` 處理。日誌已設定大小輪替。

## 歷史與停止

```sh
docker compose down
```

正常停止保留 named volume。歷史清理每批最多 10,000 筆，只刪除接收時間與上游時間都超過保留期限的資料。一般每小時檢查，批次滿載時每 10 秒繼續。每日壓縮彙整、分區管理、容量負載驗證和備份還原仍需後續完成。

## 驗證

```sh
make check-fmt test-backend integration integration-compose
```

資料庫測試與 Compose smoke test 使用假的 YouBike HTTP 服務，不消耗真實上游流量。Compose 使用獨立 project、暫存 volume 與禁止外網的網路，驗證全站採集、電輔車數、ETag 原子性、304、舊來源、錯誤 body、429、資料庫重啟恢復和 singleton lock。
