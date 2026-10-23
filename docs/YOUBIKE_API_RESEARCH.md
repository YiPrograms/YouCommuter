# YouBike 站點與車輛 API 研究

研究日期：**2026-10-19，Asia/Taipei**。YouBike 官方主機提供統一站點資料與官方 App 使用的動態查詢 API。本次以一般 HTTPS 請求、未登入、未攜帶 Cookie、Authorization 或 API key，成功查詢站點、車位及電輔車電量。YouCommuter 可以改以這個統一來源採集，不必再以各縣市 TDX 呼叫作為主要流程。

建議以全站 JSON 累積歷史，指定站點批次 API 支援即時查詢，電輔車清單則在查看單站時按需取得。後端已切換為 YouBike 全站快照來源；下方接法已落實到採集器，批次即時刷新及逐車電量 UI 仍留待需求再加入。

## 已驗證的端點

主機均為 `https://apis.youbike.com.tw`。下表全部端點在本次匿名請求中回傳 HTTP 200；動態 API 同時回傳 `retCode: 1`。

| 方法 | 路徑 | 用途與參數 |
|---|---|---|
| GET | `/json/station-yb2.json` | 全站目錄、座標、營運狀態、一般車／電輔車數、空位、容量與時間欄位 |
| GET | `/json/station-min-yb2.json` | 較小的站點目錄；本次回應沒有車輛或空位數，不能當即時庫存 |
| GET | `/json/area-all.json` | 服務區域代碼與名稱，包含新竹科學工業園區 |
| GET | `/tw2/stationList` | 名稱與位置搜尋；`lang`、`lng`、`lat`、`station_name`、`page` |
| GET | `/tw2/stationInfo` | `lang`、`station_no`；本次回應僅有站名與地址 |
| POST | `/tw2/parkingInfo` | 以 `lng`、`lat`、`maxDistance` 查周邊站點的車輛與空位 |
| POST | `/tw2/parkingInfo` | 以重複的 `station_no[]` 查指定站點；使用 `?page=2` 等參數分頁 |
| GET | `/api/front/bike/lists` | `station_no`；App 用於電輔車電量表，回應包含車號、柱號與電量 |

