# Kotlin Multiplatform: что это и нужен ли он мне. Сборка в GitHub без локального окружения

Контекст: вопрос возник после разбора Meshtastic-Android v2.8.0 (см. [`../connection/`](../connection/)),
который оказался KMP-проектом.

---

## 1. Что такое Kotlin Multiplatform

KMP — это способ **писать бизнес-логику один раз и компилировать её под разные платформы**. Kotlin умеет
компилироваться не только в JVM-байткод (Android), но и в нативный код (iOS, macOS, Windows, Linux) и в JS/Wasm.

Код раскладывается по «source sets»:

```
core/ble/src/
├── commonMain/   ← общий код, БЕЗ android.* и java.* — компилируется под все платформы
├── androidMain/  ← Android-специфика (BluetoothDevice.createBond(), Context, BroadcastReceiver)
├── iosMain/      ← iOS-специфика
└── jvmMain/      ← Desktop
```

Именно поэтому в исследованном коде встречаются такие конструкции:

```kotlin
// commonMain — объявление «здесь будет платформенная реализация»
internal expect fun PeripheralBuilder.platformConfig(device: BleDevice, autoConnect: () -> Boolean)

// androidMain — конкретная реализация
internal actual fun PeripheralBuilder.platformConfig(...) {
    requestMtu(512)   // чисто андроидная вещь
}
```

Meshtastic использует KMP, чтобы из одного кода собирать **Android-приложение и десктопное приложение**
(плюс заготовка iOS: в `build-logic` объявлены таргеты `iosArm64`, `iosSimulatorArm64`). Логика меша, парсинг
пакетов, реконнект — общие; различается только тонкий слой доступа к железу.

### Вывод для собственного приложения

KMP — это не «бесплатная» абстракция. Он приносит convention-плагины, `expect`/`actual` и ограничения на
`commonMain` (нельзя `java.io` → только Okio, нельзя `Dispatchers.IO` напрямую), а также заметно более сложную
сборку.

**Если приложение делается только под Android — KMP не нужен.** Берём обычный Android-проект и переносим из
исследования идеи (слои, протокол, жизненный цикл), а не структуру модулей.

---

## 2. Сборка в GitHub — да, бесплатно и без локальных установок

Сам Meshtastic-Android именно так и собирается (проверено по `.github/workflows/`):

* раннеры **`ubuntu-24.04-arm`** — обычный Linux, не macOS;
* JDK 25 через `actions/setup-java@v5`;
* команды вида `./gradlew androidApp:assembleFdroidDebug androidApp:assembleGoogleDebug`.

Android SDK **предустановлен** на GitHub-раннерах, Gradle сам скачивает всё остальное. Локальный ПК в процессе
не участвует.

### Лимиты

| Условие | Что даёт |
| :--- | :--- |
| **Публичный репозиторий** | Минуты стандартных раннеров **не тарифицируются вообще** |
| Приватный на бесплатном тарифе | 2000 минут/мес; macOS расходует квоту ×10, Windows ×2 |
| Артефакты | APK сохраняется как artifact workflow (по умолчанию 90 дней) — скачивается на телефон и ставится вручную |

### Минимальный рабочий workflow

```yaml
# .github/workflows/build.yml
name: build
on: [push, workflow_dispatch]
jobs:
  apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4      # кэш Gradle
      - run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk
```

### Разработка прямо в браузере

**GitHub Codespaces** — полноценная Linux-машина с VS Code; на бесплатном тарифе личного аккаунта примерно
120 core-часов и 15 ГБ в месяц. Gradle там работает.

---

## 3. Чего в облаке не получится — главное ограничение

**Протестировать Bluetooth в CI невозможно.** У облачных раннеров нет BT-адаптера, у эмулятора Android нет
BLE-стека, пробрасываемого к железу, и реальной ноды там тоже нет.

Отсюда реальный цикл работы:

| Этап | Где |
| :--- | :--- |
| Написание кода | Локально (Android Studio бесплатна) или в Codespaces |
| Сборка APK, линтеры, unit-тесты | GitHub Actions ✅ бесплатно |
| **Проверка подключения к ноде** | **Только физический Android-телефон + реальная нода** ❌ невозможно в облаке |

То есть «собрать без ничего локального» — да. «Разрабатывать BLE-приложение, не имея телефона и ноды» — нет.

Именно поэтому у Meshtastic есть `MockRadioTransport` и `ReplayRadioTransport` (проигрывание записанного дампа
трафика): бо́льшую часть логики — парсинг пакетов, UI, базу нод — можно отлаживать без железа. Это стоит
скопировать одним из первых.

---

## 4. Отдельно: сборка самого Meshtastic-Android

Форк эталонного проекта собрать **сложнее**, чем свой с нуля:

* требуется JDK 25 и JetBrains Runtime;
* нужен `local.properties`, скопированный из `secrets.defaults.properties`;
* для флейвора `google` нужны секреты (Maps API key, DataDog) — собирать надо флейвор **`fdroid`**, он без
  Google-зависимостей: `./gradlew :androidApp:assembleFdroidDebug`.
