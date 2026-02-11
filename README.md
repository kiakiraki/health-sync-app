# HealthSyncApp

Android 端末の [Health Connect](https://developer.android.com/health-and-digital-fitness/health-connect) からヘルスデータを読み取り、クラウドの API に同期する Android アプリです。

## 対応データ

- 体重 / 体脂肪率
- 血圧 / 心拍数
- 睡眠
- 歩数

## 技術スタック

| カテゴリ | ライブラリ |
|---------|-----------|
| UI | Jetpack Compose + Material 3 |
| Health | Health Connect Client 1.1.0-alpha10 |
| HTTP | Ktor Client 2.3.7 |
| Serialization | kotlinx.serialization 1.6.2 |
| Background | WorkManager 2.9.0 |
| Language | Kotlin 2.0.21 |
| Build | AGP 9.0.0 / Gradle |

## アーキテクチャ

```
MainActivity.kt (UI - Jetpack Compose)
    ↓
HealthConnectManager.kt (Domain - Health Connect API wrapper)
    ↓
HealthSyncApiClient.kt (API - Ktor HTTP client for cloud sync)
```

## セットアップ

### 前提条件

- Android Studio
- Android SDK (API Level 36+)
- Health Connect 対応デバイスまたはエミュレータ

### 手順

1. リポジトリをクローン

```bash
git clone <repository-url>
cd HealthSyncApp
```

2. `local.properties` を設定

```bash
cp local.properties.example local.properties
```

`local.properties` を開き、以下を実際の値に書き換えてください：

```properties
sdk.dir=/path/to/your/Android/sdk
HEALTH_SYNC_API_KEY=your-api-key-here
HEALTH_SYNC_API_URL=https://your-api-endpoint/sync
```

3. ビルド

```bash
./gradlew assembleDebug
```

## ビルドコマンド

```bash
./gradlew build                # フルビルド
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK
./gradlew test                 # ユニットテスト
./gradlew connectedAndroidTest # インストルメンテーションテスト (要デバイス)
./gradlew lint                 # Lint チェック
```

## License

MIT