官方網站的[站點地圖](https://www.youbike.com.tw/region/main/stations/)目前引用 `station-min-yb2.json` 與 `area-all.json`；全站即時 JSON 的可用性由直接 HTTPS 請求驗證。App 的動態端點則由 APK 方法與實際回應交叉確認，不把這些來源混為同一條 App 呼叫流程。

## 全站快照與覆蓋

2026-10-19 23:04:29 的[全站 JSON](https://apis.youbike.com.tw/json/station-yb2.json)包含 **9,701 個不重複站點、14 個服務區**，全部為 `type: 2`；狀態分布為 `1` 共 9,532 站、`2` 共 169 站。這是當時快照數量，不應寫死在程式中。

| 區域代碼 | 區域 | 站點數 |
|---|---|---:|
| `00` | 台北市 | 1,821 |
| `01` | 台中市 | 1,842 |
| `05` | 新北市 | 1,614 |
| `07` | 桃園市 | 710 |
| `08` | 嘉義市 | 229 |
| `09` | 新竹市 | 175 |
| `0A` | 苗栗縣 | 218 |
| `0B` | 新竹縣 | 224 |
| `10` | 新竹科學工業園區 | 42 |
| `11` | 嘉義縣 | 195 |
| `12` | 高雄市 | 1,519 |
| `13` | 臺南市 | 793 |
| `14` | 屏東縣 | 199 |
| `15` | 臺東縣 | 120 |

第二次抓取仍有 9,701 站，其中 1,048 站的車輛或空位數改變，全部站點的 `time` 欄位改變。兩次回應的 `Last-Modified` 分別為 23:03:38 與 23:06:38；這證明快照會更新，尚不足以承諾固定更新延遲。

第二次快照解壓後為 6,328,412 bytes，gzip 傳輸為 929,034 bytes。回應提供 `ETag` 和 `Last-Modified`；攜帶相同 `If-None-Match` 的後續請求實測回傳 **304、空 body**。若假設每分鐘完整傳輸一次、每次大小相同，gzip 約 1.34 GB／日，未壓縮約 9.11 GB／日。這是頻率與快照大小的估算，不是服務方的流量承諾。

既有 `youcommuter-backend:local` scratch Go image 也成功讀取此端點的 JSON 前 16 KiB，確認目前主機的 Docker 環境可以連線。完整筆數與結構驗證使用上述主機端下載，不代表所有未來部署 IP 都有相同存取結果。

## 批次查詢與電輔車

指定兩站時，`parkingInfo` 的 `retVal.data` 回傳兩筆。指定 50 個有效站號時，第一頁僅回傳 20 筆，標示 `per_page: 20`、`total_page: 3`、`total: 50`。以 `?page=2`、`?page=3` 查詢後分別取得 20、10 筆，聯集為完整 50 站。

因此不能看到 HTTP 200 就認定整批完成。即時查詢可先採每批不超過 20 站，仍檢查分頁與實際回傳 ID；缺資料維持未知，不補成零。App 靜態程式會從畫面／收藏中取最多 50 個 ID，這個客戶端數量不代表伺服器會在單頁回傳 50 筆。座標查詢本次以 `maxDistance=500` 取得 17 站，不能用此參數推定可無限制查詢全國。

[電輔車查詢](https://apis.youbike.com.tw/api/front/bike/lists?station_no=500101001)實測回傳一筆車輛，含 `station_no`、`bike_no`、`pillar_no`、`group_no`、`battery_power`、`modify_time`，當時電量為 74。APK 方法命名為 `getEBikeBattery`，模型讀取 `bike_no` 與 `battery_power`。這可支援單站電量與停放柱號；目前確認的範圍不是所有一般車的逐車清單，車號也不用來判斷哪位使用者借還車。

## 欄位對應

| 上游欄位 | YouCommuter 用途 | 處理方式 |
|---|---|---|
| `station_no` | 原生站號 | 保持字串；新來源使用 `youbike:<station_no>` 識別 |
| `country_code`、`area_code` | 服務區域 | 與區域清單連結；`0A` 不是整數 |
| `name_tw`、`address_tw` | 站名與地址 | 保留原文 |
| `lat`、`lng` | 站點位置 | 此來源為數字字串，需解析及範圍檢查 |
| `parking_spaces` | 容量 | 獨立欄位，不用容量推導車位數 |
| `available_spaces` | 可借總車數 | 零是實際零；缺值維持未知 |
| `available_spaces_detail.yb2` | 一般車數 | 獨立保留 |
| `available_spaces_detail.eyb` | 電輔車數 | 獨立保留；不能因 feed 的 `type: 2` 就忽略電輔車 |
| `empty_spaces` | 可還空位 | 直接使用，不計算容量減車數 |
| `forbidden_spaces` | 禁用車位欄位 | 保留來源資訊，不假定能補足所有容量差額 |
| `status` | 服務狀態 | 未知值不能默認正常 |
| `available_spaces_level` | 來源顯示等級 | 不能當缺車機率或預測結果 |
| `time`、`updated_at` | 上游時間資訊 | 無時區字尾；明確以 Asia/Taipei 解析，保留時間品質 |

第一個快照的車種明細相加都等於 `available_spaces`，但 **2,832 站的容量不等於車數＋空位＋禁用車位**。這支持既有規則：空位取 `empty_spaces`，不能用其他數字相減。

## 新鮮度與預測

`parkingInfo` 的座標與站號批次回應都沒有 `time` 或 `updated_at`。它能提供新取得的數值，卻不能證明上游何時觀測。接收時間只能記為 `fetched_at`，不能冒充來源時間，尤其不能拿來讓舊資料重複進入預測訓練。

APK 的 `MapViewModel.getStationsParkingInfo` 更新本地站點時，會把目前本機時間格式化後寫入顯示用的 `updated_at`；`StationRepository.getStationUpdatedTime` 再補上 `+08:00` 解析。因此官方 App 畫面上的更新時間，不能證明 `parkingInfo` 回應附有權威來源時間。

全站 JSON 有供應者提供的 `time`、`updated_at`，較適合建立可追蹤的歷史快照，但本次尚未釐清它們與每站控制器原始觀測時間的對應。切換 provider 時不能直接套上 TDX `SrcUpdateTime` 的語意，也不能把 HTTP 304 當新的觀測時間。重複快照須去重，目前以 `feed_snapshot` 標記供應者庫存快照時間，使用獨立的實驗性模型版本及 `FEED_SNAPSHOT_TIMESTAMPS` 原因碼；正式機率仍須時間與回測驗證。

## YouCommuter 接法

建議把採集單位改成**一個 YouBike 全站快照**：一次下載、解壓並驗證完整 JSON，再產生區域、站點與觀測資料，避免每個區域都下載同一份 feed。原本分開的 `FetchStations`／`FetchAvailability` 不宜直接套用，否則容易重複請求或混合兩個時間點的快照。

1. 區域從 `area-all.json` 載入，包含科學園區；站點依實際 `area_code` 分組。
2. 初始每分鐘抓一次全站 feed，支援 gzip、ETag／304、大小限制、timeout、429 與退避。這是 YouCommuter 的頻率設定，依實際部署調整。
3. 原子保存完整快照、來源時間與條件式請求狀態。若拿到 304 卻沒有可用的本地快照，清除 validator 後重抓；解析失敗不可提交新的 validator。
4. 搜尋、附近站點、提醒與預測仍由 YouCommuter API 查自己的資料庫，避免每支手機下載全站資料。
5. 若需即時刷新，由後端合併指定站點的 `parkingInfo` 請求、限制頻率與短暫快取；無來源時間的值不得覆寫帶有可信時間的歷史快照。
6. 電量在查看單站時按需查詢，不逐分鐘掃遍全國每台車。

YouBike provider 不需要 TDX client credentials；實作切換後可移除 TDX 專用設定與區域排程，沿用 Compose 的單一 collector。無需為舊 TDX 資料保留相容層。採集可從多個區域的分頁縮成通常每輪一個全站請求；仍須限流與失敗降級，匿名可用不代表無限額或穩定 SLA。

## 查詢範例

```sh
curl --compressed --fail 'https://apis.youbike.com.tw/json/station-yb2.json'

curl --fail 'https://apis.youbike.com.tw/tw2/stationList?lang=tw&lng=121.5436&lat=25.02605&station_name=%E7%A7%91%E6%8A%80%E5%A4%A7%E6%A8%93&page=1'

curl --fail 'https://apis.youbike.com.tw/tw2/parkingInfo?page=1' \
  --data-urlencode 'station_no[]=500101001' \
  --data-urlencode 'station_no[]=500101274'

curl --fail 'https://apis.youbike.com.tw/tw2/parkingInfo' \
  --data-urlencode 'lng=121.5436' \
  --data-urlencode 'lat=25.02605' \
  --data-urlencode 'maxDistance=500'

curl --fail 'https://apis.youbike.com.tw/api/front/bike/lists?station_no=500101001'
```

`curl --fail` 只檢查 HTTP；動態 API 還須檢查 `retCode`、`retVal` 與分頁。本次 POST 使用 `application/x-www-form-urlencoded`，沒有調用登入、會員資料、借車、還車、付款或裝置控制端點。

## 證據定位

APK 為先前下載的 `tw.com.youbike.plus` **3.14.0 (1413)**；來源、SHA-256 與簽章核對範圍見[既有 APK 研究](RIDE_DETECTION_RESEARCH.md#apk-provenance-and-limits)。結論限於此版本與本次匿名查詢。

| APK 方法 | 位址 | 對應事實 |
|---|---|---|
| `_$StationService._$getStationList` | `0x6a1a44` | GET 搜尋及分頁參數 |
| `_$StationService._$getStationInfo` | `0x4e88c8` | GET 站號與語言參數 |
| `_$StationService._$getParkingInfo` | `0x4f77bc` | POST 座標與距離參數 |
| `_$StationService._$getFavoriteParkingInfo` | `0x4f6790` | 同一 POST 路徑，欄位為 `station_no[]` |
| `_$StationService._$getEBikeBattery` | `0x7d7714` | 電量端點與站號參數 |
| `Station.fromJson` | `0x4a3110` | 車輛、空位、明細與 `updated_at` |
| `EBikeBattery.fromJson` | `0x4e10cc` | 車號與電量 |
| `MapViewModel.getStationsParkingInfo` | `0x4f52c4` | 取最多 50 個 ID，以及寫入本機更新時間的流程 |
| `StationRepository.getStationUpdatedTime` | `0x7d6644` | 補上 `+08:00` 後解析時間 |

回應狀態、快照 hash、覆蓋數量、分頁及壓縮驗證保存在[機器可讀摘要](YOUBIKE_API_RESEARCH.json)。原始回應與反組譯輸出留在摘要列出的暫存目錄，不納入執行依賴，也不將 APK 程式碼複製進專案。
